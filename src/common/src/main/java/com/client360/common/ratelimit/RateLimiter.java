package com.client360.common.ratelimit;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-window request counting for SPEC.md §4.10.
 *
 * <p>Counted in the database rather than in memory because a per-instance counter multiplies by the
 * number of replicas: two instances behind a load balancer would pass 120 probes a minute through a
 * limit of 60 while reporting the limit as enforced. The stack has no Redis, so the shared store is
 * the database the service is already talking to (migration V7 argues this at length).
 *
 * <p>The window is floored from {@code now()} in PostgreSQL, never the JVM clock — two instances
 * have to agree on where a window begins (CLAUDE.md rule 7).
 */
@Component
public class RateLimiter {

    private final JdbcClient jdbc;

    public RateLimiter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts one request and refuses it if the window is already full.
     *
     * <p>Runs in its own transaction. A limiter that joined the caller's would roll its count back
     * when the request failed, so a caller could probe indefinitely as long as every probe ended in
     * an error — which is exactly the shape of enumeration.
     *
     * @throws ApiException {@code 429 RATE_LIMIT_EXCEEDED} with {@code Retry-After} in seconds
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String subject, String bucket, int limit, Duration window) {
        long windowSeconds = window.toSeconds();
        Counter counter = jdbc.sql("""
                        INSERT INTO rate_limit_counters (subject, bucket, window_start, hits)
                        VALUES (
                            :subject, :bucket,
                            to_timestamp(floor(extract(epoch FROM now()) / :windowSeconds) * :windowSeconds),
                            1)
                        ON CONFLICT (subject, bucket, window_start)
                            DO UPDATE SET hits = rate_limit_counters.hits + 1
                        RETURNING hits, window_start
                        """)
                .param("subject", subject)
                .param("bucket", bucket)
                .param("windowSeconds", windowSeconds)
                .query((rs, n) -> new Counter(
                        rs.getInt("hits"),
                        rs.getObject("window_start", OffsetDateTime.class).toInstant()))
                .single();

        if (counter.hits() > limit) {
            // Seconds until this window closes, which is when the caller may try again. Computed
            // from the window the database returned rather than from a local clock, so the number
            // the caller is told matches the row that will actually let them through.
            long retryAfter = Math.max(
                    1,
                    counter.windowStart().plusSeconds(windowSeconds).getEpochSecond()
                            - Instant.now().getEpochSecond());
            throw new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            ErrorCodes.RATE_LIMIT_EXCEEDED,
                            "Too many requests. Try again in " + retryAfter + " seconds.")
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .detail("limit", limit, "windowSeconds", windowSeconds, "retryAfterSeconds", retryAfter);
        }
    }

    /** Counters outside their window are not audit data and may be deleted. */
    @Scheduled(fixedDelayString = "${client360.rate-limit.purge-interval:10m}")
    public void purgeExpired() {
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM rate_limit_counters
                             WHERE ctid IN (SELECT ctid FROM rate_limit_counters
                                             WHERE window_start < now() - INTERVAL '2 hours'
                                             LIMIT 1000)
                            """).update();
        } while (deleted == 1000);
    }

    private record Counter(int hits, Instant windowStart) {}
}
