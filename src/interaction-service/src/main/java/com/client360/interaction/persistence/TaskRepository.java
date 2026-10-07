package com.client360.interaction.persistence;

import com.client360.interaction.domain.ReminderChannel;
import com.client360.interaction.domain.ReminderStatus;
import com.client360.interaction.domain.Task;
import com.client360.interaction.domain.TaskPriority;
import com.client360.interaction.domain.TaskStatus;
import com.client360.interaction.domain.TaskType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code interaction.tasks}, {@code task_reminders} and {@code task_history} (SPEC.md §7.2).
 *
 * <p>Overdue never appears as a column here, in a filter or in a result: every query computes it
 * from {@code due_at} and {@code status} against {@code now()} (TR-BR-02). The partial indexes are
 * what make that cheap, and the predicates below are written to match them exactly — a filter that
 * said {@code status <> 'DONE'} instead of {@code status IN ('OPEN','IN_PROGRESS')} would be
 * logically close and would miss the index.
 */
@Repository
public class TaskRepository {

    private static final String COLUMNS = """
            id, client_id, source_interaction_id, title, description, task_type::text AS task_type,
            priority::text AS priority, status::text AS status, due_at, original_due_at,
            assignee_id, created_by, snooze_count, last_snoozed_at, last_snooze_reason,
            completed_at, completed_by, completion_note, cancelled_at, cancelled_by,
            cancellation_reason, escalation_level, escalated_at, version, created_at, updated_at""";

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Task> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE id = :id")
                .param("id", id)
                .query(TaskRepository::task)
                .optional();
    }

    public Task create(NewTask draft) {
        return jdbc.sql("""
                        INSERT INTO tasks
                            (client_id, source_interaction_id, title, description, task_type, priority,
                             due_at, original_due_at, assignee_id, created_by)
                        VALUES (:clientId, :sourceInteractionId, :title, :description,
                                CAST(:taskType AS task_type), CAST(:priority AS task_priority),
                                :dueAt, :dueAt, :assigneeId, :createdBy)
                        RETURNING """ + " " + COLUMNS)
                .param("clientId", draft.clientId())
                .param("sourceInteractionId", draft.sourceInteractionId())
                .param("title", draft.title())
                .param("description", draft.description())
                .param("taskType", draft.taskType().name())
                .param("priority", draft.priority().name())
                // One parameter for both columns: original_due_at is due_at at creation by
                // definition, and binding them separately would allow a caller to disagree.
                .param("dueAt", timestamp(draft.dueAt()))
                .param("assigneeId", draft.assigneeId())
                .param("createdBy", draft.createdBy())
                .query(TaskRepository::task)
                .single();
    }

    /**
     * {@code PATCH /tasks/{id}} with {@code If-Match} (§4.8). Nulls mean "leave alone".
     *
     * @return empty when the version did not match, which the caller turns into {@code 409}
     */
    public Optional<Task> update(
            UUID id,
            int expectedVersion,
            String title,
            String description,
            TaskPriority priority,
            TaskType taskType,
            Instant dueAt,
            UUID actorId) {
        return jdbc.sql("""
                        UPDATE tasks
                           SET title = coalesce(:title, title),
                               description = coalesce(:description, description),
                               priority = coalesce(CAST(:priority AS task_priority), priority),
                               task_type = coalesce(CAST(:taskType AS task_type), task_type),
                               due_at = coalesce(:dueAt, due_at),
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("title", title)
                .param("description", description)
                .param("priority", priority == null ? null : priority.name())
                .param("taskType", taskType == null ? null : taskType.name())
                .param("dueAt", timestamp(dueAt))
                .query(TaskRepository::task)
                .optional();
    }

    /**
     * TR-BR-03's transition, applied as one conditional statement.
     *
     * <p>The {@code WHERE} names the status the caller believed the task was in, so two people
     * completing the same task produce one winner and the loser is told the state changed rather
     * than silently overwriting a completion note.
     */
    public boolean transition(UUID id, TaskStatus from, TaskStatus to, UUID actorId, String note) {
        String sets =
                switch (to) {
                    case DONE ->
                        "status = 'DONE', completed_at = now(), completed_by = :actor,"
                                + " completion_note = :note, cancelled_at = NULL, cancelled_by = NULL,"
                                + " cancellation_reason = NULL";
                    case CANCELLED ->
                        "status = 'CANCELLED', cancelled_at = now(), cancelled_by = :actor,"
                                + " cancellation_reason = :note, completed_at = NULL, completed_by = NULL";
                    // Reopening clears the completion fields; ck_tasks_done_fields requires it, and a
                    // reopened task that kept its completion note would read as finished in every list.
                    case OPEN, IN_PROGRESS ->
                        "status = CAST(:to AS task_status), completed_at = NULL,"
                                + " completed_by = NULL, completion_note = NULL, cancelled_at = NULL,"
                                + " cancelled_by = NULL, cancellation_reason = NULL";
                };
        return jdbc.sql("UPDATE tasks SET " + sets + ", updated_at = now(), version = version + 1"
                                + " WHERE id = :id AND status = CAST(:from AS task_status)")
                        .param("id", id)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("actor", actorId)
                        .param("note", note)
                        .update()
                == 1;
    }

    /**
     * TR-BR-06. The cap is checked in the statement as well as in the service: a concurrent fourth
     * snooze would otherwise read three, both pass the service check, and one of them would land —
     * which {@code ck_tasks_snooze_cap} would then refuse with an error nobody can act on.
     */
    public Optional<Task> snooze(UUID id, Instant newDueAt, String reason, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE tasks
                           SET due_at = :newDueAt,
                               snooze_count = snooze_count + 1,
                               last_snoozed_at = now(),
                               last_snooze_reason = :reason,
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id
                           AND version = :expectedVersion
                           AND snooze_count < 3
                           AND status IN ('OPEN', 'IN_PROGRESS')
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("newDueAt", timestamp(newDueAt))
                .param("reason", reason)
                .param("expectedVersion", expectedVersion)
                .query(TaskRepository::task)
                .optional();
    }

    public Optional<Task> reassign(UUID id, UUID newAssigneeId, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE tasks
                           SET assignee_id = :assignee, updated_at = now(), version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                           AND status IN ('OPEN', 'IN_PROGRESS')
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("assignee", newAssigneeId)
                .param("expectedVersion", expectedVersion)
                .query(TaskRepository::task)
                .optional();
    }

    /**
     * TR-BR-11: a client changing hands moves its live tasks with it, in the caller's transaction.
     *
     * <p>{@code DONE} and {@code CANCELLED} tasks keep their original assignee — history must not be
     * rewritten, and "who completed this" is the question the record exists to answer.
     *
     * @return the tasks that moved, so the caller can append a history row and emit an event per
     *     task rather than one for the batch (AT-EC-11)
     */
    public List<Task> reassignLiveTasksOfClient(UUID clientId, UUID newAssigneeId) {
        return jdbc.sql("""
                        UPDATE tasks
                           SET assignee_id = :assignee, updated_at = now(), version = version + 1
                         WHERE client_id = :clientId
                           AND status IN ('OPEN', 'IN_PROGRESS')
                           AND assignee_id <> :assignee
                        RETURNING """ + " " + COLUMNS)
                .param("clientId", clientId)
                .param("assignee", newAssigneeId)
                .query(TaskRepository::task)
                .list();
    }

    // ------------------------------------------------------------------ search

    /**
     * {@code GET /tasks} (TR-US-02). Every filter in one clause, nulls expressed in SQL rather than
     * by assembling a different statement — one plan, and nothing built from what was sent.
     */
    public List<Task> search(TaskSearch criteria, String sortColumn, boolean ascending, int limit, int offset) {
        return bind(
                        jdbc.sql("SELECT " + COLUMNS + " FROM tasks " + WHERE
                                + " ORDER BY " + sortColumn + (ascending ? " ASC" : " DESC") + ", id ASC"
                                + " LIMIT :limit OFFSET :offset"),
                        criteria)
                .param("limit", limit)
                .param("offset", offset)
                .query(TaskRepository::task)
                .list();
    }

    public long countSearch(TaskSearch criteria) {
        return bind(jdbc.sql("SELECT count(*) FROM tasks " + WHERE), criteria)
                .query(Long.class)
                .single();
    }

    /**
     * §7.3: {@code buckets} counts the whole filtered set, not the current page, so the UI's group
     * headers stay correct while paging.
     *
     * <p>One pass with a {@code CASE}, not four queries: the four buckets partition the same filtered
     * set, and four round trips could each see a different {@code now()}.
     */
    public Map<String, Long> bucketCounts(TaskSearch criteria) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("OVERDUE", 0L);
        counts.put("TODAY", 0L);
        counts.put("THIS_WEEK", 0L);
        counts.put("LATER", 0L);
        bind(
                        jdbc.sql("SELECT " + BUCKET_EXPRESSION + " AS bucket, count(*) AS total FROM tasks " + WHERE
                                + " GROUP BY 1"),
                        criteria)
                .query((rs, n) -> Map.entry(rs.getString("bucket"), rs.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    /**
     * The four buckets of "My Day" (TR-US-02), in the database so they agree with the filter.
     *
     * <p>Live work only: a completed task is in no bucket, because the screen is a list of what is
     * still owed. {@code THIS_WEEK} is the next seven days rather than the calendar week — on a
     * Friday the calendar reading would show almost nothing and bury the weekend's work in LATER.
     */
    private static final String BUCKET_EXPRESSION = """
            CASE
                WHEN status NOT IN ('OPEN','IN_PROGRESS')            THEN 'DONE'
                WHEN due_at < now()                                  THEN 'OVERDUE'
                WHEN due_at < date_trunc('day', now()) + INTERVAL '1 day' THEN 'TODAY'
                WHEN due_at < now() + INTERVAL '7 days'              THEN 'THIS_WEEK'
                ELSE 'LATER'
            END""";

    private static final String WHERE = """
            WHERE (CAST(:assigneeId AS uuid) IS NULL OR assignee_id = CAST(:assigneeId AS uuid))
              AND (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))
              AND (CAST(:scopeClientIds AS uuid[]) IS NULL
                   OR client_id = ANY (CAST(:scopeClientIds AS uuid[])))
              AND (CAST(:statuses AS text[]) IS NULL OR status::text = ANY (CAST(:statuses AS text[])))
              AND (CAST(:priority AS text) IS NULL OR priority::text = CAST(:priority AS text))
              AND (CAST(:taskType AS text) IS NULL OR task_type::text = CAST(:taskType AS text))
              AND (CAST(:dueFrom AS timestamptz) IS NULL OR due_at >= CAST(:dueFrom AS timestamptz))
              AND (CAST(:dueTo AS timestamptz) IS NULL OR due_at < CAST(:dueTo AS timestamptz))
              -- TR-BR-02 as a filter: computed, and written to match ix_tasks_open_due exactly.
              AND (CAST(:overdue AS boolean) IS NULL
                   OR (CAST(:overdue AS boolean)
                       = (due_at < now() AND status IN ('OPEN','IN_PROGRESS'))))
              AND (CAST(:bucket AS text) IS NULL OR """
            // The separator is explicit: a text block strips incidental trailing
            // whitespace, so the "OR " above arrives as "OR" and runs into CASE.
            + " " + BUCKET_EXPRESSION + " = CAST(:bucket AS text))\n";

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, TaskSearch criteria) {
        return spec.param("assigneeId", criteria.assigneeId())
                .param("clientId", criteria.clientId())
                .param(
                        "scopeClientIds",
                        criteria.scopeClientIds() == null
                                        || criteria.scopeClientIds().isEmpty()
                                ? null
                                : criteria.scopeClientIds().toArray(UUID[]::new))
                .param(
                        "statuses",
                        criteria.statuses() == null || criteria.statuses().isEmpty()
                                ? null
                                : criteria.statuses().stream().map(Enum::name).toArray(String[]::new))
                .param(
                        "priority",
                        criteria.priority() == null ? null : criteria.priority().name())
                .param(
                        "taskType",
                        criteria.taskType() == null ? null : criteria.taskType().name())
                .param("dueFrom", timestamp(criteria.dueFrom()))
                .param("dueTo", timestamp(criteria.dueTo()))
                .param("overdue", criteria.overdue())
                .param("bucket", criteria.bucket());
    }

    // --------------------------------------------------------------- reminders

    /**
     * TR-BR-07. A reminder whose computed time has already passed is created {@code CANCELLED}
     * rather than fired: a notification about a deadline the user has already missed is noise, and
     * the row still records that one was asked for.
     */
    public void scheduleReminder(UUID taskId, Instant remindAt, ReminderChannel channel, boolean inThePast) {
        jdbc.sql("""
                        INSERT INTO task_reminders (task_id, remind_at, channel, status, last_error)
                        VALUES (:taskId, :remindAt, CAST(:channel AS reminder_channel),
                                CAST(:status AS reminder_status), :lastError)
                        ON CONFLICT (task_id, remind_at, channel) DO NOTHING
                        """)
                .param("taskId", taskId)
                .param("remindAt", timestamp(remindAt))
                .param("channel", channel.name())
                .param("status", inThePast ? ReminderStatus.CANCELLED.name() : ReminderStatus.SCHEDULED.name())
                .param("lastError", inThePast ? "PAST_DUE_AT_CREATION" : null)
                .update();
    }

    /** TR-BR-07: editing {@code due_at} or snoozing cancels what was pending and reschedules. */
    public int cancelScheduledReminders(UUID taskId) {
        return jdbc.sql("""
                        UPDATE task_reminders
                           SET status = 'CANCELLED', last_error = 'RESCHEDULED'
                         WHERE task_id = :taskId AND status = 'SCHEDULED'
                        """).param("taskId", taskId).update();
    }

    public List<Reminder> remindersOf(UUID taskId) {
        return jdbc.sql("""
                        SELECT remind_at, channel::text AS channel, status::text AS status, sent_at
                          FROM task_reminders WHERE task_id = :taskId
                         ORDER BY remind_at, channel
                        """)
                .param("taskId", taskId)
                .query((rs, n) -> new Reminder(
                        instant(rs.getObject("remind_at", OffsetDateTime.class)),
                        ReminderChannel.valueOf(rs.getString("channel")),
                        ReminderStatus.valueOf(rs.getString("status")),
                        instant(rs.getObject("sent_at", OffsetDateTime.class))))
                .list();
    }

    // ----------------------------------------------------------------- history

    /** §7.2.4: a product feature, written in the same transaction as the change it records. */
    public void appendHistory(
            UUID taskId, UUID changedBy, String changeType, String fromValue, String toValue, String reason) {
        jdbc.sql("""
                        INSERT INTO task_history (task_id, changed_by, change_type, from_value, to_value, reason)
                        VALUES (:taskId, :changedBy, :changeType, :fromValue, :toValue, :reason)
                        """)
                .param("taskId", taskId)
                .param("changedBy", changedBy)
                .param("changeType", changeType)
                .param("fromValue", fromValue)
                .param("toValue", toValue)
                .param("reason", reason)
                .update();
    }

    public List<HistoryEntry> historyOf(UUID taskId) {
        return jdbc.sql("""
                        SELECT changed_at, changed_by, change_type, from_value, to_value, reason
                          FROM task_history WHERE task_id = :taskId
                         ORDER BY changed_at DESC, id DESC
                        """)
                .param("taskId", taskId)
                .query((rs, n) -> new HistoryEntry(
                        instant(rs.getObject("changed_at", OffsetDateTime.class)),
                        rs.getObject("changed_by", UUID.class),
                        rs.getString("change_type"),
                        rs.getString("from_value"),
                        rs.getString("to_value"),
                        rs.getString("reason")))
                .list();
    }

    // ----------------------------------------------------------------- mapping

    private static Task task(ResultSet rs, int rowNum) throws SQLException {
        return new Task(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("source_interaction_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                TaskType.valueOf(rs.getString("task_type")),
                TaskPriority.valueOf(rs.getString("priority")),
                TaskStatus.valueOf(rs.getString("status")),
                instant(rs.getObject("due_at", OffsetDateTime.class)),
                instant(rs.getObject("original_due_at", OffsetDateTime.class)),
                rs.getObject("assignee_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getInt("snooze_count"),
                instant(rs.getObject("last_snoozed_at", OffsetDateTime.class)),
                rs.getString("last_snooze_reason"),
                instant(rs.getObject("completed_at", OffsetDateTime.class)),
                rs.getObject("completed_by", UUID.class),
                rs.getString("completion_note"),
                instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
                rs.getObject("cancelled_by", UUID.class),
                rs.getString("cancellation_reason"),
                rs.getInt("escalation_level"),
                instant(rs.getObject("escalated_at", OffsetDateTime.class)),
                rs.getInt("version"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /** A validated create. {@code original_due_at} is not a parameter: it is {@code dueAt}. */
    public record NewTask(
            UUID clientId,
            UUID sourceInteractionId,
            String title,
            String description,
            TaskType taskType,
            TaskPriority priority,
            Instant dueAt,
            UUID assigneeId,
            UUID createdBy) {}

    /**
     * @param scopeClientIds the caller's own visibility, kept apart from the {@code clientId} they
     *     may ask for: narrowing a search must never be able to widen what is visible
     * @param overdue {@code null} means "do not filter on it", which is different from false
     */
    public record TaskSearch(
            UUID assigneeId,
            UUID clientId,
            List<TaskStatus> statuses,
            TaskPriority priority,
            TaskType taskType,
            Boolean overdue,
            Instant dueFrom,
            Instant dueTo,
            String bucket,
            List<UUID> scopeClientIds) {}

    public record Reminder(Instant remindAt, ReminderChannel channel, ReminderStatus status, Instant sentAt) {}

    public record HistoryEntry(
            Instant changedAt, UUID changedBy, String changeType, String fromValue, String toValue, String reason) {}
}
