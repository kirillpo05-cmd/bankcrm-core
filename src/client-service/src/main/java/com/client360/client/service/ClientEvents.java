package com.client360.client.service;

import com.client360.client.domain.Client;
import com.client360.client.domain.ClientProduct;
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

    /** {@code audit_log.entity_type} is a VARCHAR, so the projection gets its own entity name. */
    private static final String PRODUCT_ENTITY = "CLIENT_PRODUCT";

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
     * {@code PATCH /clients/{id}} (CP-US-03). The caller only emits this when {@code changes} is
     * non-empty: a patch that changed nothing wrote nothing, and CP-BR-12 counts writes.
     */
    public void updated(Client before, Client after) {
        append(
                after.id(),
                events.event("client.updated", ENTITY, after.id())
                        .clientId(after.id())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .build());
    }

    /**
     * {@code POST /clients/{id}/kyc} (CP-BR-04). Its own event type rather than a
     * {@code client.updated}, so a compliance query for KYC decisions does not have to sift every
     * phone-number correction. The note and the rejection reason are free text about identity
     * documents and are masked like any other PII.
     */
    public void kycChanged(Client before, Client after) {
        append(
                after.id(),
                events.event("client.kyc_changed", ENTITY, after.id())
                        .clientId(after.id())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .build());
    }

    /**
     * CP-US-05. The reason travels in {@code context}, not {@code changedFields}: it explains the
     * move rather than describing a column, and "why did this client change hands" is the question
     * a supervisor asks a year later.
     */
    public void reassigned(Client before, Client after, String reason) {
        append(
                after.id(),
                events.event("client.reassigned", ENTITY, after.id())
                        .clientId(after.id())
                        .action("UPDATE")
                        .changes(diff(before, after))
                        .context("reason", reason)
                        .build());
    }

    /**
     * §4.9 soft delete. The row survives — audit rows reference {@code client_id} and must stay
     * resolvable — so this records the intent, not a disappearance.
     */
    public void deleted(Client client, String reason) {
        append(
                client.id(),
                events.event("client.deleted", ENTITY, client.id())
                        .clientId(client.id())
                        .action("DELETE")
                        .context("reason", reason)
                        .build());
    }

    /**
     * A product recorded from core banking. Rule 3 applies to every mutation, including one on a
     * projection: "core banking said so" is still an answer the audit trail has to be able to
     * give, and the entity is the product rather than the client.
     */
    public void productAdded(ClientProduct product) {
        append(
                product.clientId(),
                events.event("client.product_added", PRODUCT_ENTITY, product.id())
                        .clientId(product.clientId())
                        .action("CREATE")
                        .changes(productDiff(null, product))
                        .build());
    }

    public void productUpdated(ClientProduct before, ClientProduct after) {
        append(
                after.clientId(),
                events.event("client.product_updated", PRODUCT_ENTITY, after.id())
                        .clientId(after.clientId())
                        .action("UPDATE")
                        .changes(productDiff(before, after))
                        .build());
    }

    /**
     * The masked number and the balance are masked again here. Neither is PII on its own, but the
     * envelope carries {@code clientId} beside them, and "this person holds 12 500 PLN" is exactly
     * the kind of financial detail rule 1 keeps off the bus. Type, status and the dates stay
     * readable, so the audit trail can still answer what changed.
     */
    private static ChangedFields productDiff(ClientProduct before, ClientProduct after) {
        return ChangedFields.create()
                .put("type", before == null ? null : before.type(), after.type())
                .put("externalProductId", before == null ? null : before.externalProductId(), after.externalProductId())
                .sensitive("maskedNumber", before == null ? null : before.maskedNumber(), after.maskedNumber())
                .put("status", before == null ? null : before.status(), after.status())
                .put("currency", before == null ? null : before.currency(), after.currency())
                .sensitive("balanceMinor", before == null ? null : before.balanceMinor(), after.balanceMinor())
                .put("openedOn", before == null ? null : before.openedOn(), after.openedOn())
                .put("closedOn", before == null ? null : before.closedOn(), after.closedOn());
    }

    /**
     * Every field that can change after creation, with AR-01 applied per field. Unchanged fields
     * are dropped by {@link ChangedFields}, so this is safe to call with the whole record.
     */
    static ChangedFields diff(Client before, Client after) {
        return ChangedFields.create()
                .sensitive("firstName", before.firstName(), after.firstName())
                .sensitive("lastName", before.lastName(), after.lastName())
                .sensitive("middleName", before.middleName(), after.middleName())
                .sensitive("dateOfBirth", before.dateOfBirth(), after.dateOfBirth())
                .sensitive("email", before.email(), after.email())
                .sensitive("phone", before.phone(), after.phone())
                .sensitive("taxId", before.taxId(), after.taxId())
                .sensitive("address", before.address(), after.address())
                .put("preferredChannel", before.preferredChannel(), after.preferredChannel())
                .put("segment", before.segment(), after.segment())
                .put("status", before.status(), after.status())
                .put("risk", before.risk(), after.risk())
                .put("kycStatus", before.kycStatus(), after.kycStatus())
                .put("kycVerifiedAt", before.kycVerifiedAt(), after.kycVerifiedAt())
                .put("kycExpiresAt", before.kycExpiresAt(), after.kycExpiresAt())
                .sensitive("kycRejectionReason", before.kycRejectionReason(), after.kycRejectionReason())
                .sensitive("kycNote", before.kycNote(), after.kycNote())
                // Ownership changes only through reassignment, but it belongs here rather than in
                // that one event: "who holds this client, and since when" is the question the
                // audit trail is asked, and an event that recorded only the reason could not
                // answer it. They are staff ids, not client data, so they stay readable.
                .put("ownerManagerId", before.ownerManagerId(), after.ownerManagerId())
                .put("teamId", before.teamId(), after.teamId());
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
     * A disclosure that rides on a rejected request — the {@code 409 VERSION_CONFLICT} body puts
     * the current decrypted record in {@code details[0].current} so the UI can render a diff
     * (§4.8). The request's own transaction rolls back, so this commits separately; otherwise
     * the one response that hands PII over on a failure would be the one that is never audited.
     */
    public void readSensitiveCommitted(UUID clientId, String disclosure) {
        ownTransaction.executeWithoutResult(status -> readSensitive(clientId, disclosure));
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
