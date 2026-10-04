package com.client360.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.client360.audit.TestKeys;
import com.client360.audit.api.AuditQuery;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The self-audit (AT-BR-10), retention (AT-BR-09) and V2's constraints.
 *
 * <p>The property under test in the first group is not that a row appears — it is <em>where</em> it
 * appears. AT-BR-02 says audit entries are created only by consuming Kafka, so a read of the log
 * must leave an outbox row and nothing in {@code audit_log}. A test that only checked "the read was
 * audited" would pass against the implementation the rule forbids.
 */
@SpringBootTest
@Import(SelfAuditAndRetentionTest.Policy.class)
class SelfAuditAndRetentionTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    static {
        POSTGRES.start();
        applyBootstrap();
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AuditSearchService search;

    @Autowired
    private PartitionMaintenance partitions;

    @Autowired
    private AuditEvents events;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=audit,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("client360.outbox.relay.enabled", () -> "false");
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM audit.outbox_events").update();
        jdbc.sql("DELETE FROM audit.audit_partition_archive").update();
    }

    @Nested
    @DisplayName("watching the watchers (AT-BR-10)")
    class SelfAudit {

        /**
         * The round trip, stated as an assertion: a read leaves an outbox row for
         * {@code audit.events} and writes nothing to the log itself. The row reaches
         * {@code audit_log} only by being consumed back, like everyone else's.
         */
        @Test
        void aSearchIsAuditedThroughTheOutboxAndNotByInserting_AT_BR_10() {
            long before = auditRows();
            search.search(
                    new AuditQuery(UUID.randomUUID(), null, null, null, null, null, null, null), null, 50, auditor());

            assertThat(outboxRows("audit.searched")).isEqualTo(1);
            assertThat(topicOf("audit.searched")).isEqualTo("audit.events");
            // Nothing went into the log directly. This is the line that distinguishes the
            // implementation AT-BR-02 permits from the one it forbids.
            assertThat(auditRows()).isEqualTo(before);
        }

        /** Keyed on the actor (§2), so one person's reads stay in order on one partition. */
        @Test
        void isKeyedOnTheActor_2() {
            CurrentUser caller = auditor();
            search.search(
                    new AuditQuery(UUID.randomUUID(), null, null, null, null, null, null, null), null, 50, caller);
            assertThat(partitionKeyOf("audit.searched")).isEqualTo(caller.id().toString());
        }

        /**
         * AT-BR-04 applied to the self-audit: the entry records the filter and how many rows came
         * back, never the rows. An audit of a search must not become a second copy of the log.
         */
        @Test
        void recordsTheFilterAndNotTheResult_AT_BR_04() {
            UUID clientId = UUID.fromString("9b000000-0000-4000-8000-0000000000aa");
            UUID actorId = UUID.fromString("3f000000-0000-4000-8000-0000000000bb");
            insertEntry(clientId, actorId, "anna.kowalska@bank.example");

            search.search(new AuditQuery(clientId, null, null, null, null, null, null, null), null, 50, auditor());

            String payload = payloadOf("audit.searched");
            assertThat(payload).contains("clientId=" + clientId);
            assertThat(payload).contains("\"returnedRows\": 1");
            // The row that came back is not in the entry about the search that returned it.
            assertThat(payload).doesNotContain("anna.kowalska");
        }

        /** A refused read writes no self-audit row: nothing was disclosed, so nothing is recorded. */
        @Test
        void arefusedSearchAuditsNothing_AT_BR_11() {
            assertThatThrownBy(() -> search.search(
                            new AuditQuery(UUID.randomUUID(), null, null, null, null, null, null, null),
                            null,
                            50,
                            manager()))
                    .isInstanceOf(com.client360.common.api.ApiException.class);
            // The denial itself is audited by the service that refused the request (AT-BR-12); what
            // must not appear is a READ_SENSITIVE for a disclosure that never happened.
            assertThat(outboxRows("audit.searched")).isZero();
        }

        /** AT-BR-09's event has no actor, and that is the one place a null actor is honest. */
        @Test
        void aPartitionEventNamesNoActor_AT_BR_09() {
            events.partitionDetached("audit_log_2019_01", "2019-01-01", "2019-02-01", 42);
            assertThat(outboxRows("audit.partition_detached")).isEqualTo(1);
            String payload = payloadOf("audit.partition_detached");
            assertThat(payload).contains("DETACHED_NOT_DROPPED");
            // No request and no token, so no actor object at all.
            assertThat(payload).contains("\"actor\": null");
        }

        /** And the widened V1 constraint accepts it, which is what makes the event storable. */
        @Test
        void theLogAcceptsAnActorlessPartitionEvent_V2() {
            jdbc.sql("""
                            INSERT INTO audit.audit_log
                                (event_id, occurred_at, service, entity_type, entity_id, action,
                                 chain_id, chain_seq, kafka_offset, row_hash)
                            VALUES (gen_random_uuid(), now(), 'audit-service', 'AUDIT_PARTITION',
                                    gen_random_uuid(), 'DELETE', 9, 1, 0, decode(repeat('00', 32), 'hex'))
                            """).update();
            // And still refuses an actorless event from anything else.
            assertThatThrownBy(() -> jdbc.sql("""
                                    INSERT INTO audit.audit_log
                                        (event_id, occurred_at, service, entity_type, entity_id, action,
                                         chain_id, chain_seq, kafka_offset, row_hash)
                                    VALUES (gen_random_uuid(), now(), 'client-service', 'CLIENT',
                                            gen_random_uuid(), 'UPDATE', 9, 2, 0, decode(repeat('00', 32), 'hex'))
                                    """).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_audit_actor_present");
        }
    }

    @Nested
    @DisplayName("partition maintenance (AT-EC-13, AT-BR-09)")
    class Partitions {

        /** §8.2.2: three months ahead, so a missed run is not an ingestion outage. */
        @Test
        void createsThisMonthAndThreeAhead_AT_EC_13() {
            partitions.ensurePartitionsAhead();
            LocalDate month = LocalDate.now().withDayOfMonth(1);
            for (int i = 0; i <= 3; i++) {
                assertThat(partitionExists(month.plusMonths(i))).isTrue();
            }
        }

        /** Idempotent, and it says so by creating nothing the second time. */
        @Test
        void isIdempotent_AT_EC_13() {
            partitions.ensurePartitionsAhead();
            assertThat(partitions.ensurePartitionsAhead()).isEmpty();
        }

        /**
         * Every partition arrives armed. A partition without its immutability trigger is a silent
         * hole in the append-only guarantee, which is why V1 creates both in one function.
         */
        @Test
        void everyCreatedPartitionCarriesItsImmutabilityTrigger_AT_BR_01() {
            partitions.ensurePartitionsAhead();
            LocalDate ahead = LocalDate.now().withDayOfMonth(1).plusMonths(3);
            String name = "audit_log_%d_%02d".formatted(ahead.getYear(), ahead.getMonthValue());
            assertThat(triggerCount(name)).isEqualTo(1);
        }

        /** AT-BR-09: detached, recorded, audited — and the rows are still there afterwards. */
        @Test
        void detachesAnExpiredPartitionWithoutDroppingIt_AT_BR_09() {
            String name = createExpiredPartition();
            assertThat(partitions.detachExpired())
                    .extracting(PartitionMaintenance.Detached::partitionName)
                    .contains(name);

            // Out of the parent...
            assertThat(isPartitionOf(name)).isFalse();
            // ...but still a table, with its row, because DETACH is not DROP.
            assertThat(rowsIn(name)).isEqualTo(1);
            assertThat(archived(name)).isEqualTo(1);
            assertThat(outboxRows("audit.partition_detached")).isEqualTo(1);
        }

        /**
         * A partition straddling the cutoff keeps rows that are still inside retention, so it stays.
         * Getting this wrong deletes evidence that is still required.
         */
        @Test
        void leavesAPartitionWhoseRangeIsNotWhollyExpired_AT_BR_09() {
            partitions.ensurePartitionsAhead();
            assertThat(partitions.detachExpired()).isEmpty();
            assertThat(outboxRows("audit.partition_detached")).isZero();
        }

        /** One event per partition, never one for the run — the same reasoning as AT-EC-11. */
        @Test
        void auditsEachPartitionSeparately_AT_BR_09() {
            String first = createExpiredPartition("2018-01-01");
            String second = createExpiredPartition("2018-02-01");
            assertThat(partitions.detachExpired()).hasSize(2);
            assertThat(outboxRows("audit.partition_detached")).isEqualTo(2);
            assertThat(archived(first)).isEqualTo(1);
            assertThat(archived(second)).isEqualTo(1);
        }

        /** A guard, not a validation: {@code retain_years = 0} would detach the whole log. */
        @Test
        void refusesARetentionWindowUnderOneYear_AT_BR_09() {
            assertThatThrownBy(() -> jdbc.sql("SELECT * FROM audit.detach_expired_partitions(0)")
                            .query(String.class)
                            .list())
                    .hasMessageContaining("retain_years must be at least 1");
        }
    }

    @Nested
    @DisplayName("what V2's columns refuse")
    class Schema {

        @Test
        void anOutboxEventIdIsUnique() {
            UUID eventId = UUID.randomUUID();
            insertOutbox(eventId);
            assertThatThrownBy(() -> insertOutbox(eventId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_outbox_event_id");
        }

        @Test
        void anArchiveRangeMustMoveForwards() {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO audit.audit_partition_archive"
                                    + " (partition_name, range_start, range_end, row_count)"
                                    + " VALUES ('p', DATE '2020-02-01', DATE '2020-01-01', 0)")
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_archive_range");
        }

        /** A storage key without a timestamp, or the reverse, would mean "archived, somewhere". */
        @Test
        void archivedAndItsStorageKeyGoTogether() {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO audit.audit_partition_archive"
                                    + " (partition_name, range_start, range_end, row_count, archived_at)"
                                    + " VALUES ('p', DATE '2020-01-01', DATE '2020-02-01', 0, now())")
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_archive_storage_pair");
        }

        @Test
        void aRowCountCannotBeNegative() {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO audit.audit_partition_archive"
                                    + " (partition_name, range_start, range_end, row_count)"
                                    + " VALUES ('p', DATE '2020-01-01', DATE '2020-02-01', -1)")
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_archive_rows");
        }
    }

    // ------------------------------------------------------------------ helpers

    private String createExpiredPartition() {
        return createExpiredPartition("2017-01-01");
    }

    /**
     * A partition well past retention, with one row in it.
     *
     * <p>Created through the same function the scheduled job uses, so the partition under test is
     * armed exactly as a real one is.
     */
    private String createExpiredPartition(String monthStart) {
        String name = jdbc.sql("SELECT audit.create_audit_partition(CAST(:month AS date))")
                .param("month", monthStart)
                .query(String.class)
                .single();
        jdbc.sql("""
                        INSERT INTO audit.audit_log
                            (event_id, occurred_at, actor_id, service, entity_type, entity_id, action,
                             chain_id, chain_seq, kafka_offset, row_hash)
                        VALUES (gen_random_uuid(), CAST(:month AS date) + INTERVAL '2 days',
                                gen_random_uuid(), 'client-service', 'CLIENT', gen_random_uuid(),
                                'UPDATE', 7, :seq, 0, decode(repeat('00', 32), 'hex'))
                        """)
                .param("month", monthStart)
                .param("seq", Math.abs(monthStart.hashCode() % 1000))
                .update();
        return name;
    }

    private boolean partitionExists(LocalDate month) {
        String name = "audit_log_%d_%02d".formatted(month.getYear(), month.getMonthValue());
        return jdbc.sql("SELECT to_regclass('audit.' || :name) IS NOT NULL")
                .param("name", name)
                .query(Boolean.class)
                .single();
    }

    private boolean isPartitionOf(String name) {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE c.relname = :name AND p.relname = 'audit_log'
                        """).param("name", name).query(Integer.class).single() > 0;
    }

    private int triggerCount(String name) {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_trigger t
                          JOIN pg_class c ON c.oid = t.tgrelid
                         WHERE c.relname = :name AND NOT t.tgisinternal
                        """).param("name", name).query(Integer.class).single();
    }

    private long rowsIn(String name) {
        // The table still exists after DETACH, so it can still be counted — which is the point.
        return jdbc.sql("SELECT count(*) FROM audit." + name).query(Long.class).single();
    }

    private int archived(String name) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_partition_archive WHERE partition_name = :name")
                .param("name", name)
                .query(Integer.class)
                .single();
    }

    private long auditRows() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_log")
                .query(Long.class)
                .single();
    }

    private int outboxRows(String eventType) {
        return jdbc.sql("SELECT count(*) FROM audit.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    private String topicOf(String eventType) {
        return jdbc.sql("SELECT topic FROM audit.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private String partitionKeyOf(String eventType) {
        return jdbc.sql("SELECT partition_key FROM audit.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private String payloadOf(String eventType) {
        return jdbc.sql("SELECT payload::text FROM audit.outbox_events WHERE event_type = :type"
                        + " ORDER BY id DESC LIMIT 1")
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private void insertOutbox(UUID eventId) {
        jdbc.sql("INSERT INTO audit.outbox_events"
                        + " (event_id, aggregate_type, aggregate_id, event_type, topic, partition_key, payload)"
                        + " VALUES (:id, 'AUDIT_LOG', gen_random_uuid(), 'audit.searched', 'audit.events', 'k', '{}')")
                .param("id", eventId)
                .update();
    }

    private void insertEntry(UUID clientId, UUID actorId, String actorEmail) {
        jdbc.sql("""
                        INSERT INTO audit.audit_log
                            (event_id, occurred_at, actor_id, actor_email, service, entity_type, entity_id,
                             client_id, action, chain_id, chain_seq, kafka_offset, row_hash)
                        VALUES (gen_random_uuid(), now(), :actorId, :actorEmail, 'client-service', 'CLIENT',
                                gen_random_uuid(), :clientId, 'UPDATE', 5, 1, 0, decode(repeat('00', 32), 'hex'))
                        """)
                .param("actorId", actorId)
                .param("actorEmail", actorEmail)
                .param("clientId", clientId)
                .update();
    }

    private static CurrentUser auditor() {
        return new CurrentUser(
                UUID.fromString("3f000000-0000-4000-8000-000000000007"),
                "j.audyt@bank.example",
                "Jakub Audyt",
                List.of("AUDITOR"));
    }

    private static CurrentUser manager() {
        return new CurrentUser(UUID.randomUUID(), "mgr@bank.example", "A Manager", List.of("MANAGER"));
    }

    /**
     * The scope under test here is the search service's handling of one, not where it came from, so
     * it is injected. {@code audit:read} at ALL for an auditor, nothing for a manager (AT-BR-11).
     */
    @TestConfiguration
    static class Policy {

        @Bean
        @Primary
        AccessPolicy scopedPolicy() {
            return (user, permission) ->
                    user.roles().contains("AUDITOR") || user.roles().contains("ADMIN")
                            ? Optional.of(Scope.ALL)
                            : Optional.empty();
        }
    }

    private static void applyBootstrap() {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(read("db/init/01_bootstrap.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply db/init/01_bootstrap.sql", e);
        }
    }

    private static String read(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing test resource " + classpathLocation, e);
        }
    }
}
