package com.client360.interaction.api;

import com.client360.interaction.domain.ReminderChannel;
import com.client360.interaction.domain.ReminderStatus;
import com.client360.interaction.domain.Task;
import com.client360.interaction.domain.TaskPriority;
import com.client360.interaction.domain.TaskStatus;
import com.client360.interaction.domain.TaskType;
import com.client360.interaction.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One task on the wire (SPEC.md §7.3).
 *
 * <p>{@code overdue}, {@code overdueByHours} and {@code hoursUntilDue} are computed here from the
 * one {@code now} the service read out of PostgreSQL (TR-BR-02, rule 7). Three fields rather than
 * one so the UI never has to do date arithmetic to decide what colour a row is — and all three
 * agree, because they come from the same instant.
 *
 * <p>{@code snoozesRemaining} is sent alongside {@code snoozeCount} because the cap is a rule the
 * user needs to see coming (TR-BR-06). A manager who learns about it on the fourth attempt has
 * already planned around a delay they cannot have.
 */
public record TaskResponse(
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
        boolean overdue,
        double overdueByHours,
        double hoursUntilDue,
        Person assignee,
        Person createdBy,
        int snoozeCount,
        int snoozesRemaining,
        Instant lastSnoozedAt,
        String lastSnoozeReason,
        Instant completedAt,
        Person completedBy,
        String completionNote,
        boolean completedLate,
        Instant cancelledAt,
        String cancellationReason,
        int escalationLevel,
        Instant escalatedAt,
        List<Reminder> reminders,
        List<HistoryEntry> history,
        int version) {

    public static TaskResponse of(TaskService.TaskDetail detail) {
        Task task = detail.task();
        Instant now = detail.now();
        return new TaskResponse(
                task.id(),
                task.clientId(),
                task.sourceInteractionId(),
                task.title(),
                task.description(),
                task.taskType(),
                task.priority(),
                task.status(),
                task.dueAt(),
                task.originalDueAt(),
                task.isOverdue(now),
                round(task.overdueByHours(now)),
                round(task.hoursUntilDue(now)),
                person(task.assigneeId(), detail.people()),
                person(task.createdBy(), detail.people()),
                task.snoozeCount(),
                task.snoozesRemaining(),
                task.lastSnoozedAt(),
                task.lastSnoozeReason(),
                task.completedAt(),
                person(task.completedBy(), detail.people()),
                task.completionNote(),
                task.completedLate(),
                task.cancelledAt(),
                task.cancellationReason(),
                task.escalationLevel(),
                task.escalatedAt(),
                detail.reminders().stream()
                        .map(reminder -> new Reminder(
                                reminder.remindAt(), reminder.channel(), reminder.status(), reminder.sentAt()))
                        .toList(),
                detail.history().stream()
                        .map(entry -> new HistoryEntry(
                                entry.changedAt(),
                                person(entry.changedBy(), detail.people()),
                                entry.changeType(),
                                entry.fromValue(),
                                entry.toValue(),
                                entry.reason()))
                        .toList(),
                task.version());
    }

    /** One decimal place. A dashboard showing 52.43829 hours late is reporting its own arithmetic. */
    private static double round(double hours) {
        return Math.round(hours * 10) / 10.0;
    }

    /**
     * A name for an id, or the id alone when the directory could not be reached.
     *
     * <p>Degrading to an id is deliberate: a task list that renders "3f1c…" is worse than one that
     * renders "Adam Nowak" and far better than an error page, and client-service being slow must
     * not take the task board down with it.
     */
    private static Person person(UUID id, Map<UUID, UserSummary> people) {
        if (id == null) {
            return null;
        }
        UserSummary summary = people.get(id);
        return new Person(id, summary == null ? null : summary.fullName());
    }

    public record Person(UUID id, String fullName) {}

    public record Reminder(Instant remindAt, ReminderChannel channel, ReminderStatus status, Instant sentAt) {}

    /** §7.2.4's product history, rendered in the drawer: who moved this task, and why. */
    public record HistoryEntry(
            Instant changedAt, Person changedBy, String changeType, String fromValue, String toValue, String reason) {}
}
