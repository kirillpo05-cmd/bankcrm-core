package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;

/**
 * §4.10's PII-enumeration guard on {@code GET /clients/lookup}, and the V7 table that counts it.
 *
 * <p>Lookup is the one endpoint that answers "does a client with this email exist" across the whole
 * book — the narrow ER-01 exception that lets a manager raise a break-glass request (RB-US-05) — so
 * it is also the endpoint an attacker would use to enumerate customers. The limit is the only thing
 * between those two facts.
 */
@Import(MatrixAccessPolicy.Config.class)
class LookupRateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final int LIMIT = 60;

    @Nested
    class Throttling {

        @Test
        void allowsTheWholeMinutesWorth_4_10() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                mvc.perform(lookup("nobody" + i + "@example.com")).andExpect(status().isOk());
            }
        }

        @Test
        void refusesTheRequestPastTheLimit_RATE_LIMIT_EXCEEDED() throws Exception {
            for (int i = 0; i < LIMIT; i++) {
                mvc.perform(lookup("nobody" + i + "@example.com")).andExpect(status().isOk());
            }
            mvc.perform(lookup("onemore@example.com"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                    // §4.3: the caller is told when to come back, not just that they were refused.
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andExpect(jsonPath("$.details[0].limit").value(LIMIT));
        }

        /** Per user. One manager burning their allowance must not throttle their colleagues. */
        @Test
        void theLimitIsPerUser_4_10() throws Exception {
            for (int i = 0; i < LIMIT + 1; i++) {
                mvc.perform(lookup("nobody" + i + "@example.com", ADAM_NOWAK));
            }
            mvc.perform(lookup("nobody@example.com", ADAM_NOWAK)).andExpect(status().isTooManyRequests());
            mvc.perform(lookup("nobody@example.com", MARTA_LEWANDOWSKA)).andExpect(status().isOk());
        }

        /**
         * A probe that ends in an error still costs the prober a request. The limiter commits in its
         * own transaction, so a caller cannot reset their count by making every attempt fail — which
         * is the shape enumeration actually takes.
         */
        @Test
        void afailedLookupStillCounts() throws Exception {
            // A malformed query is a 400, and 400s are exactly what a prober would generate.
            for (int i = 0; i < 5; i++) {
                mvc.perform(get("/api/v1/clients/lookup").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                        .andExpect(status().isBadRequest());
            }
            assertThat(hits("clients.lookup")).isEqualTo(5);
        }

        /** Only lookup is guarded here; reading a card the caller already has is not enumeration. */
        @Test
        void otherEndpointsAreNotCountedAgainstIt() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            mvc.perform(get("/api/v1/clients/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk());
            assertThat(hits("clients.lookup")).isZero();
        }
    }

    /** The V7 constraints, tested the way {@code db-migrations.md} asks: by trying to break them. */
    @Nested
    class Schema {

        @Test
        void aCounterCannotBeZeroOrNegative() {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO client.rate_limit_counters"
                                    + " (subject, bucket, window_start, hits) VALUES ('u', 'b', :ts, 0)")
                            .param("ts", OffsetDateTime.now(ZoneOffset.UTC))
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_rate_limit_hits");
        }

        @Test
        void oneRowPerSubjectBucketAndWindow() {
            OffsetDateTime window = OffsetDateTime.now(ZoneOffset.UTC);
            insert(window);
            assertThatThrownBy(() -> insert(window))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("pk_rate_limit_counters");
        }

        private void insert(OffsetDateTime window) {
            jdbc.sql("INSERT INTO client.rate_limit_counters (subject, bucket, window_start, hits)"
                            + " VALUES ('u', 'b', :ts, 1)")
                    .param("ts", window)
                    .update();
        }
    }

    private int hits(String bucket) {
        Integer sum = jdbc.sql("SELECT sum(hits) FROM client.rate_limit_counters WHERE bucket = :bucket")
                .param("bucket", bucket)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return sum == null ? 0 : sum;
    }

    private org.springframework.test.web.servlet.RequestBuilder lookup(String email) {
        return lookup(email, ADAM_NOWAK);
    }

    private org.springframework.test.web.servlet.RequestBuilder lookup(String email, java.util.UUID actor) {
        return get("/api/v1/clients/lookup?email={email}", email).header(HttpHeaders.AUTHORIZATION, bearerFor(actor));
    }
}
