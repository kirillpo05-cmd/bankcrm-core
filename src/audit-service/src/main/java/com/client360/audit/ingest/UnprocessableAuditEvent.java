package com.client360.audit.ingest;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * An event this service cannot turn into an audit entry (AT-BR-08).
 *
 * <p>Thrown rather than logged-and-skipped on purpose. Five attempts, then the DLT plus an alert,
 * and the offset stops there pending an operator decision — because a silently dropped audit event
 * is the exact failure this service exists to prevent. A gap in the log that nobody noticed is
 * worse than a partition that stopped and said so.
 */
public class UnprocessableAuditEvent extends RuntimeException {

    public UnprocessableAuditEvent(ConsumerRecord<String, String> record, String reason) {
        this(record.topic(), record.partition(), record.offset(), reason);
    }

    public UnprocessableAuditEvent(String topic, int partition, long offset, String reason) {
        // The reason, the coordinates, and nothing from the payload: an audit event carries the
        // shape of a change, and CLAUDE.md rule 10 keeps bodies out of the log.
        super("unprocessable audit event at %s-%d offset %d: %s".formatted(topic, partition, offset, reason));
    }
}
