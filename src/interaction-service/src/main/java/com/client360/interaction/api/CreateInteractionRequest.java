package com.client360.interaction.api;

import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
import com.client360.interaction.domain.TicketPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /clients/{clientId}/interactions} body (SPEC.md §6.3, IL-US-02).
 *
 * <p>{@code occurredAt} is when the contact happened, which is not when it was logged: a manager
 * writes up yesterday's call this morning. The database tolerates five minutes of clock skew and
 * refuses the future beyond that (IL-EC-02).
 *
 * <p>{@code body} is encrypted at rest and PAN-masked before it gets there (IL-EC-05). That is why
 * there is no full-text search over bodies — a deliberate trade-off of §4.7.
 */
public record CreateInteractionRequest(
        @NotNull InteractionType type,
        InteractionDirection direction,
        @NotBlank @Size(max = 200) String subject,
        @Size(max = 10_000) String body,
        @NotNull Instant occurredAt,
        Integer durationSeconds,
        InteractionOutcome outcome,
        InteractionVisibility visibility,
        @Valid NewTicket ticket) {

    /**
     * The ticket block, required when {@code type} is {@code TICKET} and refused otherwise.
     *
     * <p>There is no {@code status}: a ticket always enters at {@code NEW}, or IL-BR-08's
     * transitions would guard a machine callers could start anywhere in. There is no
     * {@code slaDueAt} either — it is derived from {@code priority} in the owning team's business
     * hours and frozen (IL-BR-07), and a caller-supplied deadline would be a caller-supplied SLA.
     *
     * @param assigneeId optional; an unassigned ticket sits in the team queue until someone takes
     *     it, which is a real state and not an error
     */
    public record NewTicket(@NotNull TicketPriority priority, UUID assigneeId) {}

    /**
     * Column defaults, applied here so the response echoes what was stored. A note and a ticket are
     * internal by definition (ck_interactions_direction), so the caller does not have to say so.
     */
    public CreateInteractionRequest {
        outcome = outcome == null ? InteractionOutcome.NOT_APPLICABLE : outcome;
        visibility = visibility == null ? InteractionVisibility.TEAM : visibility;
        if (direction == null && type != null && type.isInternalOnly()) {
            direction = InteractionDirection.INTERNAL;
        }
    }
}
