package com.client360.interaction.service;

import com.client360.common.outbox.ChangedFields;
import com.client360.common.outbox.EventEnvelope;
import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
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
