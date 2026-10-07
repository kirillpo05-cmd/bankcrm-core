package com.client360.interaction.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code interaction.tasks} (SPEC.md §7.2.2).
 *
 * <p>{@code clientId} is immutable (TR-BR-01) — it is what lets task authorization derive from the
 * client's scope with no second mechanism. {@code originalDueAt} is frozen at creation and is the
 * record of what was originally promised; {@code dueAt} may only move forward from it (TR-BR-04).
 *
 * <p><strong>There is no {@code overdue} field.</strong> Overdue is a function of the clock, so it
 * is computed from {@code dueAt} and {@code status} whenever it is asked for (TR-BR-02). Storing it
 * would mean a window in which the record disagrees with the calendar.
 */
public record Task(
        UUID id,
        UUID clientId,
        UUID sourceInteractionId,
        String title,
        String description,
        TaskType taskType,
        TaskPriority priority,
        TaskStatus status,
        Instant dueAt,
        Instant originalDueAt,
        UUID assigneeId,
        UUID createdBy,
        int snoozeCount,
        Instant lastSnoozedAt,
        String lastSnoozeReason,
        Instant completedAt,
        UUID completedBy,
        String completionNote,
        Instant cancelledAt,
        UUID cancelledBy,
        String cancellationReason,
        int escalationLevel,
        Instant escalatedAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    /** TR-BR-06: three snoozes per task, and the response tells the caller how many are left. */
    public static final int SNOOZE_CAP = 3;

    /**
     * TR-BR-02, as one expression and in one place.
     *
     * @param now from the database clock, never the JVM's (rule 7): a task is late or not by the
     *     same clock that every {@code due_at} was compared against when it was written
     */
    public boolean isOverdue(Instant now) {
        return status.isLive() && dueAt.isBefore(now);
    }

    /** How late, for the dashboard's age buckets. Zero rather than negative when not overdue. */
    public double overdueByHours(Instant now) {
        if (!isOverdue(now)) {
            return 0;
        }
        return Duration.between(dueAt, now).toMinutes() / 60.0;
    }

    /** Negative once the deadline has passed, which is what lets one field serve both states. */
    public double hoursUntilDue(Instant now) {
        return Duration.between(now, dueAt).toMinutes() / 60.0;
    }

    /**
     * TR-BR-02's other half: a {@code DONE} task finished after the original promise was completed
     * late, which is a different metric from overdue and the one TR-US-08 coaches on.
     */
    public boolean completedLate() {
        return status == TaskStatus.DONE && completedAt != null && completedAt.isAfter(originalDueAt);
    }

    public int snoozesRemaining() {
        return Math.max(0, SNOOZE_CAP - snoozeCount);
    }
}
