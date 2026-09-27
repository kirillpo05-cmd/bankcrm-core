package com.client360.interaction.domain;

import java.time.Duration;

/**
 * {@code interaction.ticket_priority} (SPEC.md §6.2.1) and the SLA it buys (IL-BR-07).
 *
 * <p>The durations are <strong>business hours</strong>, not elapsed hours: Mon–Fri 09:00–18:00 in
 * the owning team's timezone. {@code LOW} is 168 business hours, which is about four calendar
 * weeks, not one — the difference is the whole reason {@link
 * com.client360.interaction.support.BusinessHours} exists rather than a {@code plus(Duration)}.
 */
public enum TicketPriority {
    LOW(168),
    MEDIUM(72),
    HIGH(24),
    CRITICAL(4);

    private final int businessHours;

    TicketPriority(int businessHours) {
        this.businessHours = businessHours;
    }

    public Duration sla() {
        return Duration.ofHours(businessHours);
    }

    /**
     * IL-BR-07: raising priority recomputes the deadline from the original {@code occurred_at},
     * which may put it in the past. Lowering it must not buy time that was never granted, so the
     * recompute only happens on a raise.
     */
    public boolean isHigherThan(TicketPriority other) {
        return ordinal() > other.ordinal();
    }
}
