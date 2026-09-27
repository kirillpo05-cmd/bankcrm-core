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
 * <p>{@code ticket} is the subtype: non-null exactly when {@code type} is {@code TICKET}, which is
 * what {@code ck_interactions_ticket_fields} enforces in the database. Only a ticket has a
 * lifecycle; everything else in the feed is a fact that happened once.
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
        Ticket ticket,
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

    public boolean isTicket() {
        return type == InteractionType.TICKET;
    }

    /**
     * The ticket lifecycle of §6.2.2 (IL-BR-07, IL-BR-08).
     *
     * @param slaDueAt derived from {@code priority} at creation and frozen, except that raising
     *     priority recomputes it from the original {@code occurredAt} and leaving
     *     {@code WAITING_CLIENT} shifts it forward by the business hours the pause consumed
     * @param waitingSince when the current {@code WAITING_CLIENT} period began, {@code null}
     *     otherwise — the pair is a database constraint, not a convention
     * @param pausedSeconds business seconds given back across every pause so far. Reporting only:
     *     {@code slaDueAt} already carries the same shift, which is what keeps "breached" a plain
     *     comparison the queue index can serve
     */
    public record Ticket(
            TicketStatus status,
            TicketPriority priority,
            UUID assigneeId,
            Instant slaDueAt,
            Instant resolvedAt,
            Instant closedAt,
            String resolutionNote,
            Instant waitingSince,
            long pausedSeconds) {

        /**
         * Whether the deadline was missed.
         *
         * <p>A resolved ticket is judged against when it was resolved, not against now — otherwise
         * every ticket ever closed late would drift further into breach forever, and "how many did
         * we miss" would depend on when you asked.
         *
         * <p>A paused ticket is never breached: the clock is not running, and {@code slaDueAt} has
         * not yet been shifted for the pause in progress (that happens on leaving
         * {@code WAITING_CLIENT}), so comparing against it mid-pause would report a breach the
         * bank did not cause (IL-BR-08).
         */
        public boolean isBreached(Instant now) {
            if (status.pausesSla()) {
                return false;
            }
            return (resolvedAt != null ? resolvedAt : now).isAfter(slaDueAt);
        }

        /** IL-EC-10: past this a paused ticket is reported as stale rather than simply quiet. */
        public static final Duration STALE_AFTER = Duration.ofDays(14);

        /**
         * IL-EC-10: a pause that has gone on too long.
         *
         * <p>The counterpart to {@link #isBreached}, and the reason it can safely answer
         * {@code false} while paused. Waiting on a client is legitimate and stops the SLA; waiting
         * for three weeks is not, and without this a ticket could be parked indefinitely with a
         * clean SLA — a paused clock must not become a hiding place.
         *
         * <p>Calendar days, not business hours: the question is how long the client has been silent,
         * and a client does not work office hours.
         */
        public boolean isStale(Instant now) {
            return status.pausesSla() && waitingSince != null && waitingSince.isBefore(now.minus(STALE_AFTER));
        }
    }
}
