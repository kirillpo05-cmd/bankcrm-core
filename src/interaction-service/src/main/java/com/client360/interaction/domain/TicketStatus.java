package com.client360.interaction.domain;

import java.util.Set;

/**
 * {@code interaction.ticket_status} and the legal transitions between them (IL-BR-08).
 *
 * <p>The table lives here rather than in the service so the illegal-transition answer can name the
 * legal targets, and so the UI's disabled buttons and the service's refusal come from one list. The
 * UI disabling a target is a convenience; this is the enforcement.
 */
public enum TicketStatus {
    NEW,
    IN_PROGRESS,
    WAITING_CLIENT,
    RESOLVED,
    CLOSED,
    REJECTED;

    /** IL-BR-08. {@code CLOSED} and {@code REJECTED} are terminal — nothing reopens them. */
    public Set<TicketStatus> legalTargets() {
        return switch (this) {
            case NEW -> Set.of(IN_PROGRESS, REJECTED);
            case IN_PROGRESS -> Set.of(WAITING_CLIENT, RESOLVED, REJECTED);
            case WAITING_CLIENT -> Set.of(IN_PROGRESS, RESOLVED);
            case RESOLVED -> Set.of(CLOSED, IN_PROGRESS);
            case CLOSED, REJECTED -> Set.of();
        };
    }

    public boolean canMoveTo(TicketStatus target) {
        return legalTargets().contains(target);
    }

    /** {@code ck_interactions_ticket_resolution}: a refusal and a fix both need saying why. */
    public boolean requiresResolutionNote() {
        return this == RESOLVED || this == REJECTED;
    }

    /** {@code ck_interactions_ticket_resolved_at}. */
    public boolean isResolvedOrClosed() {
        return this == RESOLVED || this == CLOSED;
    }

    /**
     * IL-BR-08: {@code WAITING_CLIENT} pauses the SLA clock. The ticket is not late while the bank
     * is not the one holding it up — and IL-EC-10's staleness rule is what stops that from becoming
     * a place to hide.
     */
    public boolean pausesSla() {
        return this == WAITING_CLIENT;
    }

    /** Past these the SLA no longer runs and the queue no longer shows the ticket. */
    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }
}
