package com.client360.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the event row in the caller's transaction (SPEC.md §3.4, CLAUDE.md rule 3).
 *
 * <p>{@link Propagation#MANDATORY} makes the rule mechanical: calling this outside a transaction
 * fails immediately instead of producing an event that could commit without its business change,
 * or a business change that could commit without its event.
 *
 * <p>Nothing in any service calls {@code KafkaTemplate.send()}. {@link OutboxRelay} publishes.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String partitionKey, EventEnvelope envelope) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventType(), e);
        }
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (event_id, aggregate_type, aggregate_id, event_type, topic, partition_key, payload)
                        VALUES (:eventId, :aggregateType, :aggregateId, :eventType, :topic, :partitionKey, CAST(:payload AS jsonb))
                        """)
                .param("eventId", envelope.eventId())
                .param("aggregateType", envelope.entity().type())
                .param("aggregateId", envelope.entity().id())
                .param("eventType", envelope.eventType())
                .param("topic", topic)
                .param("partitionKey", partitionKey)
                .param("payload", payload)
                .update();
    }
}
