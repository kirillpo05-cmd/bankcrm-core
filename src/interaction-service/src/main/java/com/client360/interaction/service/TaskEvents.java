package com.client360.interaction.service;

import com.client360.common.outbox.ChangedFields;
import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
import com.client360.interaction.domain.Task;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code task.events} topic (SPEC.md §2, TR-BR-12), always through the outbox (rule 3).
 *
 * <p>Keyed on {@code client_id}, like {@code interaction.events} and for the same reason: every
 * task belongs to exactly one client (TR-BR-01), and a consumer rebuilding a client's state needs
 * that client's events in order. The notifier in v3 and audit-service both read this topic.
 *
 * <p>Titles and descriptions travel masked. A task title is staff-written and usually innocuous —
 * "Send refinancing offer" — but it is written beside a client id, and "call about the overdraft" is
 * exactly the kind of inference rule 1 keeps off the bus. The dates, the status and the people stay
 * readable, so the audit trail can still answer what changed and who changed it.
 */
@Component
public class TaskEvents {

    public static final String TOPIC = "task.events";
    private static final String ENTITY = "TASK";

    private final EventFactory events;
    private final OutboxWriter outbox;

    public TaskEvents(EventFactory events, OutboxWriter outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    public void created(Task task) {
        ChangedFields changes = ChangedFields.create()
                .sensitive("title", null, task.title())
                .sensitive("description", null, task.description())
                .put("taskType", null, task.taskType())
                .put("priority", null, task.priority())
                .put("status", null, task.status())
                .put("dueAt", null, task.dueAt())
                .put("originalDueAt", null, task.originalDueAt())
                .put("assigneeId", null, task.assigneeId())
                .put("sourceInteractionId", null, task.sourceInteractionId());
        append(
                task,
                events.event("task.created", ENTITY, task.id())
                        .clientId(task.clientId())
                        .action("CREATE")
                        .changes(changes)
                        .build());
    }

    public void updated(Task before, Task after) {
        append(
                after,
                events.event("task.updated", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .build());
    }

    /**
     * TR-US-03. {@code completedLate} is on the event rather than left to be derived, because the
     * consumer that cares about it — the coaching metrics of TR-US-08 — would otherwise need
     * {@code original_due_at} from a table it cannot read.
     */
    public void completed(Task before, Task after) {
        append(
                after,
                events.event("task.completed", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .context("completedLate", after.completedLate())
                        .build());
    }

    /** §7.3: cancelled, never deleted — a cancelled commitment is itself information. */
    public void cancelled(Task before, Task after, String reason) {
        append(
                after,
                events.event("task.cancelled", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .context("reason", reason)
                        .build());
    }

    /**
     * TR-US-04. The reason travels in {@code context}: it explains the delay rather than describing
     * a column, and "why does this keep moving" is the question a supervisor asks on the third one.
     */
    public void snoozed(Task before, Task after, String reason) {
        append(
                after,
                events.event("task.snoozed", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .context("reason", reason)
                        .context("snoozeCount", after.snoozeCount())
                        .context("snoozesRemaining", after.snoozesRemaining())
                        .build());
    }

    /**
     * TR-US-06, and TR-BR-11's per-task event when a whole client changes hands.
     *
     * @param correlationId shared by every task of one bulk reassignment, so a consumer can tell a
     *     client handover from six unrelated transfers (AT-EC-11). One event per task either way.
     */
    public void reassigned(Task before, Task after, String reason, UUID correlationId) {
        var builder = events.event("task.reassigned", ENTITY, after.id())
                .clientId(after.clientId())
                .action("UPDATE")
                .changes(diff(before, after))
                .context("reason", reason)
                .context("previousAssigneeId", before.assigneeId());
        if (correlationId != null) {
            builder.correlationId(correlationId);
        }
        append(after, builder.build());
    }

    /** TR-BR-08. Emitted by the sweep, which has no request behind it. */
    public void escalated(Task task, int fromLevel, UUID supervisorId) {
        append(
                task,
                events.event("task.escalated", ENTITY, task.id())
                        .clientId(task.clientId())
                        .action("UPDATE")
                        .changes(ChangedFields.create().put("escalationLevel", fromLevel, task.escalationLevel()))
                        .context("notifiedUserId", supervisorId)
                        .context("overdueByHours", Math.round(task.overdueByHours(java.time.Instant.now())))
                        .build());
    }

    /** Every field that can change after creation, with AR-01 applied per field. */
    private static ChangedFields diff(Task before, Task after) {
        return ChangedFields.create()
                .sensitive("title", before.title(), after.title())
                .sensitive("description", before.description(), after.description())
                .sensitive("completionNote", before.completionNote(), after.completionNote())
                .put("taskType", before.taskType(), after.taskType())
                .put("priority", before.priority(), after.priority())
                .put("status", before.status(), after.status())
                .put("dueAt", before.dueAt(), after.dueAt())
                .put("assigneeId", before.assigneeId(), after.assigneeId())
                .put("completedAt", before.completedAt(), after.completedAt())
                .put("completedBy", before.completedBy(), after.completedBy())
                .put("cancelledAt", before.cancelledAt(), after.cancelledAt())
                .put("snoozeCount", before.snoozeCount(), after.snoozeCount())
                .put("escalationLevel", before.escalationLevel(), after.escalationLevel());
    }

    private void append(Task task, com.client360.common.outbox.EventEnvelope envelope) {
        outbox.append(TOPIC, task.clientId().toString(), envelope);
    }
}
