package com.client360.interaction.api;

import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code PATCH /interactions/{id}/ticket} body (SPEC.md §6.3, IL-US-05).
 *
 * <p>Every field is optional and an absent one leaves the ticket alone. A {@code null} is read the
 * same way rather than as "clear this": there is nothing here worth clearing — a ticket always has
 * a status and a priority, and §6.3 defines no way to un-assign one. When it does, the field will
 * need the three-state treatment {@code ClientPatch} gives a merge-patch, and a two-state record
 * cannot be quietly stretched to cover it.
 *
 * @param resolutionNote required when moving to {@code RESOLVED} or {@code REJECTED}
 *     ({@code ck_interactions_ticket_resolution}) — a refusal and a fix both have to say why
 */
public record TicketPatch(
        TicketStatus status,
        TicketPriority priority,
        UUID assigneeId,
        @Size(max = 4000) String resolutionNote) {

    public boolean isEmpty() {
        return status == null && priority == null && assigneeId == null && resolutionNote == null;
    }
}
