package com.client360.audit.api;

import java.time.Instant;
import java.util.List;

/**
 * Ingestion health (SPEC.md §8.3, AT-US-06).
 *
 * <p>Reports the true gap rather than degrading quietly (AT-EC-05). This service being behind must
 * never block a mutation elsewhere — outbox rows accumulate, Kafka retains seven days, the consumer
 * catches up on restart — but "behind" has to be visible while it happens, or the first anybody
 * hears of it is a compliance question nobody can answer.
 */
public record AuditHealth(
        Status status, List<PartitionLag> partitions, long maxLagSeconds, long dltDepth, Instant oldestUnprocessedAt) {

    /** §8.3: below 60 s and an empty DLT is healthy; past 300 s or a non-empty DLT is not. */
    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public record PartitionLag(String topic, int partition, long lastOffset, long lagSeconds) {}

    public static AuditHealth of(List<PartitionLag> partitions, long dltDepth, Instant oldestUnprocessedAt) {
        long maxLag =
                partitions.stream().mapToLong(PartitionLag::lagSeconds).max().orElse(0);
        Status status;
        if (maxLag > 300) {
            status = Status.UNHEALTHY;
        } else if (maxLag > 60 || dltDepth > 0) {
            status = Status.DEGRADED;
        } else {
            status = Status.HEALTHY;
        }
        return new AuditHealth(status, partitions, maxLag, dltDepth, oldestUnprocessedAt);
    }
}
