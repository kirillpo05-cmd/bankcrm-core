package com.client360.audit.ingest;

import java.time.Instant;

/**
 * AT-EC-04: an event back-dated further than the pipeline could plausibly be behind.
 *
 * <p>Refused rather than stored, because storing it would silently place a record before entries
 * that already exist and break the chain's meaning as a timeline. It goes to the DLT as
 * {@code CLOCK_SKEW_SUSPECTED} for an operator to look at — a producer with a wrong clock and a
 * legitimate backfill after a long outage look identical from here, and only one of them should be
 * accepted. The backfill has its own operator-approved path, which stamps
 * {@code context.backfill: true}.
 */
public class ClockSkewSuspected extends RuntimeException {

    public ClockSkewSuspected(String topic, int partition, long offset, Instant occurredAt) {
        super("CLOCK_SKEW_SUSPECTED at %s-%d offset %d: occurredAt %s is further back than the skew limit"
                .formatted(topic, partition, offset, occurredAt));
    }
}
