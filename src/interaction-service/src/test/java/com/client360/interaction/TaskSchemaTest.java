package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V8's constraints, tested the way {@code .claude/rules/db-migrations.md} asks: by trying to
 * violate every one it adds and asserting the database refuses.
 *
 * <p>These rows are inserted directly rather than through the API, because the point is what the
 * schema guarantees when something bypasses the service — a repair script, a future endpoint, a
 * bug. A constraint only tested through the code that already respects it is untested.
 */
class TaskSchemaTest extends AbstractInteractionIntegrationTest {

    @Nested
    class TaskColumns {

        /**
         * TR-BR-04, and the reason it is a constraint rather than a service check: a well-meaning
         * bulk update to "tidy up overdue work" would otherwise rewrite what was promised.
         */
        @Test
        void aDeadlineCannotPrecedeTheOriginal_TR_BR_04() {
            assertThatThrownBy(
                            () -> insertTask(task -> task.dueAt(hoursFromNow(1)).originalDueAt(hoursFromNow(48))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_due_forward");
        }

        @Test
        void aDeadlineEqualToTheOriginalIsAccepted_TR_BR_04() {
            Instant due = hoursFromNow(24);
            assertThat(insertTask(task -> task.dueAt(due).originalDueAt(due))).isNotNull();
        }

        /** TR-BR-06: three, and the database is the last line (rule 9). */
        @Test
        void aFourthSnoozeIsRefused_TR_BR_06() {
            assertThatThrownBy(() -> insertTask(task -> task.snoozeCount(4).snoozeReason("still waiting")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_snooze_cap");
        }

        /** A snooze without a reason is the thing the cap exists to prevent, recorded anonymously. */
        @Test
        void aSnoozeWithoutAReasonIsRefused_TR_BR_06() {
            assertThatThrownBy(() -> insertTask(task -> task.snoozeCount(1).snoozeReason(null)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_snooze_reason");
        }

        @Test
        void aBlankTitleIsRefused_7_2_2() {
            assertThatThrownBy(() -> insertTask(task -> task.title("  ")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_title_not_blank");
        }

        /** A DONE task carries both completion fields or neither — never half a completion. */
        @Test
        void doneWithoutACompleterIsRefused_7_2_2() {
            assertThatThrownBy(() -> insertTask(task -> task.status("DONE").completedAt(Instant.now())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_done_fields");
        }

        @Test
        void aCompletionTimestampOnAnOpenTaskIsRefused_7_2_2() {
            assertThatThrownBy(() ->
                            insertTask(task -> task.completedAt(Instant.now()).completedBy(ADAM_NOWAK)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_done_fields");
        }

        /** A cancellation with no reason is a commitment that vanished without an account of why. */
        @Test
        void cancelledWithoutAReasonIsRefused_7_2_2() {
            assertThatThrownBy(() -> insertTask(task -> task.status("CANCELLED").cancelledAt(Instant.now())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_cancelled_fields");
        }

        /** TR-BR-08: the ladder has four rungs and no fifth. */
        @Test
        void anEscalationBeyondLevelThreeIsRefused_TR_BR_08() {
            assertThatThrownBy(() -> insertTask(task -> task.escalationLevel(4).escalatedAt(Instant.now())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_escalation");
        }

        /** An escalated task says when. Level and timestamp move together or not at all. */
        @Test
        void anEscalationWithoutATimestampIsRefused_TR_BR_08() {
            assertThatThrownBy(() -> insertTask(task -> task.escalationLevel(1)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_tasks_escalated_pair");
        }

        /** TR-BR-01: a task without a client is not follow-up work. */
        @Test
        void aTaskWithoutAClientIsRefused_TR_BR_01() {
            assertThatThrownBy(() -> insertTask(task -> task.clientId(null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** An unknown assignee cannot own work; RESTRICT is what keeps a task attributable. */
        @Test
        void anUnknownAssigneeIsRefused_7_2_2() {
            assertThatThrownBy(() -> insertTask(task -> task.assigneeId(UUID.randomUUID())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_tasks_assignee");
        }
    }

    @Nested
    class Reminders {

        /** TR-BR-09: this key is what makes at-least-once delivery idempotent. */
        @Test
        void oneReminderPerTaskTimeAndChannel_TR_BR_09() {
            UUID taskId = insertTask(task -> task);
            Instant at = hoursFromNow(23);
            insertReminder(taskId, at, "IN_APP", "SCHEDULED", null);
            assertThatThrownBy(() -> insertReminder(taskId, at, "IN_APP", "SCHEDULED", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_reminders");
        }

        /** The same time on a different channel is a different reminder, and allowed. */
        @Test
        void theSameTimeOnAnotherChannelIsFine_TR_BR_09() {
            UUID taskId = insertTask(task -> task);
            Instant at = hoursFromNow(23);
            insertReminder(taskId, at, "IN_APP", "SCHEDULED", null);
            insertReminder(taskId, at, "EMAIL", "SCHEDULED", null);
            assertThat(reminderCount(taskId)).isEqualTo(2);
        }

        /** A SENT reminder says when it went. Anything else must not claim to have been sent. */
        @Test
        void sentWithoutATimestampIsRefused_7_2_3() {
            UUID taskId = insertTask(task -> task);
            assertThatThrownBy(() -> insertReminder(taskId, hoursFromNow(23), "IN_APP", "SENT", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_reminders_sent_pair");
        }

        @Test
        void aTimestampWithoutSentIsRefused_7_2_3() {
            UUID taskId = insertTask(task -> task);
            assertThatThrownBy(() -> insertReminder(taskId, hoursFromNow(23), "IN_APP", "SCHEDULED", Instant.now()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_reminders_sent_pair");
        }

        /**
         * CASCADE: a reminder is a pure projection of its task with no independent authority, so it
         * goes when the task does. Nothing is lost, because a reminder that was actually sent is
         * recorded in the audit trail rather than here.
         */
        @Test
        void remindersGoWithTheirTask_7_2_3() {
            UUID taskId = insertTask(task -> task);
            insertReminder(taskId, hoursFromNow(23), "IN_APP", "SCHEDULED", null);
            jdbc.sql("DELETE FROM interaction.tasks WHERE id = :id")
                    .param("id", taskId)
                    .update();
            assertThat(reminderCount(taskId)).isZero();
        }
    }

    @Nested
    class History {

        /** §7.2.4: a history row names who changed what, and RESTRICT keeps that name resolvable. */
        @Test
        void anUnknownAuthorIsRefused_7_2_4() {
            UUID taskId = insertTask(task -> task);
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO interaction.task_history"
                                    + " (task_id, changed_by, change_type) VALUES (:task, :who, 'SNOOZED')")
                            .param("task", taskId)
                            .param("who", UUID.randomUUID())
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_task_history_user");
        }

        @Test
        void aBlankChangeTypeIsRefused_7_2_4() {
            UUID taskId = insertTask(task -> task);
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO interaction.task_history"
                                    + " (task_id, changed_by, change_type) VALUES (:task, :who, '  ')")
                            .param("task", taskId)
                            .param("who", ADAM_NOWAK)
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_task_history_type");
        }

        @Test
        void historyGoesWithItsTask_7_2_4() {
            UUID taskId = insertTask(task -> task);
            jdbc.sql("INSERT INTO interaction.task_history (task_id, changed_by, change_type)"
                            + " VALUES (:task, :who, 'CREATED')")
                    .param("task", taskId)
                    .param("who", ADAM_NOWAK)
                    .update();
            jdbc.sql("DELETE FROM interaction.tasks WHERE id = :id")
                    .param("id", taskId)
                    .update();
            assertThat(jdbc.sql("SELECT count(*) FROM interaction.task_history WHERE task_id = :id")
                            .param("id", taskId)
                            .query(Integer.class)
                            .single())
                    .isZero();
        }
    }

    /**
     * There is no {@code is_overdue} column, and this is the test that says so.
     *
     * <p>If somebody adds one later — a generated column cannot work, but a plain one could — this
     * fails and asks them to read TR-BR-02 first. The whole module's correctness rests on overdue
     * being a function of the clock rather than a value somebody has to remember to refresh.
     */
    @Test
    void thereIsNoStoredOverdueColumn_TR_BR_02() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_schema = 'interaction' AND table_name = 'tasks'
                           AND column_name IN ('is_overdue', 'overdue')
                        """).query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static Instant hoursFromNow(int hours) {
        return Instant.now().plusSeconds(hours * 3600L);
    }

    private UUID insertTask(java.util.function.UnaryOperator<Draft> customise) {
        Draft draft = customise.apply(new Draft());
        return jdbc.sql("""
                        INSERT INTO interaction.tasks
                            (client_id, title, due_at, original_due_at, assignee_id, created_by, status,
                             snooze_count, last_snooze_reason, completed_at, completed_by,
                             cancelled_at, cancellation_reason, escalation_level, escalated_at)
                        VALUES (:clientId, :title, :dueAt, :originalDueAt, :assigneeId, :createdBy,
                                CAST(:status AS interaction.task_status), :snoozeCount, :snoozeReason,
                                :completedAt, :completedBy, :cancelledAt, :cancellationReason,
                                :escalationLevel, :escalatedAt)
                        RETURNING id
                        """)
                .param("clientId", draft.clientId)
                .param("title", draft.title)
                .param("dueAt", timestamp(draft.dueAt))
                .param("originalDueAt", timestamp(draft.originalDueAt))
                .param("assigneeId", draft.assigneeId)
                .param("createdBy", ADAM_NOWAK)
                .param("status", draft.status)
                .param("snoozeCount", draft.snoozeCount)
                .param("snoozeReason", draft.snoozeReason)
                .param("completedAt", timestamp(draft.completedAt))
                .param("completedBy", draft.completedBy)
                .param("cancelledAt", timestamp(draft.cancelledAt))
                .param("cancellationReason", draft.cancellationReason)
                .param("escalationLevel", draft.escalationLevel)
                .param("escalatedAt", timestamp(draft.escalatedAt))
                .query(UUID.class)
                .single();
    }

    private void insertReminder(UUID taskId, Instant at, String channel, String status, Instant sentAt) {
        jdbc.sql("""
                        INSERT INTO interaction.task_reminders (task_id, remind_at, channel, status, sent_at)
                        VALUES (:task, :at, CAST(:channel AS interaction.reminder_channel),
                                CAST(:status AS interaction.reminder_status), :sentAt)
                        """)
                .param("task", taskId)
                .param("at", timestamp(at))
                .param("channel", channel)
                .param("status", status)
                .param("sentAt", timestamp(sentAt))
                .update();
    }

    private int reminderCount(UUID taskId) {
        return jdbc.sql("SELECT count(*) FROM interaction.task_reminders WHERE task_id = :id")
                .param("id", taskId)
                .query(Integer.class)
                .single();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /** A mutable builder, so each test states only the field it is trying to break. */
    private static final class Draft {
        private UUID clientId = CLIENT_ID;
        private String title = "A valid title";
        private Instant dueAt = hoursFromNow(24);
        private Instant originalDueAt = hoursFromNow(24);
        private UUID assigneeId = ADAM_NOWAK;
        private String status = "OPEN";
        private int snoozeCount = 0;
        private String snoozeReason;
        private Instant completedAt;
        private UUID completedBy;
        private Instant cancelledAt;
        private String cancellationReason;
        private int escalationLevel = 0;
        private Instant escalatedAt;

        Draft clientId(UUID value) {
            this.clientId = value;
            return this;
        }

        Draft title(String value) {
            this.title = value;
            return this;
        }

        Draft dueAt(Instant value) {
            this.dueAt = value;
            return this;
        }

        Draft originalDueAt(Instant value) {
            this.originalDueAt = value;
            return this;
        }

        Draft assigneeId(UUID value) {
            this.assigneeId = value;
            return this;
        }

        Draft status(String value) {
            this.status = value;
            return this;
        }

        Draft snoozeCount(int value) {
            this.snoozeCount = value;
            return this;
        }

        Draft snoozeReason(String value) {
            this.snoozeReason = value;
            return this;
        }

        Draft completedAt(Instant value) {
            this.completedAt = value;
            return this;
        }

        Draft completedBy(UUID value) {
            this.completedBy = value;
            return this;
        }

        Draft cancelledAt(Instant value) {
            this.cancelledAt = value;
            return this;
        }

        Draft escalationLevel(int value) {
            this.escalationLevel = value;
            return this;
        }

        Draft escalatedAt(Instant value) {
            this.escalatedAt = value;
            return this;
        }
    }
}
