package com.client360.audit.service;

import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the log's storage ahead of the writes and behind the retention window (SPEC.md §8.2.2,
 * AT-BR-09, AT-EC-13).
 *
 * <p>Two jobs that look symmetrical and are not. Creating partitions ahead of time is routine and
 * idempotent; detaching expired ones removes evidence, so it is deliberately the more careful of
 * the two: it detaches rather than drops, records what it did in {@code audit_partition_archive},
 * and audits each partition by name.
 */
@Component
public class PartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);

    /**
     * §8.2.2: three months ahead. One month would be enough until the job missed a run, and a
     * missing partition is an ingestion outage — {@code AuditRepository} creates one on demand to
     * survive that (AT-EC-13), but a service recovering from its own gap is not a plan.
     */
    private static final int MONTHS_AHEAD = 3;

    private final JdbcClient jdbc;
    private final AuditEvents events;
    private final int retainYears;

    public PartitionMaintenance(
            JdbcClient jdbc, AuditEvents events, @Value("${client360.audit.retain-years:7}") int retainYears) {
        this.jdbc = jdbc;
        this.events = events;
        this.retainYears = retainYears;
    }

    /**
     * Creates this month and the next three if they do not exist. Idempotent, and each partition
     * arrives with its immutability trigger in the same transaction as its creation — a partition
     * without one is a silent hole in the append-only guarantee (AT-EC-13).
     *
     * @return the partitions this run created, for the test and the log line
     */
    @Scheduled(cron = "${client360.audit.partition-cron:0 15 3 * * *}")
    @Transactional
    public List<String> ensurePartitionsAhead() {
        List<String> created = new java.util.ArrayList<>();
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            LocalDate target = month.plusMonths(i);
            if (createIfAbsent(target)) {
                created.add(partitionName(target));
            }
        }
        if (!created.isEmpty()) {
            log.info("created audit partitions {}", created);
            created.forEach(events::partitionCreated);
        }
        return created;
    }

    /**
     * AT-BR-09: detaches every partition whose whole range is past the retention window, and audits
     * each one.
     *
     * <p>The event is emitted per partition rather than once for the run, because "which seven-year
     * window left the log, and when" is the question a regulator asks, and one event for a batch
     * cannot answer it (the same reasoning as AT-EC-11 for bulk operations).
     *
     * <p>Monthly rather than daily: there is nothing to do on 30 days out of 31, and a job that
     * removes evidence should run as rarely as its purpose allows.
     */
    @Scheduled(cron = "${client360.audit.retention-cron:0 45 3 1 * *}")
    @Transactional
    public List<Detached> detachExpired() {
        List<Detached> detached = jdbc.sql("SELECT partition_name, range_start, range_end, row_count"
                        + "  FROM audit.detach_expired_partitions(:retainYears)")
                .param("retainYears", retainYears)
                .query((rs, n) -> new Detached(
                        rs.getString("partition_name"),
                        rs.getString("range_start"),
                        rs.getString("range_end"),
                        rs.getLong("row_count")))
                .list();
        for (Detached partition : detached) {
            // Loud, because this is the one operation that takes rows out of the log. An operator
            // reading the logs should see it without going looking.
            log.warn(
                    "detached expired audit partition {} ({} to {}, {} rows); archival pending",
                    partition.partitionName(),
                    partition.rangeStart(),
                    partition.rangeEnd(),
                    partition.rowCount());
            events.partitionDetached(
                    partition.partitionName(), partition.rangeStart(), partition.rangeEnd(), partition.rowCount());
        }
        return detached;
    }

    private boolean createIfAbsent(LocalDate month) {
        // The function returns the name whether it created the partition or found it, so existence
        // is checked separately rather than inferred from the return value.
        boolean existed = jdbc.sql("SELECT to_regclass('audit.' || :name) IS NOT NULL")
                .param("name", partitionName(month))
                .query(Boolean.class)
                .single();
        if (existed) {
            return false;
        }
        jdbc.sql("SELECT audit.create_audit_partition(CAST(:month AS date))")
                .param("month", month.toString())
                .query(String.class)
                .single();
        return true;
    }

    private static String partitionName(LocalDate month) {
        return "audit_log_%d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    /** @param rowCount what left the parent table, recorded before the detach so it is the truth */
    public record Detached(String partitionName, String rangeStart, String rangeEnd, long rowCount) {}
}
