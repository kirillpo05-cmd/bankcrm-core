package com.client360.client.service;

import com.client360.client.domain.Client;
import com.client360.common.outbox.ChangedFields;
import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every event this service emits, and the one place that decides which fields are sensitive
 * (AR-01). Always through the outbox, in the caller's transaction — nothing here touches Kafka.
 *
 * <p>"Sensitive" is wider than "encrypted": names and date of birth are plaintext columns but
 * still personal data, and an erased client must leave audit rows holding only a UUID (AT-EC-08).
 */
@Component
public class ClientEvents {

    public static final String TOPIC = "client.events";
    private static final String ENTITY = "CLIENT";

    private final EventFactory events;
    private final OutboxWriter outbox;

    /**
     * Denials are written in their own transaction. Recording one in the transaction that is
     * about to fail would roll the audit row back with it — and an unrecorded denial is exactly
     * what ER-01's 404 is meant to stay accountable for.
     */
    private final TransactionTemplate ownTransaction;

    public ClientEvents(EventFactory events, OutboxWriter outbox, PlatformTransactionManager transactions) {
        this.events = events;
        this.outbox = outbox;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** CP-BR-12. One event per entity, never one per batch (AT-EC-11). */
    public void created(Client client) {
        ChangedFields changes = ChangedFields.create()
                .put("externalRef", null, client.externalRef())
                .sensitive("firstName", null, client.firstName())
                .sensitive("lastName", null, client.lastName())
                .sensitive("middleName", null, client.middleName())
                .sensitive("dateOfBirth", null, client.dateOfBirth())
                .sensitive("email", null, client.email())
                .sensitive("phone", null, client.phone())
                .sensitive("taxId", null, client.taxId())
                .sensitive("address", null, client.address())
                .put("preferredChannel", null, client.preferredChannel())
                .put("segment", null, client.segment())
                .put("status", null, client.status())
                .put("risk", null, client.risk())
                .put("kycStatus", null, client.kycStatus())
                .put("ownerManagerId", null, client.ownerManagerId())
                .put("teamId", null, client.teamId());
        append(
                client.id(),
                events.event("client.created", ENTITY, client.id())
                        .clientId(client.id())
                        .action("CREATE")
                        .changes(changes)
                        .build());
    }

    /**
     * CP-BR-13: emitted only when decrypted PII actually reaches the caller — opening a card, or
     * an out-of-scope identifier hit in lookup. A masked result list emits nothing, which is what
     * keeps the audit table from drowning in list views.
     *
     * @param disclosure what was shown, e.g. {@code "client.card"} or {@code "lookup.existence"}
     */
    public void readSensitive(UUID clientId, String disclosure) {
        append(
                clientId,
                events.event("client.read_sensitive", ENTITY, clientId)
                        .clientId(clientId)
                        .action("READ_SENSITIVE")
                        .context("disclosure", disclosure)
                        .build());
    }

    /**
     * ER-01: the caller is told {@code 404}, the audit log is told the truth. Commits on its own,
     * so it survives the rejection that follows.
     */
    public void recordDenial(UUID clientId, String permission) {
        ownTransaction.executeWithoutResult(status -> append(
                clientId,
                events.event("client.permission_denied", ENTITY, clientId)
                        .clientId(clientId)
                        .action("PERMISSION_DENIED")
                        .context("permission", permission)
                        .build()));
    }

    private void append(UUID clientId, com.client360.common.outbox.EventEnvelope envelope) {
        outbox.append(TOPIC, clientId.toString(), envelope);
    }
}
