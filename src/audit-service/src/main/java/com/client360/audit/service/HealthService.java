package com.client360.audit.service;

import com.client360.audit.api.AuditHealth;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /audit/health} (SPEC.md §8.3, AT-US-06, AT-EC-05). */
@Service
public class HealthService {

    private final JdbcClient jdbc;

    public HealthService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lag is computed on read as {@code now() - last_event_at}, from PostgreSQL's clock rather than
     * the JVM's (rule 7) — with several replicas answering this endpoint, a lag that depended on
     * which one replied would be a number nobody could act on.
     */
    @Transactional(readOnly = true)
    public AuditHealth current() {
        List<AuditHealth.PartitionLag> partitions = jdbc.sql("""
                        SELECT topic, partition_no, last_offset,
                               GREATEST(0, EXTRACT(EPOCH FROM (now() - last_event_at))::bigint) AS lag_seconds
                          FROM audit_consumer_state
                         ORDER BY topic, partition_no
                        """)
                .query((rs, n) -> new AuditHealth.PartitionLag(
                        rs.getString("topic"),
                        rs.getInt("partition_no"),
                        rs.getLong("last_offset"),
                        rs.getLong("lag_seconds")))
                .list();

        OffsetDateTime oldest = jdbc.sql("SELECT min(last_event_at) FROM audit_consumer_state")
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);

        // The DLT is a Kafka topic, not a table here. Until the consumer's error handler publishes
        // to it, this is honestly zero rather than a number invented to fill the field.
        return AuditHealth.of(partitions, 0, oldest == null ? null : oldest.toInstant());
    }
}
