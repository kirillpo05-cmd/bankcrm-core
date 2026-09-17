package com.client360.interaction.domain;

/** {@code interaction.interaction_outcome} (SPEC.md §6.2.1). */
public enum InteractionOutcome {
    SUCCESSFUL,
    NO_ANSWER,
    CALLBACK_REQUESTED,
    ESCALATED,
    REFUSED,
    NOT_APPLICABLE;
}
