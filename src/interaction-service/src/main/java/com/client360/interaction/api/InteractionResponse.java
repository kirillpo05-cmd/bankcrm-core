package com.client360.interaction.api;

import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A full interaction (SPEC.md §6.3).
 *
 * <p>Returning the body is a genuine disclosure and audits {@code READ_SENSITIVE}; a timeline row
 * does not, because it carries only a preview (IL-BR-11). That split is what keeps the audit log
 * about real reads instead of about scrolling.
 *
 * @param body {@code null} when the caller may see the interaction but not read it — an admin
 *     looking at someone else's private note (IL-BR-09)
 * @param editableUntil when the author's 15-minute window closes (IL-BR-02). After it, the only
 *     remedy is a correction — a new interaction with {@code correctsId} set (IL-BR-03)
 * @param corrections the amendments of this interaction, oldest first; empty on a correction,
 *     since chains are one level deep (IL-BR-05)
 * @param maskedCardNumbers how many card numbers the PAN detector redacted from the body before
 *     storing it, so the caller learns their text was changed (IL-EC-05)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InteractionResponse(
        UUID id,
        UUID clientId,
        InteractionType type,
        InteractionDirection direction,
        String subject,
        String body,
        Instant occurredAt,
        Integer durationSeconds,
        InteractionOutcome outcome,
        InteractionVisibility visibility,
        InteractionSource source,
        UserSummary author,
        List<Object> attachments,
        UUID correctsId,
        List<CorrectionSummary> corrections,
        Instant editableUntil,
        boolean edited,
        int editCount,
        Instant editedAt,
        int version,
        Instant createdAt,
        Integer maskedCardNumbers) {

    /** Enough to render "corrected on … by …" under the original; the detail is one click away. */
    public record CorrectionSummary(UUID id, String subject, UserSummary author, Instant createdAt) {}

    public static InteractionResponse of(
            Interaction interaction,
            boolean withBody,
            UserSummary author,
            List<CorrectionSummary> corrections,
            Integer maskedCardNumbers) {
        return new InteractionResponse(
                interaction.id(),
                interaction.clientId(),
                interaction.type(),
                interaction.direction(),
                interaction.subject(),
                withBody ? interaction.body() : null,
                interaction.occurredAt(),
                interaction.durationSeconds(),
                interaction.outcome(),
                interaction.visibility(),
                interaction.source(),
                author,
                // Attachments arrive with the attachment endpoints of §6.3; the field is present
                // so the response shape does not change when they do.
                List.of(),
                interaction.correctsId(),
                corrections,
                interaction.editableUntil(),
                interaction.isEdited(),
                interaction.editCount(),
                interaction.editedAt(),
                interaction.version(),
                interaction.createdAt(),
                maskedCardNumbers);
    }
}
