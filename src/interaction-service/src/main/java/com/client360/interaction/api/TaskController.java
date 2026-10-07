package com.client360.interaction.api;

import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import com.client360.interaction.domain.ReminderChannel;
import com.client360.interaction.domain.Task;
import com.client360.interaction.domain.TaskPriority;
import com.client360.interaction.domain.TaskStatus;
import com.client360.interaction.domain.TaskType;
import com.client360.interaction.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tasks (SPEC.md §7.3, TR-US-01 through TR-US-06).
 *
 * <p>No endpoint deletes a task. {@code DELETE /tasks/{id}} cancels: a commitment somebody dropped
 * is itself information, and a supervisor asking "what did we promise and not do" needs the
 * cancelled ones in the answer.
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService tasks;
    private final IdempotencyService idempotency;

    public TaskController(TaskService tasks, IdempotencyService idempotency) {
        this.tasks = tasks;
        this.idempotency = idempotency;
    }

    /** TR-US-01. {@code Idempotency-Key} required, like every create (§4.6). */
    @PostMapping("/tasks")
    // ResponseEntity<Object> because a replay returns the stored response verbatim, whatever it
    // was — the same shape InteractionController uses for the same reason (§4.6).
    public ResponseEntity<Object> create(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest body,
            CurrentUser caller) {
        return idempotency.execute(idempotencyKey, body, () -> {
            Task created = tasks.create(
                    new TaskService.NewTaskCommand(
                            body.clientId(),
                            body.sourceInteractionId(),
                            body.title(),
                            body.description(),
                            body.taskType(),
                            body.priority(),
                            body.dueAt(),
                            body.assigneeId(),
                            body.reminderOffsetMinutes(),
                            body.reminderChannels()),
                    caller);
            TaskService.TaskDetail detail = tasks.read(created.id(), caller);
            return ResponseEntity.created(java.net.URI.create("/api/v1/tasks/" + created.id()))
                    .eTag(ETags.of(created.version()))
                    .body(TaskResponse.of(detail));
        });
    }

    /**
     * TR-US-02. Default with no filter: the caller's own open tasks, soonest first.
     *
     * <p>{@code buckets} counts the whole filtered set rather than the page, so the group headers
     * the UI draws stay correct while somebody pages through them.
     */
    @GetMapping("/tasks")
    public TaskListResponse list(
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) List<TaskStatus> status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) TaskType taskType,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Instant dueFrom,
            @RequestParam(required = false) Instant dueTo,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            CurrentUser caller) {
        return TaskListResponse.of(tasks.list(
                new TaskService.TaskListQuery(
                        assigneeId,
                        clientId,
                        status,
                        priority,
                        taskType,
                        overdue,
                        dueFrom,
                        dueTo,
                        bucket,
                        page,
                        size,
                        sort),
                caller));
    }

    /** The {@code ETag} is what a later {@code PATCH} or {@code DELETE} must echo (§4.8). */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskResponse> read(@PathVariable UUID id, CurrentUser caller) {
        TaskService.TaskDetail detail = tasks.read(id, caller);
        return ResponseEntity.ok().eTag(ETags.of(detail.task().version())).body(TaskResponse.of(detail));
    }

    /** The client card's Tasks tab (§7.3) — the same filters, scoped to one client. */
    @GetMapping("/clients/{clientId}/tasks")
    public TaskListResponse ofClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) List<TaskStatus> status,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            CurrentUser caller) {
        return TaskListResponse.of(tasks.list(
                new TaskService.TaskListQuery(
                        null, clientId, status, null, null, overdue, null, null, null, page, size, sort),
                caller));
    }

    @PatchMapping("/tasks/{id}")
    public ResponseEntity<TaskResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateTaskRequest body,
            CurrentUser caller) {
        Task saved = tasks.update(
                id,
                ifMatch,
                new TaskService.TaskPatch(
                        body.title(),
                        body.description(),
                        body.priority(),
                        body.taskType(),
                        body.dueAt(),
                        body.reminderOffsetMinutes(),
                        body.reminderChannels()),
                caller);
        return ResponseEntity.ok()
                .eTag(ETags.of(saved.version()))
                .body(TaskResponse.of(tasks.read(saved.id(), caller)));
    }

    /** TR-US-03. An overdue task needs a note; an on-time one does not (TR-BR-05). */
    @PostMapping("/tasks/{id}/complete")
    public TaskResponse complete(
            @PathVariable UUID id, @RequestBody(required = false) CompleteTaskRequest body, CurrentUser caller) {
        Task saved = tasks.complete(id, body == null ? null : body.note(), caller);
        return TaskResponse.of(tasks.read(saved.id(), caller));
    }

    /** TR-US-04. Assignee only — a supervisor postponing somebody else's work is a reassignment. */
    @PostMapping("/tasks/{id}/snooze")
    public TaskResponse snooze(@PathVariable UUID id, @Valid @RequestBody SnoozeRequest body, CurrentUser caller) {
        Task saved = tasks.snooze(id, body.newDueAt(), body.reason(), caller);
        return TaskResponse.of(tasks.read(saved.id(), caller));
    }

    /** TR-US-06. {@code task:reassign} over the task's client. */
    @PostMapping("/tasks/{id}/reassign")
    public TaskResponse reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest body, CurrentUser caller) {
        Task saved = tasks.reassign(id, body.newAssigneeId(), body.reason(), caller);
        return TaskResponse.of(tasks.read(saved.id(), caller));
    }

    /** Cancels. Never deletes (§7.3). */
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestParam String reason,
            CurrentUser caller) {
        tasks.cancel(id, ifMatch, reason, caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param assigneeId omitted means the caller. §7.3: a manager may only self-assign, so handing
     *     work to a colleague needs a wider scope than {@code OWN}
     * @param reminderChannels defaults to {@code IN_APP} alone; email is opt-in per task
     */
    public record CreateTaskRequest(
            @NotNull UUID clientId,
            UUID sourceInteractionId,
            @NotBlank @Size(min = 3, max = 200) String title,
            @Size(max = 4000) String description,
            TaskType taskType,
            TaskPriority priority,
            @NotNull Instant dueAt,
            UUID assigneeId,
            Integer reminderOffsetMinutes,
            List<ReminderChannel> reminderChannels) {}

    public record UpdateTaskRequest(
            @Size(min = 3, max = 200) String title,
            @Size(max = 4000) String description,
            TaskPriority priority,
            TaskType taskType,
            Instant dueAt,
            Integer reminderOffsetMinutes,
            List<ReminderChannel> reminderChannels) {}

    /**
     * @param note required only when the task is overdue (TR-BR-05), so not {@code @NotBlank}
     *     <p>§7.3 also specifies {@code logInteraction}, which writes a linked {@code NOTE} to the
     *     client's timeline in the same transaction. It is <strong>not accepted here yet</strong>
     *     and deliberately absent rather than ignored: a field a caller can set that changes
     *     nothing is worse than one that is missing, because the caller believes the note was
     *     written. SPEC.md records it as outstanding.
     */
    public record CompleteTaskRequest(@Size(max = 2000) String note) {}

    public record SnoozeRequest(
            @NotNull Instant newDueAt,
            @NotBlank @Size(min = 10, max = 2000) String reason) {}

    public record ReassignRequest(
            @NotNull UUID newAssigneeId,
            @NotBlank @Size(min = 10, max = 2000) String reason,
            Boolean notifyAssignee) {}
}
