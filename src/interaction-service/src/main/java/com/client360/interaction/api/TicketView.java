package com.client360.interaction.api;

import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Set;

/**
 * The ticket block of an interaction (SPEC.md §6.3). {@code null} on everything that is not a
 * ticket, which is how the feed knows to render an SLA badge on one row and not the next.
 *
 * @param slaBreached computed against the clock, never stored. {@code now()} is not
 *     {@code IMMUTABLE}, so a column would be stale the moment it was written — the same reason
 *     TR-BR-02 computes overdue rather than materializing it
 * @param legalTargets the statuses this ticket may move to (IL-BR-08). Sent so the UI can disable
 *     the rest from the server's own table instead of a copy that drifts; the service still
 *     validates every transition, because a disabled button is a convenience and not a control
 * @param stale IL-EC-10: paused on the client for more than a fortnight. Computed like
 *     {@code slaBreached} and for the same reason — it is a fact about the clock, not a column.
 *     This is what stops {@code WAITING_CLIENT} from being a way to hide a ticket from the SLA
 * @param slaPausedSeconds business seconds returned by {@code WAITING_CLIENT} pauses so far.
 *     Present so a supervisor looking at a deadline can see it was moved and by how much, rather
 *     than wondering why it is later than the priority implies (IL-BR-08)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TicketView(
        TicketStatus status,
        TicketPriority priority,
        UserSummary assignee,
        Instant slaDueAt,
        boolean slaBreached,
        Instant resolvedAt,
        Instant closedAt,
        String resolutionNote,
        Instant waitingSince,
        boolean stale,
        long slaPausedSeconds,
        Set<TicketStatus> legalTargets) {

    /** @param assignee resolved through the user directory, or {@code null} while unassigned */
    public static TicketView of(Interaction.Ticket ticket, UserSummary assignee, Instant now) {
        if (ticket == null) {
            return null;
        }
        return new TicketView(
                ticket.status(),
                ticket.priority(),
                assignee,
                ticket.slaDueAt(),
                ticket.isBreached(now),
                ticket.resolvedAt(),
                ticket.closedAt(),
                ticket.resolutionNote(),
                ticket.waitingSince(),
                ticket.isStale(now),
                ticket.pausedSeconds(),
                ticket.status().legalTargets());
    }
}
