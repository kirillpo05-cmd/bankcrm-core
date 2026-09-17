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
 * A full interaction, body included (SPEC.md §6.3).
 *
 * <p>Returning this is a genuine disclosure and audits {@code READ_SENSITIVE}; a timeline row does
 * not, because it carries only a preview (IL-BR-11). That split is what keeps the audit log about
 * real reads instead of about scrolling.
 *
 * @param editableUntil when the author's 15-minute window closes (IL-BR-02). After it, the only
 *     remedy is a correction — a new interaction with {@code correctsId} set (IL-BR-03)
 * @param maskedCardNumbers how many card numbers the PAN detector redacted from the body before
 *     storing it, so the caller learns their note was changed (IL-EC-05)
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
        Instant editableUntil,
        Instant editedAt,
        int version,
        Instant createdAt,
        Integer maskedCardNumbers) {

    public static InteractionResponse of(Interaction interaction, UserSummary author, Integer maskedCardNumbers) {
        return new InteractionResponse(
                interaction.id(),
                interaction.clientId(),
                interaction.type(),
                interaction.direction(),
                interaction.subject(),
                interaction.body(),
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
                interaction.editableUntil(),
                interaction.editedAt(),
                interaction.version(),
                interaction.createdAt(),
                maskedCardNumbers);
    }
}
