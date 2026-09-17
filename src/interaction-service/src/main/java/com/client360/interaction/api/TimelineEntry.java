package com.client360.interaction.api;

import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the client timeline (SPEC.md §6.3, IL-US-01).
 *
 * <p>Carries a {@value Interaction#PREVIEW_LENGTH}-character {@code bodyPreview} rather than the
 * body, and emits no audit event. Opening the full interaction does (IL-BR-11) — scrolling a feed
 * is not a disclosure, and treating it as one would bury the real reads in a seven-year log.
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
        UUID correctsId,
        boolean corrected,
        Instant createdAt) {

    public static TimelineEntry of(Interaction interaction, UserSummary author) {
        return new TimelineEntry(
                interaction.id(),
                interaction.type(),
                interaction.direction(),
                interaction.subject(),
                interaction.bodyPreview(),
                interaction.occurredAt(),
                interaction.durationSeconds(),
                interaction.outcome(),
                interaction.visibility(),
                author,
                interaction.correctsId(),
                interaction.isCorrection(),
                interaction.createdAt());
    }
}
