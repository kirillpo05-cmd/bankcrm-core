package com.client360.interaction.api;

import com.client360.interaction.domain.Task;
import com.client360.interaction.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /tasks} (SPEC.md §7.3, TR-US-02).
 *
 * <p>{@code buckets} counts the whole filtered set, not the page, so the UI's group headers stay
 * correct while somebody pages through them. {@code generatedAt} is the instant every
 * {@code overdue} in this page was computed against — one clock for the whole response, so two rows
 * cannot disagree about what "now" was (rule 7).
 */
public record TaskListResponse(
        List<Entry> content,
        Map<String, Long> buckets,
        Instant generatedAt,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static TaskListResponse of(TaskService.TaskPage page) {
        return new TaskListResponse(
                page.content().stream()
                        .map(task -> Entry.of(task, page.people(), page.now()))
                        .toList(),
                page.buckets(),
                page.now(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext());
    }

    /**
     * A row, not a card. No description, no history and no reminders: a list of forty tasks does not
     * need forty descriptions, and the drawer fetches the full task when one is opened.
     */
    public record Entry(
            UUID id,
            UUID clientId,
            String title,
            Instant dueAt,
            com.client360.interaction.domain.TaskPriority priority,
            com.client360.interaction.domain.TaskStatus status,
            com.client360.interaction.domain.TaskType taskType,
            boolean overdue,
            double overdueByHours,
            int escalationLevel,
            int snoozeCount,
            TaskResponse.Person assignee) {

        static Entry of(Task task, Map<UUID, UserSummary> people, Instant now) {
            UserSummary assignee = people.get(task.assigneeId());
            return new Entry(
                    task.id(),
                    task.clientId(),
                    task.title(),
                    task.dueAt(),
                    task.priority(),
                    task.status(),
                    task.taskType(),
                    task.isOverdue(now),
                    Math.round(task.overdueByHours(now) * 10) / 10.0,
                    task.escalationLevel(),
                    task.snoozeCount(),
                    new TaskResponse.Person(task.assigneeId(), assignee == null ? null : assignee.fullName()));
        }
    }
}
