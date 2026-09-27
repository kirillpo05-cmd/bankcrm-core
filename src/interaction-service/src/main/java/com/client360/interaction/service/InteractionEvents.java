package com.client360.interaction.service;

import com.client360.common.outbox.ChangedFields;
import com.client360.common.outbox.EventEnvelope;
import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
import com.client360.interaction.domain.Attachment;
import com.client360.interaction.domain.Interaction;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every event this service emits, and the one place that decides which fields are sensitive
 * (AR-01). Always through the outbox, in the caller's transaction — nothing here touches Kafka.
 *
 * <p>Keyed by {@code client_id} like every other topic (§3.2), so one client's history stays in
 * order on one partition however many services write to it.
 */
@Component
public class InteractionEvents {

    public static final String TOPIC = "interaction.events";
    private static final String ENTITY = "INTERACTION";

    private final EventFactory events;
    private final OutboxWriter outbox;
    private final TransactionTemplate ownTransaction;

    public InteractionEvents(EventFactory events, OutboxWriter outbox, PlatformTransactionManager transactions) {
        this.events = events;
        this.outbox = outbox;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * IL-BR-06's other half: this row is written in the same transaction as the interaction, so a
     * note that commits without its event — or an event without its note — is not reachable.
     */
    public void created(Interaction interaction) {
        ChangedFields changes = ChangedFields.create()
                .put("type", null, interaction.type())
                .put("direction", null, interaction.direction())
                // Subject is a plaintext, searchable column, and body is encrypted — but both are
                // written by a person about a customer, so neither goes on the bus (AR-01).
                .sensitive("subject", null, interaction.subject())
                .sensitive("body", null, interaction.body())
                .put("occurredAt", null, interaction.occurredAt())
                .put("outcome", null, interaction.outcome())
                .put("visibility", null, interaction.visibility())
                .put("source", null, interaction.source())
                .put("authorId", null, interaction.authorId())
                .put("correctsId", null, interaction.correctsId());
        append(
                interaction.clientId(),
                events.event("interaction.created", ENTITY, interaction.id())
                        .clientId(interaction.clientId())
                        .action("CREATE")
                        .changes(changes)
                        .build());
    }

    /**
     * IL-BR-02: the author reworded their own interaction inside the window. The event proves the
     * wording changed and when, never what it said before or after (AR-01).
     */
    public void updated(Interaction before, Interaction after) {
        ChangedFields changes = ChangedFields.create()
                .sensitive("subject", before.subject(), after.subject())
                .sensitive("body", before.body(), after.body())
                .put("editCount", before.editCount(), after.editCount());
        append(
                after.clientId(),
                events.event("interaction.updated", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(changes)
                        .build());
    }

    /**
     * IL-US-06: a document was attached. Its own event rather than an {@code interaction.updated},
     * because "what was attached to this record, and by whom" is a question an investigation asks
     * directly.
     *
     * <p>The filename is masked. It is free text a manager typed or a scanner produced, and a name
     * like {@code kowalska-passport.pdf} says as much about a client as the file does (AR-01). The
     * checksum travels in the clear: it identifies the bytes without describing them, which is what
     * makes it useful for proving a file was not swapped.
     */
    public void attachmentAdded(Interaction interaction, Attachment attachment) {
        ChangedFields changes = ChangedFields.create()
                .sensitive("filename", null, attachment.filename())
                .put("contentType", null, attachment.contentType())
                .put("sizeBytes", null, attachment.sizeBytes())
                .put("scan", null, attachment.scan());
        append(
                interaction.clientId(),
                events.event("interaction.attachment_added", ENTITY, interaction.id())
                        .clientId(interaction.clientId())
                        .action("CREATE")
                        .changes(changes)
                        .context("attachmentId", attachment.id())
                        .build());
    }

    /** §6.3: the row is soft-deleted and the object stays, so the event is the record of removal. */
    public void attachmentDeleted(Interaction interaction, Attachment attachment) {
        append(
                interaction.clientId(),
                events.event("interaction.attachment_deleted", ENTITY, interaction.id())
                        .clientId(interaction.clientId())
                        .action("DELETE")
                        .context("attachmentId", attachment.id())
                        .build());
    }

    /**
     * IL-BR-10: a ticket moved. Its own event type — {@code ticket.status_changed}, not
     * {@code interaction.updated} — because "when did this ticket become breached, and who let it"
     * is a question the SLA report and an audit both ask, and neither should have to sift ordinary
     * edits for it.
     *
     * <p>Nothing here is masked: a status, a priority, an assignee and a deadline are operational
     * facts about the bank's own handling, not anything about the client (AR-01). The resolution
     * note is the exception — it is free text a manager wrote and may quote the client, so the
     * event proves it changed and never what it says.
     */
    public void ticketChanged(Interaction before, Interaction after) {
        Interaction.Ticket was = before.ticket();
        Interaction.Ticket now = after.ticket();
        ChangedFields changes = ChangedFields.create()
                .put("ticketStatus", was.status(), now.status())
                .put("ticketPriority", was.priority(), now.priority())
                .put("ticketAssigneeId", was.assigneeId(), now.assigneeId())
                .put("slaDueAt", was.slaDueAt(), now.slaDueAt())
                .put("slaPausedSeconds", was.pausedSeconds(), now.pausedSeconds())
                .sensitive("resolutionNote", was.resolutionNote(), now.resolutionNote());
        append(
                after.clientId(),
                events.event("ticket.status_changed", ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(changes)
                        .build());
    }

    /**
     * IL-BR-03: an amendment appended after the window. Its own event type, not
     * {@code interaction.created}, because "the record was corrected" is the question an
     * investigation asks, and it should not have to reconstruct that from {@code correctsId}.
     */
    public void corrected(Interaction correction, Interaction original) {
        ChangedFields changes = ChangedFields.create()
                .put("type", null, correction.type())
                .sensitive("subject", null, correction.subject())
                .sensitive("body", null, correction.body())
                .put("occurredAt", null, correction.occurredAt())
                .put("visibility", null, correction.visibility())
                .put("authorId", null, correction.authorId())
                .put("correctsId", null, original.id());
        append(
                correction.clientId(),
                events.event("interaction.corrected", ENTITY, correction.id())
                        .clientId(correction.clientId())
                        .action("CREATE")
                        .changes(changes)
                        .context("correctsId", original.id())
                        .build());
    }

    /**
     * §6.3 soft delete. The reason travels in {@code context}: it explains the removal rather than
     * describing a column, and "who removed this, and why" is what an auditor asks.
     */
    public void deleted(Interaction interaction, String reason) {
        append(
                interaction.clientId(),
                events.event("interaction.deleted", ENTITY, interaction.id())
                        .clientId(interaction.clientId())
                        .action("DELETE")
                        .context("reason", reason)
                        .build());
    }

    /**
     * IL-BR-11: opening a full body is a disclosure and is audited; scrolling the timeline, which
     * returns a preview, is not.
     */
    public void readSensitive(Interaction interaction, String disclosure) {
        append(
                interaction.clientId(),
                events.event("interaction.read_sensitive", ENTITY, interaction.id())
                        .clientId(interaction.clientId())
                        .action("READ_SENSITIVE")
                        .context("disclosure", disclosure)
                        .build());
    }

    /**
     * ER-01: the caller is told {@code 404}, the audit log is told the truth. Commits separately,
     * so it survives the rejection that follows.
     */
    public void recordDenial(UUID clientId, UUID interactionId, String permission) {
        ownTransaction.executeWithoutResult(status -> append(
                clientId,
                events.event("interaction.permission_denied", ENTITY, interactionId)
                        .clientId(clientId)
                        .action("PERMISSION_DENIED")
                        .context("permission", permission)
                        .build()));
    }

    private void append(UUID clientId, EventEnvelope envelope) {
        outbox.append(TOPIC, clientId.toString(), envelope);
    }
}
