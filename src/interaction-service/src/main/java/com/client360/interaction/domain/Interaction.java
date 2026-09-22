package com.client360.interaction.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code interaction.interactions} with its body already decrypted (SPEC.md §6.2.2).
 *
 * <p>Immutable in substance (IL-BR-01): {@code type}, {@code clientId}, {@code occurredAt} and
 * {@code authorId} never change. Only {@code subject} and {@code body} may be edited, only by the
 * author, and only inside the window below — after that the remedy is a correction, a new row with
 * {@code correctsId} set, never a rewrite (IL-BR-03).
 *
 * <p>The ticket subtype columns are not mapped yet. Nothing can create a {@code TICKET} until the
 * ticket endpoints of §6.3 land, so there is no row to read them from; they arrive with that work
 * rather than sitting here unused.
 */
public record Interaction(
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
        String externalRef,
        UUID authorId,
        UUID correctsId,
        Instant editedAt,
        int editCount,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        UUID deletedBy,
        String deletionReason) {

    /**
     * IL-BR-02. Measured from {@code createdAt}, not {@code occurredAt}: the window exists to let
     * an author fix a typo they just made, and a call logged three days late would otherwise
     * arrive already uneditable.
     */
    public static final Duration EDIT_WINDOW = Duration.ofMinutes(15);

    /** IL-BR-11: the timeline shows this much and audits nothing. */
    public static final int PREVIEW_LENGTH = 160;

    public Instant editableUntil() {
        return createdAt.plus(EDIT_WINDOW);
    }

    /** IL-BR-02: the author, inside the window. Not a supervisor, and not the author later. */
    public boolean isEditableBy(UUID userId, Instant now) {
        return authorId.equals(userId) && now.isBefore(editableUntil());
    }

    /**
     * IL-BR-09, first half: whether the caller may know this interaction exists at all. A private
     * note is visible to its author and to an admin; a supervisor does not learn it is there — a
     * product decision, not an oversight (§12 Q-07).
     */
    public boolean isVisibleTo(UUID userId, boolean admin) {
        return visibility != InteractionVisibility.PRIVATE || admin || authorId.equals(userId);
    }

    /**
     * IL-BR-09, second half: whether the caller may read what it says. For a private note only the
     * author can. An admin sees that it exists and its metadata, never its body — otherwise
     * "private" would mean "private from everyone except the people with the most access", and
     * managers would go back to paper.
     */
    public boolean canReadBodyAs(UUID userId) {
        return visibility != InteractionVisibility.PRIVATE || authorId.equals(userId);
    }

    /** IL-BR-01's substance stayed put; the wording was touched inside the window. */
    public boolean isEdited() {
        return editCount > 0;
    }

    /**
     * The first {@value #PREVIEW_LENGTH} characters of the body. Reading a preview is not a
     * disclosure; opening the full body is (IL-BR-11).
     */
    public String bodyPreview() {
        if (body == null) {
            return null;
        }
        return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH);
    }

    public boolean isCorrection() {
        return correctsId != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
