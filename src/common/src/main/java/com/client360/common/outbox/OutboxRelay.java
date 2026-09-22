package com.client360.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows to Kafka (SPEC.md §3.4): polls every 200 ms, claims up to 100
 * rows with {@code FOR UPDATE SKIP LOCKED} so several instances can run side by side, sends each
 * and stamps {@code published_at}.
 *
 * <p>Ordering: rows go out in {@code created_at, id} order, and a failed send stops the batch.
 * Skipping past a failure would let a later event for the same key overtake an earlier one. If
 * Kafka is down nothing is lost — rows accumulate and the next poll retries (IL-EC-15, AT-EC-05).
 *
 * <p>Delivery is at-least-once: a send that succeeded before the stamp rolled back is re-sent,
 * and the audit consumer deduplicates on {@code event_id} (AT-BR-03).
 */
@Component
@ConditionalOnProperty(name = "client360.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_BATCHES_PER_POLL = 50;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxRelay(
            JdbcClient jdbc,
            TransactionTemplate tx,
            KafkaTemplate<String, String> kafka,
            MeterRegistry meters,
            @Value("${client360.outbox.batch-size:100}") int batchSize,
            @Value("${client360.outbox.send-timeout:20s}") Duration sendTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        // §10.5: outbox depth and age; alert when the oldest unpublished row passes 5 minutes.
        Gauge.builder("client360.outbox.depth", depth, AtomicLong::get).register(meters);
        Gauge.builder("client360.outbox.oldest.age", oldestAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${client360.outbox.poll-interval:200ms}")
    public void poll() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_POLL; i++) {
                if (publishBatch() < batchSize) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay poll failed; will retry", e);
        }
    }

    /** @return the number of rows published */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            SELECT id, event_id, event_type, topic, partition_key, payload::text AS payload
                              FROM outbox_events
                             WHERE published_at IS NULL
                             ORDER BY created_at, id
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(
                            rs.getLong("id"),
                            rs.getObject("event_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("topic"),
                            rs.getString("partition_key"),
                            rs.getString("payload")))
                    .list();
            int sent = 0;
            for (Row row : rows) {
                try {
                    send(row);
                } catch (Exception e) {
                    recordFailure(row, e);
                    break;
                }
                jdbc.sql(
                                "UPDATE outbox_events SET published_at = now(), attempts = LEAST(attempts + 1, 100), last_error = NULL WHERE id = :id")
                        .param("id", row.id())
                        .update();
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    @Scheduled(fixedDelayString = "${client360.outbox.metrics-interval:10s}")
    public void refreshMetrics() {
        try {
            jdbc.sql("""
                            SELECT count(*) AS depth,
                                   COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint AS age
                              FROM outbox_events
                             WHERE published_at IS NULL
                            """).query(rs -> {
                depth.set(rs.getLong("depth"));
                oldestAgeSeconds.set(rs.getLong("age"));
            });
        } catch (RuntimeException e) {
            log.debug("outbox metrics refresh failed", e);
        }
    }

    private void send(Row row) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Runs inside the claiming transaction, which then commits — so the attempt counter survives
     * even though the row stays unpublished.
     */
    private void recordFailure(Row row, Exception e) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (error.length() > 1000) {
            error = error.substring(0, 1000);
        }
        log.warn(
                "outbox publish failed for event {} ({}); batch stopped to preserve ordering",
                row.eventId(),
                row.eventType());
        jdbc.sql("UPDATE outbox_events SET attempts = LEAST(attempts + 1, 100), last_error = :error WHERE id = :id")
                .param("error", error)
                .param("id", row.id())
                .update();
    }

    private record Row(long id, UUID eventId, String eventType, String topic, String partitionKey, String payload) {}
}
