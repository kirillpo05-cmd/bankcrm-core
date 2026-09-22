package com.client360.interaction.domain;

/** {@code interaction.interaction_type} (SPEC.md §6.2.1). */
public enum InteractionType {
    CALL,
    MEETING,
    EMAIL,
    CHAT,
    NOTE,
    TICKET;

    /**
     * A note and a ticket have no counterparty, so {@code ck_interactions_direction} pins them to
     * {@code INTERNAL}. Stated here rather than only in the DDL so the API can default it instead
     * of rejecting a body that was never ambiguous.
     */
    public boolean isInternalOnly() {
        return this == NOTE || this == TICKET;
    }

    /** {@code ck_interactions_duration}: only synchronous contact has a length. */
    public boolean allowsDuration() {
        return this == CALL || this == MEETING;
    }
}
