package com.client360.audit.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes and reads {@code audit.audit_log} (SPEC.md §8.2). Insert and select — there is nothing else. */
@Repository
public class AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditRepository.class);

    /**
     * Months whose partition this process has already ensured. Only ever grown, and only ever by
     * one entry a month — it is a cache of "this exists", and a partition is never removed while
     * events for it are still arriving (retention detaches expired ones, AT-BR-09).
     */
    private final Set<YearMonth> ensuredMonths = ConcurrentHashMap.newKeySet();

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores one entry.
     *
     * <p>Returns {@code false} when the row was already there. That is not an error: Kafka delivers
     * at least once, and {@code ux_audit_event} is what turns that into exactly-once storage
     * (AT-BR-03). The caller commits the offset either way — retrying a redelivery forever would
     * stall the partition over a row that is already correct.
     *
     * <p><strong>Both hazards are checked before the insert, never recovered after it.</strong> In
     * PostgreSQL a failed statement poisons the whole transaction: every later statement answers
     * {@code 25P02 current transaction is aborted} until a rollback. So catching the duplicate-key
     * error and carrying on, or catching "no partition" and creating one, cannot work inside the
     * transaction that just failed — the recovery statement fails too. A savepoint would allow it;
     * a look before leaping is simpler and costs one indexed lookup.
     *
     * <p>The unique index stays the real guarantee. If it ever fires despite the check, two writers
     * raced for one chain, the transaction aborts, and the event is redelivered — which is the
     * correct outcome, not something to paper over.
     */
    public boolean insert(AuditRow row) {
        ensurePartition(row.occurredAt());
        if (exists(row.occurredAt(), row.eventId())) {
            return false;
        }
        return attemptInsert(row);
    }

    private boolean exists(Instant occurredAt, UUID eventId) {
        return jdbc.sql("SELECT 1 FROM audit_log WHERE occurred_at = :occurredAt AND event_id = :eventId")
                .param("occurredAt", timestamp(occurredAt))
                .param("eventId", eventId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * AT-EC-13: an event for a month nobody created a partition for still lands.
     *
     * <p>{@code create_audit_partition} is idempotent and creates the immutability trigger in the
     * same transaction as the partition, so a partition can never exist unarmed. The months already
     * ensured are remembered, because the common case is thousands of events in the same month and
     * a round trip each would be waste.
     */
    private void ensurePartition(Instant occurredAt) {
        YearMonth month = YearMonth.from(occurredAt.atOffset(ZoneOffset.UTC));
        if (ensuredMonths.contains(month)) {
            return;
        }
        String partition = jdbc.sql(
                        "SELECT audit.create_audit_partition(date_trunc('month', CAST(:at AS timestamptz))::date)")
                .param("at", timestamp(occurredAt))
                .query(String.class)
                .single();
        if (ensuredMonths.add(month)) {
            log.debug("audit partition {} is present", partition);
        }
    }

    private boolean attemptInsert(AuditRow row) {
        jdbc.sql("""
                        INSERT INTO audit_log
                            (event_id, chain_id, chain_seq, occurred_at, actor_id, actor_email, actor_role,
                             actor_ip, user_agent, service, entity_type, entity_id, client_id, action,
                             changed_fields, context, request_id, correlation_id, kafka_offset,
                             prev_hash, row_hash)
                        VALUES
                            (:eventId, :chainId, :chainSeq, :occurredAt, :actorId, :actorEmail, :actorRole,
                             CAST(:actorIp AS inet), :userAgent, :service, :entityType, :entityId, :clientId,
                             CAST(:action AS audit.audit_action),
                             CAST(:changedFields AS jsonb), CAST(:context AS jsonb), :requestId, :correlationId,
                             :kafkaOffset, :prevHash, :rowHash)
                        """)
                .param("eventId", row.eventId())
                .param("chainId", row.chainId())
                .param("chainSeq", row.chainSeq())
                .param("occurredAt", timestamp(row.occurredAt()))
                .param("actorId", row.actorId())
                .param("actorEmail", row.actorEmail())
                .param("actorRole", row.actorRole())
                .param("actorIp", row.actorIp())
                .param("userAgent", row.userAgent())
                .param("service", row.service())
                .param("entityType", row.entityType())
                .param("entityId", row.entityId())
                .param("clientId", row.clientId())
                .param("action", row.action())
                .param(
                        "changedFields",
                        row.changedFields() == null ? null : row.changedFields().toString())
                .param("context", row.context() == null ? null : row.context().toString())
                .param("requestId", row.requestId())
                .param("correlationId", row.correlationId())
                .param("kafkaOffset", row.kafkaOffset())
                .param("prevHash", row.prevHash())
                .param("rowHash", row.rowHash())
                .update();
        return true;
    }

    /**
     * The last link of a chain, for a consumer that has just been assigned the partition.
     *
     * <p>Unrestricted by {@code occurred_at} on purpose, so it is correct for a chain that has been
     * idle for longer than any window a filter might guess. It costs one scan per partition per
     * process start, which is the right place to spend it — the alternative is a head that is
     * silently wrong after a quiet month, and a chain that fails verification for no reason.
     */
    public Optional<ChainHead> currentHead(int chainId) {
        return jdbc.sql("SELECT chain_seq, row_hash FROM audit_log WHERE chain_id = :chainId"
                        + " ORDER BY occurred_at DESC, chain_seq DESC LIMIT 1")
                .param("chainId", chainId)
                .query((rs, n) -> new ChainHead(rs.getLong("chain_seq"), rs.getBytes("row_hash")))
                .optional();
    }

    /**
     * AT-BR-06: seals a head so an attacker who rewrites a partition and recomputes its chain still
     * cannot match what was written here — and, nightly, mirrored to external WORM storage.
     */
    public void sealHead(int chainId, long chainSeq, byte[] headHash) {
        jdbc.sql("INSERT INTO audit_chain_head (chain_id, chain_seq, head_hash)"
                        + " VALUES (:chainId, :chainSeq, :headHash)"
                        + " ON CONFLICT (chain_id, chain_seq) DO NOTHING")
                .param("chainId", chainId)
                .param("chainSeq", chainSeq)
                .param("headHash", headHash)
                .update();
    }

    public void recordProgress(String topic, int partition, long offset, Instant lastEventAt) {
        jdbc.sql("INSERT INTO audit_consumer_state (topic, partition_no, last_offset, last_event_at)"
                        + " VALUES (:topic, :partition, :offset, :lastEventAt)"
                        + " ON CONFLICT (topic, partition_no) DO UPDATE SET"
                        + "   last_offset = EXCLUDED.last_offset,"
                        + "   last_event_at = EXCLUDED.last_event_at,"
                        + "   last_processed_at = now()")
                .param("topic", topic)
                .param("partition", partition)
                .param("offset", offset)
                .param("lastEventAt", timestamp(lastEventAt))
                .update();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /** @param chainSeq the sequence of the last stored row; the next one is this plus one */
    public record ChainHead(long chainSeq, byte[] rowHash) {}

    /** One entry, already hashed. This layer stores what it is given and computes nothing. */
    public record AuditRow(
            UUID eventId,
            int chainId,
            long chainSeq,
            Instant occurredAt,
            UUID actorId,
            String actorEmail,
            String actorRole,
            String actorIp,
            String userAgent,
            String service,
            String entityType,
            UUID entityId,
            UUID clientId,
            String action,
            JsonNode changedFields,
            JsonNode context,
            UUID requestId,
            UUID correlationId,
            long kafkaOffset,
            byte[] prevHash,
            byte[] rowHash) {}
}
