package com.client360.audit.api;

import java.time.Instant;

/**
 * {@code POST /audit/verify} body (SPEC.md §8.3, AT-US-03).
 *
 * <p>Either a chain and a sequence range, or a time range across every chain. Both empty verifies
 * everything, which on seven years of log is not something to start by accident — the caller says
 * what they mean.
 */
public record VerifyRequest(Integer chainId, Long fromSeq, Long toSeq, Instant from, Instant to) {

    public boolean isUnbounded() {
        return chainId == null && fromSeq == null && toSeq == null && from == null && to == null;
    }
}
