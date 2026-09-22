package com.client360.interaction.api;

import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
import com.client360.interaction.persistence.InteractionRepository.TimelineRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the client timeline (SPEC.md §6.3, IL-US-01).
 *
 * <p>Carries a {@value Interaction#PREVIEW_LENGTH}-character {@code bodyPreview} rather than the
 * body, and emits no audit event. Opening the full interaction does (IL-BR-11) — scrolling a feed
 * is not a disclosure, and treating it as one would bury the real reads in a seven-year log.
 *
 * @param bodyPreview {@code null} on someone else's private note: an admin sees that it exists,
 *     never what it says (IL-BR-09)
 * @param correctsId set on a correction, pointing at the original it amends
 * @param hasCorrection set on an original that has been corrected, so the feed renders the pair
 *     together with this one visibly superseded (IL-BR-03)
 * @param edited the wording was changed inside the author's window (IL-BR-02)
 * @param deleted only ever true in an auditor's {@code includeDeleted} view, where the row is shown
 *     struck through with who removed it and why (§6.3)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TimelineEntry(
        UUID id,
        InteractionType type,
        InteractionDirection direction,
        String subject,
        String bodyPreview,
        Instant occurredAt,
        Integer durationSeconds,
        InteractionOutcome outcome,
        InteractionVisibility visibility,
        UserSummary author,
        int attachmentCount,
        UUID correctsId,
        boolean hasCorrection,
        boolean edited,
        Instant createdAt,
        boolean deleted,
        Instant deletedAt,
        UserSummary deletedBy,
        String deletionReason) {

    public static TimelineEntry of(TimelineRow row, UserSummary author, UserSummary deletedBy, UUID viewerId) {
        Interaction interaction = row.interaction();
        return new TimelineEntry(
                interaction.id(),
                interaction.type(),
                interaction.direction(),
                interaction.subject(),
                interaction.canReadBodyAs(viewerId) ? interaction.bodyPreview() : null,
                interaction.occurredAt(),
                interaction.durationSeconds(),
                interaction.outcome(),
                interaction.visibility(),
                author,
                row.attachmentCount(),
                interaction.correctsId(),
                row.hasCorrection(),
                interaction.isEdited(),
                interaction.createdAt(),
                interaction.isDeleted(),
                interaction.deletedAt(),
                deletedBy,
                interaction.deletionReason());
    }
}
