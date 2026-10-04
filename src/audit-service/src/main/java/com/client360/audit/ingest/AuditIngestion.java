package com.client360.audit.ingest;

import com.client360.audit.chain.HashChain;
import com.client360.audit.persistence.AuditRepository;
import com.client360.audit.persistence.AuditRepository.AuditRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns events on the bus into audit entries (SPEC.md §8, AT-BR-02).
 *
 * <p>This is the <strong>only</strong> way a row reaches {@code audit_log}. There is no ingestion
 * endpoint and none may be added: a compromised application service can then forge an entry only by
 * also compromising the broker.
 *
 * <p>Three properties this class exists to hold:
 *
 * <ul>
 *   <li><strong>Exactly-once storage from at-least-once delivery.</strong> A redelivery collides
 *       with the unique index and is treated as already done (AT-BR-03), not as an error to retry
 *       forever.
 *   <li><strong>Nothing is skipped.</strong> An event that cannot be stored stops its partition
 *       rather than being stepped over (AT-BR-08). A silently dropped audit event is the exact
 *       failure this service exists to prevent, so the offset does not advance past one.
 *   <li><strong>A delayed entry reads as delayed.</strong> Both the producer's clock
 *       ({@code occurred_at}) and this service's ({@code recorded_at}) are stored, so pipeline lag
 *       is visible per row rather than silently back-dating the record (AT-BR-07).
 * </ul>
 */
@Component
public class AuditIngestion {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestion.class);

    /** AT-EC-04: further back than this and the producer's clock is not to be trusted. */
    static final Duration CLOCK_SKEW_LIMIT = Duration.ofHours(1);

    /** AT-BR-06: how often a chain head is sealed for external mirroring. */
    static final long SEAL_EVERY = 1000;

    /**
     * The head of each chain, kept here because one Kafka partition belongs to one consumer at a
     * time, so nothing else is appending to it. Seeded from the database on first use and after a
     * rebalance — an in-memory head that survived a reassignment would be a guess.
     */
    private final Map<Integer, AuditRepository.ChainHead> heads = new ConcurrentHashMap<>();

    private final AuditRepository audit;
    private final ObjectMapper objectMapper;

    public AuditIngestion(AuditRepository audit, ObjectMapper objectMapper) {
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            // audit.events is this service's own (§2, AT-BR-10): a read of the log is published and
            // consumed back, so it reaches audit_log by the same path as everyone else's events and
            // never by a direct insert (AT-BR-02). Storing an event is not reading one, so there is
            // no loop to guard against.
            topics = {"client.events", "interaction.events", "task.events", "auth.events", "audit.events"},
            groupId = "audit-service.ingest")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record) {
        JsonNode event;
        try {
            event = objectMapper.readTree(record.value());
        } catch (Exception e) {
            throw new UnprocessableAuditEvent(record, "payload is not JSON");
        }
        store(event, record.partition(), record.offset(), record.topic());
    }

    /** Split from the listener so tests can drive it without a broker. */
    @Transactional
    public Result store(JsonNode event, int partition, long offset, String topic) {
        UUID eventId = uuid(event.path("eventId"));
        Instant occurredAt = instant(event.path("occurredAt"));
        JsonNode payload = event.path("payload");
        String action = payload.path("action").asText(null);
        UUID entityId = uuid(event.path("entity").path("id"));
        String entityType = event.path("entity").path("type").asText(null);

        if (eventId == null || occurredAt == null || action == null || entityId == null || entityType == null) {
            // Not retryable: redelivering a malformed envelope produces the same malformed envelope.
            throw new UnprocessableAuditEvent(topic, partition, offset, "envelope is missing a required field");
        }

        Instant now = Instant.now();
        if (occurredAt.isBefore(now.minus(CLOCK_SKEW_LIMIT))) {
            // AT-EC-04. Not stored and not skipped — the DLT is where an operator decides, and a
            // legitimate backfill after an outage comes back through the approved path stamped
            // context.backfill, which this check is not what gates.
            throw new ClockSkewSuspected(topic, partition, offset, occurredAt);
        }

        AuditRepository.ChainHead head = heads.computeIfAbsent(
                partition, id -> audit.currentHead(id).orElse(new AuditRepository.ChainHead(-1, null)));
        long nextSeq = head.chainSeq() + 1;
        byte[] prevHash = head.rowHash() == null ? HashChain.GENESIS : head.rowHash();

        JsonNode changedFields = payload.hasNonNull("changedFields") ? payload.get("changedFields") : null;
        byte[] rowHash = HashChain.rowHash(
                prevHash,
                eventId,
                occurredAt,
                uuid(event.path("actor").path("userId")),
                entityType,
                entityId,
                action,
                changedFields);

        boolean stored = audit.insert(new AuditRow(
                eventId,
                partition,
                nextSeq,
                occurredAt,
                uuid(event.path("actor").path("userId")),
                text(event.path("actor").path("email")),
                text(event.path("actor").path("role")),
                text(event.path("actor").path("ip")),
                text(event.path("actor").path("userAgent")),
                event.path("service").asText(null),
                entityType,
                entityId,
                uuid(event.path("clientId")),
                action,
                changedFields,
                payload.hasNonNull("context") ? payload.get("context") : null,
                uuid(event.path("requestId")),
                uuid(event.path("correlationId")),
                offset,
                prevHash,
                rowHash));

        if (!stored) {
            // AT-BR-03: already there. The chain is untouched — this row's sequence was never used,
            // so the head stays where it is and the next new event takes it.
            audit.recordProgress(topic, partition, offset, occurredAt);
            return Result.DUPLICATE;
        }

        heads.put(partition, new AuditRepository.ChainHead(nextSeq, rowHash));
        if (nextSeq % SEAL_EVERY == 0) {
            audit.sealHead(partition, nextSeq, rowHash);
        }
        audit.recordProgress(topic, partition, offset, occurredAt);
        return Result.STORED;
    }

    /**
     * Forgets the cached heads.
     *
     * <p>Called on a partition reassignment: the consumer that now owns a partition must read the
     * chain's real head rather than trust one this process cached before it lost the partition.
     */
    public void forgetHeads() {
        heads.clear();
        log.info("chain heads cleared; they will be re-read from the log on the next event");
    }

    public enum Result {
        STORED,
        DUPLICATE
    }

    private static UUID uuid(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }
}
