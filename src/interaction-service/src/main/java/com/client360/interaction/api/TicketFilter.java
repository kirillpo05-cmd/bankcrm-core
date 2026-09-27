package com.client360.interaction.api;

import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import java.util.List;
import java.util.UUID;

/**
 * The query of {@code GET /tickets} (SPEC.md §6.3).
 *
 * <p>Bound as typed enums rather than strings, so an unknown {@code status} is a {@code 400} at the
 * edge instead of a value that reaches a query and quietly matches nothing.
 *
 * @param assigneeId whose desk. Absent means the caller's own, which is the one queue that needs no
 *     client scope to authorize
 * @param slaBreached the supervisor's "what did we miss" view; a paused ticket is never breached
 *     (IL-BR-08)
 * @param stale IL-EC-10: the other half of that view — tickets parked on the client for more than
 *     a fortnight, which no breach filter would ever surface
 */
public record TicketFilter(
        UUID assigneeId,
        UUID clientId,
        List<TicketStatus> statuses,
        TicketPriority priority,
        boolean slaBreached,
        boolean stale) {

    public TicketFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
