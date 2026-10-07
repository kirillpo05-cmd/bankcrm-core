package com.client360.interaction.service;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.ETags;
import com.client360.common.web.OffsetPage;
import com.client360.interaction.api.TaskErrorCodes;
import com.client360.interaction.api.UserSummary;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.client.ClientAccessView;
import com.client360.interaction.client.UserDirectoryClient;
import com.client360.interaction.domain.ReminderChannel;
import com.client360.interaction.domain.Task;
import com.client360.interaction.domain.TaskPriority;
import com.client360.interaction.domain.TaskStatus;
import com.client360.interaction.domain.TaskType;
import com.client360.interaction.persistence.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task / Reminder (SPEC.md §7): the follow-up work a manager owes a client, and the rules that keep
 * a commitment from quietly disappearing.
 *
 * <p>Authorization derives entirely from the client (TR-BR-01): every task has exactly one, so
 * "may this caller touch this task" is "may this caller touch this client", answered by
 * client-service through {@link ClientAccessClient}. There is no second scoping mechanism, which is
 * the whole reason {@code client_id} is {@code NOT NULL} and immutable.
 *
 * <p>Four rules are enforced here rather than trusted, and each has a reason to exist:
 *
 * <ul>
 *   <li><strong>TR-BR-04</strong>: {@code due_at} moves forward only. Pulling a deadline earlier
 *       erases the record of what was originally promised; the honest action is a new task.
 *   <li><strong>TR-BR-05</strong>: completing an <em>overdue</em> task needs a note. Friction where
 *       the information is worth having and nowhere else.
 *   <li><strong>TR-BR-06</strong>: three snoozes, each with a reason. The cap is what stops
 *       "snooze forever" from replacing "ask for help".
 *   <li><strong>TR-BR-10</strong>: a manager cannot cancel a {@code KYC_REFRESH} task, because
 *       cancelling one silently drops a compliance obligation.
 * </ul>
 */
@Service
public class TaskService {

    /** §7.3: a task dated more than two years out is a filter mistake, not a plan. */
    private static final Duration MAX_HORIZON = Duration.ofDays(730);

    /** TR-BR-06: each snooze lands at most 30 days past the original promise. */
    private static final Duration MAX_SNOOZE_BEYOND_ORIGINAL = Duration.ofDays(30);

    private static final int MIN_REASON = 10;
    private static final int MAX_NOTE = 2000;
    private static final int DEFAULT_REMINDER_OFFSET_MINUTES = 60;

    /** §7.3's {@code ?sort=} whitelist: API name to column. Anything else is {@code 400}. */
    private static final Map<String, String> SORTABLE = Map.of(
            "dueAt", "due_at",
            "priority", "priority",
            "createdAt", "created_at",
            "status", "status");

    private static final Set<String> BUCKETS = Set.of("OVERDUE", "TODAY", "THIS_WEEK", "LATER");

    private final TaskRepository tasks;
    private final ClientAccessClient clientAccess;
    private final UserDirectoryClient directory;
    private final AccessPolicy accessPolicy;
    private final TaskEvents events;
    private final DatabaseClock clock;

    public TaskService(
            TaskRepository tasks,
            ClientAccessClient clientAccess,
            UserDirectoryClient directory,
            AccessPolicy accessPolicy,
            TaskEvents events,
            DatabaseClock clock) {
        this.tasks = tasks;
        this.clientAccess = clientAccess;
        this.directory = directory;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * {@code POST /tasks} (TR-US-01).
     *
     * @throws ApiException {@code 404} when the client is absent or out of scope (ER-01 passes
     *     through from client-service unchanged); {@code 422} on a past or far-future deadline, a
     *     closed client or a deactivated assignee
     */
    @Transactional
    public Task create(NewTaskCommand command, CurrentUser caller) {
        ClientAccessView client = clientAccess.require(command.clientId(), Permissions.TASK_WRITE);
        if (client.isClosed()) {
            // A closed client has no open commitments to make; CP-BR-08's reasoning applied to §7.
            throw ApiException.businessRule("This client is closed, so no new task can be created for them.")
                    .detail("clientStatus", client.status());
        }

        Instant now = clock.now();
        Instant dueAt = command.dueAt();
        if (dueAt == null) {
            throw ApiException.validation("dueAt", "required");
        }
        if (!dueAt.isAfter(now)) {
            // A *new* task dated in the past is a mistake. An existing one whose deadline has
            // passed is the normal state of overdue work, which is why this is not a constraint.
            throw ApiException.businessRule("A new task cannot be due in the past.");
        }
        if (dueAt.isAfter(now.plus(MAX_HORIZON))) {
            throw ApiException.businessRule("A task cannot be due more than two years out.")
                    .detail("maxHorizonDays", MAX_HORIZON.toDays());
        }

        UUID assigneeId = command.assigneeId() == null ? caller.id() : command.assigneeId();
        requireAssignable(assigneeId, client, caller);

        Task created = tasks.create(new TaskRepository.NewTask(
                command.clientId(),
                command.sourceInteractionId(),
                requireTitle(command.title()),
                command.description(),
                command.taskType() == null ? TaskType.FOLLOW_UP : command.taskType(),
                command.priority() == null ? TaskPriority.MEDIUM : command.priority(),
                dueAt,
                assigneeId,
                caller.id()));

        scheduleReminders(created, command.reminderOffsetMinutes(), command.reminderChannels(), now);
        tasks.appendHistory(
                created.id(), caller.id(), "CREATED", null, created.status().name(), null);
        events.created(created);
        return created;
    }

    // -------------------------------------------------------------------- read

    /** {@code GET /tasks/{id}} — {@code 404 TASK_NOT_FOUND} absent or out of scope. */
    @Transactional(readOnly = true)
    public TaskDetail read(UUID id, CurrentUser caller) {
        Task task = requireReadable(id);
        return new TaskDetail(
                task,
                tasks.remindersOf(id),
                tasks.historyOf(id),
                // Arrays.asList, not List.of: completedBy is null on a task nobody has
                // finished, and List.of refuses nulls. names() filters them.
                names(java.util.Arrays.asList(task.assigneeId(), task.createdBy(), task.completedBy())),
                clock.now());
    }

    /**
     * {@code GET /tasks} (TR-US-02).
     *
     * <p>Scope is applied by asking client-service which clients the caller may see — except at
     * {@code OWN}, where the honest reading is "tasks assigned to me", and that needs no client
     * lookup at all. A manager's own tasks are their own clients' by construction (CP-BR-03).
     */
    @Transactional(readOnly = true)
    public TaskPage list(TaskListQuery query, CurrentUser caller) {
        Scope scope = accessPolicy
                .scopeOf(caller, Permissions.TASK_READ)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.PERMISSION_DENIED, "This action requires the task:read permission.")
                        .detail("permission", Permissions.TASK_READ));

        int page = query.page() == null ? 0 : query.page();
        int size = query.size() == null ? OffsetPage.DEFAULT_SIZE : query.size();
        if (page < 0) {
            throw ApiException.validation("page", "must be zero or greater");
        }
        if (size < 1 || size > OffsetPage.MAX_SIZE) {
            throw ApiException.validation("size", "must be between 1 and " + OffsetPage.MAX_SIZE);
        }
        String sortKey = query.sort() == null || query.sort().isBlank() ? "dueAt" : query.sort();
        String column = SORTABLE.get(sortKey);
        if (column == null) {
            throw ApiException.validation("sort", "must be one of " + SORTABLE.keySet());
        }
        if (query.bucket() != null && !BUCKETS.contains(query.bucket())) {
            throw ApiException.validation("bucket", "must be one of " + BUCKETS);
        }
        if (query.dueFrom() != null && query.dueTo() != null && query.dueFrom().isAfter(query.dueTo())) {
            throw ApiException.validation("dueFrom", "must not be after dueTo");
        }

        UUID assigneeId = query.assigneeId();
        if (scope == Scope.OWN) {
            if (assigneeId != null && !assigneeId.equals(caller.id())) {
                // Naming a colleague is not a narrower request, it is a different one.
                throw ApiException.forbidden(
                                ErrorCodes.SCOPE_VIOLATION, "Reading another user's tasks requires a wider scope.")
                        .detail("permission", Permissions.TASK_READ, "scope", scope);
            }
            assigneeId = caller.id();
        }
        if (query.clientId() != null) {
            // One client's tasks is one client's authorization question, and ER-01 shapes the
            // answer: a client outside the caller's scope is 404 here as everywhere else.
            clientAccess.require(query.clientId(), Permissions.TASK_READ);
        }

        // §7.3: the default is the caller's own open tasks, soonest first.
        List<TaskStatus> statuses = query.statuses() == null || query.statuses().isEmpty()
                ? (query.bucket() == null && query.overdue() == null
                        ? List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS)
                        : List.of())
                : query.statuses();

        TaskRepository.TaskSearch criteria = new TaskRepository.TaskSearch(
                assigneeId,
                query.clientId(),
                statuses,
                query.priority(),
                query.taskType(),
                query.overdue(),
                query.dueFrom(),
                query.dueTo(),
                query.bucket(),
                null);

        long total = tasks.countSearch(criteria);
        List<Task> content = tasks.search(criteria, column, !"priority".equals(sortKey), size, page * size);
        // §7.3: buckets count the whole filtered set, not the page, so the UI's group headers stay
        // correct while paging.
        Map<String, Long> buckets = tasks.bucketCounts(criteria);

        List<UUID> people = new ArrayList<>();
        content.forEach(task -> {
            people.add(task.assigneeId());
            people.add(task.createdBy());
        });
        int totalPages = (int) Math.ceil((double) total / size);
        return new TaskPage(
                content,
                buckets,
                names(people),
                clock.now(),
                page,
                size,
                total,
                totalPages,
                (long) (page + 1) * size < total);
    }

    // ------------------------------------------------------------------- edit

    /**
     * {@code PATCH /tasks/{id}} with {@code If-Match} (§4.8).
     *
     * @throws ApiException {@code 403} when the caller is neither assignee, creator nor a
     *     supervising supervisor; {@code 422} on a terminal task or a backwards deadline
     */
    @Transactional
    public Task update(UUID id, String ifMatch, TaskPatch patch, CurrentUser caller) {
        int version = ETags.requireIfMatch(ifMatch);
        Task current = requireWritable(id, caller);
        if (current.status().isTerminal()) {
            throw ApiException.businessRule("A " + current.status() + " task cannot be edited.")
                    .detail("status", current.status());
        }
        if (patch.dueAt() != null && patch.dueAt().isBefore(current.originalDueAt())) {
            // TR-BR-04. ck_tasks_due_forward says the same thing; saying it here gives the caller a
            // field name and the reason, which a constraint violation cannot.
            throw ApiException.businessRule("A deadline may only move forward. Create a new, more urgent task instead.")
                    .detail("field", "dueAt", "originalDueAt", current.originalDueAt());
        }
        if (patch.title() != null) {
            requireTitle(patch.title());
        }

        Task saved = tasks.update(
                        id,
                        version,
                        patch.title(),
                        patch.description(),
                        patch.priority(),
                        patch.taskType(),
                        patch.dueAt(),
                        caller.id())
                .orElseThrow(() -> ApiException.conflict(
                                ErrorCodes.VERSION_CONFLICT,
                                "This task was changed by someone else. Re-read and retry.")
                        .detail("currentVersion", current.version()));

        if (patch.dueAt() != null && !patch.dueAt().equals(current.dueAt())) {
            // TR-BR-07: moving the deadline cancels what was pending and reschedules from the new
            // one. A reminder for a date that no longer exists is worse than no reminder.
            rescheduleReminders(saved, patch.reminderOffsetMinutes(), patch.reminderChannels());
            tasks.appendHistory(
                    id,
                    caller.id(),
                    "DUE_CHANGED",
                    current.dueAt().toString(),
                    saved.dueAt().toString(),
                    null);
        }
        events.updated(current, saved);
        return saved;
    }

    // --------------------------------------------------------------- complete

    /**
     * {@code POST /tasks/{id}/complete} (TR-US-03, TR-BR-05).
     *
     * @throws ApiException {@code 409} when already terminal; {@code 422} when an overdue task is
     *     completed with no note
     */
    @Transactional
    public Task complete(UUID id, String note, CurrentUser caller) {
        Task current = requireCompletable(id, caller);
        if (current.status().isTerminal()) {
            throw ApiException.conflict(
                            ErrorCodes.ILLEGAL_STATE_TRANSITION, "This task is already " + current.status() + ".")
                    .detail("status", current.status());
        }
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > MAX_NOTE) {
            throw ApiException.validation("note", "must be at most " + MAX_NOTE + " characters");
        }
        if (current.isOverdue(clock.now()) && (trimmed == null || trimmed.isBlank())) {
            // TR-BR-05. The friction is deliberate and narrow: the note is only required where the
            // information is actually valuable, which is when the commitment was missed.
            throw ApiException.businessRule("Completing an overdue task requires a note.")
                    .detail("field", "note", "reason", "OVERDUE_COMPLETION_NEEDS_NOTE");
        }

        if (!tasks.transition(id, current.status(), TaskStatus.DONE, caller.id(), trimmed)) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "This task changed before it completed.");
        }
        // A completed task stops the escalation ladder (TR-BR-08), and its pending reminders are
        // about a deadline nobody owes any more.
        tasks.cancelScheduledReminders(id);
        Task saved = tasks.findById(id).orElseThrow();
        tasks.appendHistory(id, caller.id(), "STATUS_CHANGED", current.status().name(), "DONE", trimmed);
        events.completed(current, saved);
        return saved;
    }

    // ----------------------------------------------------------------- snooze

    /**
     * {@code POST /tasks/{id}/snooze} (TR-US-04, TR-BR-06).
     *
     * <p>Assignee only, and that is the point: a supervisor postponing someone else's work is a
     * reassignment or a due-date edit, not a snooze. Those are different actions with different
     * records, and conflating them would hide a supervisor's decision inside a manager's.
     */
    @Transactional
    public Task snooze(UUID id, Instant newDueAt, String rawReason, CurrentUser caller) {
        Task current = requireReadable(id);
        if (!current.assigneeId().equals(caller.id())) {
            throw ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, "Only the assignee may snooze a task.")
                    .detail("reason", "ASSIGNEE_ONLY");
        }
        if (!current.status().isLive()) {
            throw ApiException.conflict(
                    ErrorCodes.ILLEGAL_STATE_TRANSITION, "A " + current.status() + " task cannot be snoozed.");
        }
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        if (current.snoozeCount() >= Task.SNOOZE_CAP) {
            // §7.3 gives this its own code: the UI's answer is "escalate to a supervisor", which is
            // a different prompt from a generic rule violation.
            throw ApiException.unprocessable(
                            TaskErrorCodes.TASK_SNOOZE_LIMIT_REACHED,
                            "This task has been snoozed the maximum number of times. Ask a supervisor for help.")
                    .detail("snoozeCount", current.snoozeCount(), "cap", Task.SNOOZE_CAP);
        }
        if (newDueAt == null || !newDueAt.isAfter(clock.now())) {
            throw ApiException.businessRule("A snooze must move the deadline into the future.");
        }
        if (newDueAt.isAfter(current.originalDueAt().plus(MAX_SNOOZE_BEYOND_ORIGINAL))) {
            throw ApiException.businessRule("A snooze cannot move a deadline more than 30 days past the original.")
                    .detail("originalDueAt", current.originalDueAt(), "maxDays", MAX_SNOOZE_BEYOND_ORIGINAL.toDays());
        }

        Task saved = tasks.snooze(id, newDueAt, reason, current.version())
                .orElseThrow(() -> ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT, "This task was changed by someone else. Re-read and retry."));
        rescheduleReminders(saved, null, null);
        tasks.appendHistory(
                id,
                caller.id(),
                "SNOOZED",
                current.dueAt().toString(),
                saved.dueAt().toString(),
                reason);
        events.snoozed(current, saved, reason);
        return saved;
    }

    // --------------------------------------------------------------- reassign

    /** {@code POST /tasks/{id}/reassign} (TR-US-06). {@code task:reassign} over the task's client. */
    @Transactional
    public Task reassign(UUID id, UUID newAssigneeId, String rawReason, CurrentUser caller) {
        Task current = requireReadable(id);
        ClientAccessView client = clientAccess.require(current.clientId(), Permissions.TASK_REASSIGN);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        if (current.status().isTerminal()) {
            throw ApiException.businessRule("A " + current.status() + " task cannot be reassigned.");
        }
        if (newAssigneeId == null || newAssigneeId.equals(current.assigneeId())) {
            throw ApiException.businessRule("The new assignee is already the current one.");
        }
        requireAssignable(newAssigneeId, client, caller);

        Task saved = tasks.reassign(id, newAssigneeId, current.version())
                .orElseThrow(() -> ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT, "This task was changed by someone else. Re-read and retry."));
        tasks.appendHistory(
                id, caller.id(), "REASSIGNED", current.assigneeId().toString(), newAssigneeId.toString(), reason);
        events.reassigned(current, saved, reason, null);
        return saved;
    }

    /**
     * TR-BR-11: a client changed hands, so its live tasks follow, in the caller's transaction.
     *
     * <p>Called by the {@code client.reassigned} consumer. One event per task with a shared
     * correlation id, never one for the batch (AT-EC-11) — six tasks moving because a client moved
     * is one decision, and a consumer needs to be able to see that.
     */
    @Transactional
    public int transferLiveTasks(UUID clientId, UUID newAssigneeId, UUID actorId, UUID correlationId, String reason) {
        List<Task> before = tasks.search(
                new TaskRepository.TaskSearch(
                        null,
                        clientId,
                        List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                "due_at",
                true,
                OffsetPage.MAX_SIZE,
                0);
        List<Task> moved = tasks.reassignLiveTasksOfClient(clientId, newAssigneeId);
        Map<UUID, Task> previous =
                before.stream().collect(java.util.stream.Collectors.toMap(Task::id, task -> task, (a, b) -> a));
        for (Task task : moved) {
            Task was = previous.get(task.id());
            tasks.appendHistory(
                    task.id(),
                    actorId,
                    "REASSIGNED",
                    was == null ? null : was.assigneeId().toString(),
                    newAssigneeId.toString(),
                    reason);
            events.reassigned(was == null ? task : was, task, reason, correlationId);
        }
        return moved.size();
    }

    // ----------------------------------------------------------------- cancel

    /**
     * {@code DELETE /tasks/{id}} (§7.3). Sets {@code CANCELLED}; tasks are never hard-deleted,
     * because a cancelled commitment is itself information.
     *
     * @throws ApiException {@code 422} when already {@code DONE}, or when a manager tries to cancel
     *     a {@code KYC_REFRESH} task (TR-BR-10)
     */
    @Transactional
    public void cancel(UUID id, String ifMatch, String rawReason, CurrentUser caller) {
        int version = ETags.requireIfMatch(ifMatch);
        Task current = requireWritable(id, caller);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        if (current.status() == TaskStatus.DONE) {
            throw ApiException.businessRule("A completed task cannot be cancelled.")
                    .detail("status", "DONE");
        }
        if (current.status() == TaskStatus.CANCELLED) {
            // Idempotent: the state the caller asked for is the state it is in.
            return;
        }
        if (current.taskType() == TaskType.KYC_REFRESH && !holdsWiderThanOwn(caller, Permissions.TASK_WRITE)) {
            // TR-BR-10. Cancelling one silently drops a compliance obligation, so it takes someone
            // who answers for the team rather than the person the obligation is inconvenient for.
            throw ApiException.businessRule("A KYC refresh task can only be cancelled by a supervisor.")
                    .detail("reason", "KYC_REFRESH_SUPERVISOR_ONLY", "taskType", current.taskType());
        }
        if (current.version() != version) {
            throw ApiException.conflict(
                            ErrorCodes.VERSION_CONFLICT, "This task was changed by someone else. Re-read and retry.")
                    .detail("currentVersion", current.version());
        }

        if (!tasks.transition(id, current.status(), TaskStatus.CANCELLED, caller.id(), reason)) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "This task changed before it cancelled.");
        }
        tasks.cancelScheduledReminders(id);
        Task saved = tasks.findById(id).orElseThrow();
        tasks.appendHistory(id, caller.id(), "STATUS_CHANGED", current.status().name(), "CANCELLED", reason);
        events.cancelled(current, saved, reason);
    }

    // ---------------------------------------------------------------- helpers

    /** Readable means the caller may see the task's client — TR-BR-01 and nothing else. */
    private Task requireReadable(UUID id) {
        Task task = tasks.findById(id).orElseThrow(TaskService::notFound);
        // ER-01 passes through: a client out of scope is 404 there and 404 here, so a task id
        // cannot be used to probe which clients exist.
        clientAccess.require(task.clientId(), Permissions.TASK_READ);
        return task;
    }

    /**
     * §7.3: assignee, creator, or a supervisor over the assignee.
     *
     * <p>"A supervisor over the assignee" is read as {@code task:write} at a scope wider than
     * {@code OWN} covering this client — which is what client-service already answered. Asking who
     * supervises whom would need a second mechanism, and TR-BR-01 exists to avoid exactly that.
     */
    private Task requireWritable(UUID id, CurrentUser caller) {
        Task task = tasks.findById(id).orElseThrow(TaskService::notFound);
        clientAccess.require(task.clientId(), Permissions.TASK_WRITE);
        boolean ownWork =
                task.assigneeId().equals(caller.id()) || task.createdBy().equals(caller.id());
        if (!ownWork && !holdsWiderThanOwn(caller, Permissions.TASK_WRITE)) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Only the assignee, the creator or a supervisor may change this task.")
                    .detail("permission", Permissions.TASK_WRITE);
        }
        return task;
    }

    /** §7.3: the assignee, or a supervisor over them. The response records who actually did it. */
    private Task requireCompletable(UUID id, CurrentUser caller) {
        Task task = tasks.findById(id).orElseThrow(TaskService::notFound);
        clientAccess.require(task.clientId(), Permissions.TASK_WRITE);
        if (!task.assigneeId().equals(caller.id()) && !holdsWiderThanOwn(caller, Permissions.TASK_WRITE)) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED, "Only the assignee or a supervisor may complete this task.")
                    .detail("permission", Permissions.TASK_WRITE);
        }
        return task;
    }

    private boolean holdsWiderThanOwn(CurrentUser caller, String permission) {
        return accessPolicy
                .scopeOf(caller, permission)
                .filter(scope -> scope != Scope.OWN)
                .isPresent();
    }

    /**
     * §7.3: "managers may only self-assign".
     *
     * <p>A manager holds {@code task:write} at {@code OWN}, so handing work to a colleague needs a
     * wider scope — which is the same sentence read through permissions rather than through roles
     * (rule 5). The assignee must also be able to hold the work: an inactive user cannot.
     */
    private void requireAssignable(UUID assigneeId, ClientAccessView client, CurrentUser caller) {
        if (!assigneeId.equals(caller.id()) && !holdsWiderThanOwn(caller, Permissions.TASK_WRITE)) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED, "Assigning work to another user requires a wider scope.")
                    .detail("permission", Permissions.TASK_WRITE, "assigneeId", assigneeId);
        }
        UserSummary assignee = directory.byId(assigneeId);
        if (assignee.fullName() == null) {
            // The directory resolves a name for every live user, so an unresolved id is either
            // unknown or a directory outage — and assigning work to a user that may not exist is
            // not something to do on a guess.
            throw ApiException.notFound("USER_NOT_FOUND", "Assignee not found.").detail("assigneeId", assigneeId);
        }
    }

    /** TR-BR-07: one reminder per requested channel, at {@code due_at} minus the offset. */
    private void scheduleReminders(Task task, Integer offsetMinutes, List<ReminderChannel> channels, Instant now) {
        int offset = offsetMinutes == null ? DEFAULT_REMINDER_OFFSET_MINUTES : offsetMinutes;
        if (offset < 0) {
            throw ApiException.validation("reminderOffsetMinutes", "must be zero or greater");
        }
        List<ReminderChannel> requested =
                channels == null || channels.isEmpty() ? List.of(ReminderChannel.IN_APP) : channels;
        Instant remindAt = task.dueAt().minus(Duration.ofMinutes(offset));
        boolean alreadyPast = remindAt.isBefore(now);
        for (ReminderChannel channel : requested) {
            tasks.scheduleReminder(task.id(), remindAt, channel, alreadyPast);
        }
    }

    private void rescheduleReminders(Task task, Integer offsetMinutes, List<ReminderChannel> channels) {
        List<ReminderChannel> existing = channels == null || channels.isEmpty()
                ? tasks.remindersOf(task.id()).stream()
                        .map(TaskRepository.Reminder::channel)
                        .distinct()
                        .toList()
                : channels;
        tasks.cancelScheduledReminders(task.id());
        scheduleReminders(task, offsetMinutes, existing, clock.now());
    }

    private Map<UUID, UserSummary> names(List<UUID> ids) {
        List<UUID> present =
                ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return present.isEmpty() ? Map.of() : directory.byIds(present);
    }

    private static String requireTitle(String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.length() < 3) {
            throw ApiException.validation("title", "must be at least 3 characters");
        }
        if (trimmed.length() > 200) {
            throw ApiException.validation("title", "must be at most 200 characters");
        }
        return trimmed;
    }

    private static ApiException notFound() {
        return ApiException.notFound(TaskErrorCodes.TASK_NOT_FOUND, "Task not found or not in your scope.");
    }

    /** §7.3's create body, already parsed. */
    public record NewTaskCommand(
            UUID clientId,
            UUID sourceInteractionId,
            String title,
            String description,
            TaskType taskType,
            TaskPriority priority,
            Instant dueAt,
            UUID assigneeId,
            Integer reminderOffsetMinutes,
            List<ReminderChannel> reminderChannels) {}

    /**
     * §7.3's query string, already parsed.
     *
     * @param overdue {@code null} means "do not filter on it", which is a third state distinct
     *     from true and false — {@code ?overdue=false} is a request for work that is not yet late
     */
    public record TaskListQuery(
            UUID assigneeId,
            UUID clientId,
            List<TaskStatus> statuses,
            TaskPriority priority,
            TaskType taskType,
            Boolean overdue,
            Instant dueFrom,
            Instant dueTo,
            String bucket,
            Integer page,
            Integer size,
            String sort) {}

    /** A merge-patch: null means "leave alone" (§4.6). */
    public record TaskPatch(
            String title,
            String description,
            TaskPriority priority,
            TaskType taskType,
            Instant dueAt,
            Integer reminderOffsetMinutes,
            List<ReminderChannel> reminderChannels) {}

    public record TaskDetail(
            Task task,
            List<TaskRepository.Reminder> reminders,
            List<TaskRepository.HistoryEntry> history,
            Map<UUID, UserSummary> people,
            Instant now) {}

    /** @param now the one clock every {@code overdue} in this page was computed against (rule 7) */
    public record TaskPage(
            List<Task> content,
            Map<String, Long> buckets,
            Map<UUID, UserSummary> people,
            Instant now,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext) {}
}
