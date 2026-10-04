package com.client360.audit.service;

import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
import com.client360.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The self-audit (SPEC.md §8.4, AT-BR-10): reading the audit log is itself audited.
 *
 * <p>Watching the watchers is not optional, and the way it is done here is the whole point. The
 * obvious implementation — the read handler inserting its own {@code audit_log} row — is exactly the
 * direct write path AT-BR-02 forbids, and it would mean a compromised audit API could forge entries
 * without touching the broker. So a read writes an outbox row, the relay publishes it to
 * {@code audit.events}, and the same consumer that stores everyone else's events stores this one.
 *
 * <p>The cost is that a self-audit entry appears a moment after the read rather than within it, and
 * that is the right trade: a slightly late entry that cannot be forged beats a prompt one that can.
 *
 * <p>Nothing here records what a search <em>returned</em>. The filter and the row count, never the
 * rows — an audit of a search must not become a second copy of the thing searched (AT-BR-04).
 */
@Component
public class AuditEvents {

    public static final String TOPIC = "audit.events";

    private static final String ENTITY = "AUDIT_LOG";

    /** Entity type for the log's own storage. {@code ck_audit_actor_present} knows this name (V2). */
    private static final String PARTITION_ENTITY = "AUDIT_PARTITION";

    /**
     * The subject of a read of the log as a whole. A search has no single row to point at, and the
     * log itself is what was read.
     */
    private static final UUID THE_LOG = new UUID(0L, 0L);

    private final EventFactory events;
    private final OutboxWriter outbox;

    /**
     * Self-audit commits in its own transaction.
     *
     * <p>A search is a read and runs without one; more importantly, an export that fails halfway
     * must still have recorded that someone asked for it. An audit row that rolls back with the
     * thing it was auditing is not an audit row.
     */
    private final TransactionTemplate ownTransaction;

    public AuditEvents(EventFactory events, OutboxWriter outbox, PlatformTransactionManager transactions) {
        this.events = events;
        this.outbox = outbox;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@code GET /audit} (AT-BR-10). Keyed on the actor, as §2 specifies for this topic: "who has
     * been reading the audit log" is the question it is asked, and keying by actor keeps one
     * person's reads in order on one partition.
     *
     * @param filterSummary the filter as the caller supplied it, already reduced to identifiers and
     *     ranges — never the result
     */
    public void searched(CurrentUser caller, String filterSummary, int returnedRows) {
        append(
                caller,
                events.event("audit.searched", ENTITY, THE_LOG)
                        .action("READ_SENSITIVE")
                        .context("disclosure", "audit.search")
                        .context("filter", filterSummary)
                        .context("returnedRows", returnedRows)
                        .build());
    }

    /**
     * {@code POST /audit/verify} (AT-BR-10). Recorded whatever the verdict: a verification that
     * found a break and a verification that found nothing are equally interesting, and only one of
     * them is good news.
     */
    public void verified(CurrentUser caller, boolean verified, long rowsChecked, java.util.List<String> diagnoses) {
        append(
                caller,
                events.event("audit.verified", ENTITY, THE_LOG)
                        .action("READ_SENSITIVE")
                        .context("disclosure", "audit.verify")
                        .context("verified", verified)
                        .context("rowsChecked", rowsChecked)
                        // Empty on a clean run. Present, it names which of the three diagnoses
                        // ChainVerifier reached, which is the part an investigator starts from.
                        .context("diagnoses", diagnoses.isEmpty() ? null : diagnoses)
                        .build());
    }

    /** An export was requested (AT-US-04). The reason is mandatory, so it is the point of the row. */
    public void exportRequested(CurrentUser caller, UUID jobId, String filterSummary, String reason, String format) {
        append(
                caller,
                events.event("audit.export_requested", ENTITY, jobId)
                        .action("EXPORT")
                        .context("stage", "REQUESTED")
                        .context("jobId", jobId)
                        .context("filter", filterSummary)
                        .context("format", format)
                        .context("reason", reason)
                        .build());
    }

    /**
     * §8.3: "Downloading an export emits an {@code EXPORT} audit event carrying the {@code jobId},
     * row count and checksum." The checksum is what ties a file someone is holding to a row in this
     * log — without it the entry says a download happened but not of what.
     */
    public void exportDownloaded(CurrentUser caller, UUID jobId, long rowCount, String checksumSha256) {
        append(
                caller,
                events.event("audit.export_downloaded", ENTITY, jobId)
                        .action("EXPORT")
                        .context("stage", "DOWNLOADED")
                        .context("jobId", jobId)
                        .context("rowCount", rowCount)
                        .context("checksumSha256", checksumSha256)
                        .build());
    }

    /**
     * AT-BR-09: the archival operation is itself audited.
     *
     * <p>The one event in the system with no actor. A scheduled job detached the partition, and
     * naming a person would make the log claim someone did it; V2 widened
     * {@code ck_audit_actor_present} for exactly this case rather than letting the operation that
     * removes evidence be the operation with no record.
     */
    public void partitionDetached(String partitionName, String rangeStart, String rangeEnd, long rowCount) {
        // Deterministic from the partition name, so a redelivery is the same event and the unique
        // index absorbs it (AT-BR-03) — there is no request id here to make it unique.
        UUID entityId = UUID.nameUUIDFromBytes(
                ("audit_partition:" + partitionName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ownTransaction.executeWithoutResult(status -> outbox.append(
                TOPIC,
                partitionName,
                events.event("audit.partition_detached", PARTITION_ENTITY, entityId)
                        .action("DELETE")
                        .context("partitionName", partitionName)
                        .context("rangeStart", rangeStart)
                        .context("rangeEnd", rangeEnd)
                        .context("rowCount", rowCount)
                        // Said in the event, because "DELETE" on an audit partition would otherwise
                        // read as the one thing this service does not do (AT-BR-01, AT-BR-09).
                        .context("disposition", "DETACHED_NOT_DROPPED")
                        .build()));
    }

    /** A partition created ahead of time. Cheap to record, and the gap is what AT-EC-13 is about. */
    public void partitionCreated(String partitionName) {
        UUID entityId = UUID.nameUUIDFromBytes(
                ("audit_partition:" + partitionName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ownTransaction.executeWithoutResult(status -> outbox.append(
                TOPIC,
                partitionName,
                events.event("audit.partition_created", PARTITION_ENTITY, entityId)
                        .action("CREATE")
                        .context("partitionName", partitionName)
                        .build()));
    }

    private void append(CurrentUser caller, com.client360.common.outbox.EventEnvelope envelope) {
        ownTransaction.executeWithoutResult(
                status -> outbox.append(TOPIC, caller.id().toString(), envelope));
    }
}
