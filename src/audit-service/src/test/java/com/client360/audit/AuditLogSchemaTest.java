package com.client360.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V1, against a real PostgreSQL 16 — the only way to test what this schema is actually for.
 *
 * <p>The append-only guarantee (AT-BR-01) is made of a revoked privilege and a trigger, and neither
 * exists in an in-memory database. Partition routing, the inherited-trigger problem and the
 * unique-index-must-include-the-partition-key rule are all PostgreSQL behaviour too. So every
 * assertion here is about what the database refuses.
 */
@SpringBootTest
class AuditLogSchemaTest {

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=audit,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        // Self-audit writes outbox rows (AT-BR-10) and the relay would spend the suite
        // failing to reach a broker. Events are asserted where rule 3 puts them — in
        // outbox_events, in the same transaction as the read — and publishing is the
        // relay's own concern.
        registry.add("client360.outbox.relay.enabled", () -> "false");
        // SecurityConfig refuses to start without a verification key, and rightly so.
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @Nested
    @DisplayName("append-only (AT-BR-01)")
    class AppendOnly {

        @Test
        void aRowCannotBeUpdated() {
            insert(action("UPDATE"));
            assertThatThrownBy(() -> jdbc.sql("UPDATE audit.audit_log SET action = 'CREATE'")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
        }

        @Test
        void aRowCannotBeDeleted() {
            insert(action("CREATE"));
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.audit_log").update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
        }

        /** TRUNCATE is not a DELETE and would slip past a row-level guard entirely. */
        @Test
        void aPartitionCannotBeTruncated() {
            insert(action("CREATE"));
            String partition = currentPartition();
            assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit." + partition).update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
        }

        /**
         * The reason {@code create_audit_partition()} exists. A statement-level trigger on the
         * partitioned parent is not inherited, so a partition created by hand would accept the
         * updates the log is supposed to refuse — a silent hole rather than a visible one
         * (AT-EC-13).
         */
        @Test
        void everyPartitionCarriesItsOwnTrigger_AT_EC_13() {
            jdbc.sql("SELECT audit.create_audit_partition(DATE '2027-03-01')")
                    .query(String.class)
                    .single();
            assertThat(triggerCount("audit_log_2027_03")).isEqualTo(1);
            assertThat(triggerCount(currentPartition())).isEqualTo(1);
        }

        @Test
        void creatingTheSamePartitionTwiceIsHarmless() {
            jdbc.sql("SELECT audit.create_audit_partition(DATE '2027-04-01')")
                    .query(String.class)
                    .single();
            jdbc.sql("SELECT audit.create_audit_partition(DATE '2027-04-01')")
                    .query(String.class)
                    .single();
            assertThat(triggerCount("audit_log_2027_04")).isEqualTo(1);
        }

        /** The chain head is evidence too, and sealed heads are what external mirroring compares. */
        @Test
        void aSealedChainHeadCannotBeChanged_AT_BR_06() {
            jdbc.sql("INSERT INTO audit.audit_chain_head (chain_id, chain_seq, head_hash)" + " VALUES (0, 1000, :hash)")
                    .param("hash", new byte[32])
                    .update();
            assertThatThrownBy(() -> jdbc.sql("UPDATE audit.audit_chain_head SET chain_seq = 1")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("append-only");
        }

        /**
         * Consumer state is bookkeeping about the pipeline, not evidence about anything that
         * happened, so it is deliberately mutable — and the distinction is worth pinning, because
         * arming it with the same trigger would stop ingestion dead on the second batch.
         */
        @Test
        void consumerStateIsMutableOnPurpose() {
            jdbc.sql("INSERT INTO audit.audit_consumer_state (topic, partition_no, last_offset, last_event_at)"
                            + " VALUES ('client.events', 0, 10, now())")
                    .update();
            int updated = jdbc.sql("UPDATE audit.audit_consumer_state SET last_offset = 11"
                            + " WHERE topic = 'client.events' AND partition_no = 0")
                    .update();
            assertThat(updated).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("what the columns refuse")
    class Constraints {

        /**
         * The constraint guards the future direction: a row may not claim to have been recorded
         * more than an hour before it happened.
         */
        @Test
        void aFutureDatedEventIsRefused() {
            assertThatThrownBy(() -> insert(
                            row(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(7200), "CREATE", UUID.randomUUID())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_audit_recorded_after");
        }

        /**
         * And it deliberately does <em>not</em> guard the other one. AT-EC-04 rejects a back-dated
         * event, but that is the consumer's rule — it goes to the DLT as
         * {@code CLOCK_SKEW_SUSPECTED}. A CHECK here would also block the operator-approved
         * backfill after a long outage, which legitimately inserts old events. Pinned so nobody
         * "tightens" the constraint and breaks recovery.
         */
        @Test
        void aBackDatedEventIsTheConsumersToRefuseNotTheSchemas_AT_EC_04() {
            insert(row(UUID.randomUUID(), 0, 5, Instant.now().minusSeconds(7200), "CREATE", UUID.randomUUID()));
        }

        /** Every action but a failed login has an authenticated actor to name. */
        @Test
        void onlyAFailedLoginMayHaveNoActor() {
            assertThatThrownBy(() -> insert(row(UUID.randomUUID(), 0, 0, Instant.now(), "CREATE", null)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_audit_actor_present");

            insert(row(UUID.randomUUID(), 0, 0, Instant.now(), "LOGIN_FAILURE", null));
        }

        /**
         * AT-BR-03: redelivery collides here, which is what makes storage exactly-once.
         *
         * <p>Asserted on the key columns rather than on {@code ux_audit_event}. A unique index
         * declared on a partitioned parent is implemented as one index per partition, each with a
         * generated name, and the violation reports the partition's name
         * ({@code audit_log_2026_09_occurred_at_event_id_idx}). So the naming convention of
         * {@code db-migrations.md} — name everything, because an anonymous constraint is an
         * unusable production error — cannot hold for these two indexes, and the consumer has to
         * recognise the collision by its columns.
         */
        @Test
        void thesameEventIdCannotBeStoredTwice_AT_BR_03() {
            UUID eventId = UUID.randomUUID();
            Instant at = Instant.now();
            insert(row(eventId, 0, 0, at, "CREATE", UUID.randomUUID()));
            assertThatThrownBy(() -> insert(row(eventId, 0, 1, at, "CREATE", UUID.randomUUID())))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasStackTraceContaining("(occurred_at, event_id)");
        }

        /** One chain per partition, strictly ordered: a repeated sequence number is a broken chain. */
        @Test
        void aChainSequenceCannotRepeat() {
            Instant at = Instant.now();
            insert(row(UUID.randomUUID(), 3, 7, at, "CREATE", UUID.randomUUID()));
            assertThatThrownBy(() -> insert(row(UUID.randomUUID(), 3, 7, at, "CREATE", UUID.randomUUID())))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasStackTraceContaining("(occurred_at, chain_id, chain_seq)");
        }

        /** A hash that is not 32 bytes is not a SHA-256, and a chain of them proves nothing. */
        @Test
        void aMalformedHashIsRefused() {
            assertThatThrownBy(() -> jdbc.sql(INSERT)
                            .param("eventId", UUID.randomUUID())
                            .param("chainId", 0)
                            .param("chainSeq", 0)
                            .param("occurredAt", OffsetDateTime.now(ZoneOffset.UTC))
                            .param("actorId", UUID.randomUUID())
                            .param("action", "CREATE")
                            .param("entityId", UUID.randomUUID())
                            .param("rowHash", new byte[16])
                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("ck_audit_hash_length");
        }
    }

    /** The privilege layer, which the trigger is only the second half of (§8.2.3). */
    @Test
    void theApplicationRoleHoldsNoUpdateOrDeleteOnTheLog_AT_BR_01() {
        assertThat(privilege("UPDATE")).isFalse();
        assertThat(privilege("DELETE")).isFalse();
        assertThat(privilege("TRUNCATE")).isFalse();
        assertThat(privilege("INSERT")).isTrue();
        assertThat(privilege("SELECT")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static final String INSERT = """
            INSERT INTO audit.audit_log
                (event_id, chain_id, chain_seq, occurred_at, service, entity_type, entity_id,
                 actor_id, action, kafka_offset, row_hash)
            VALUES (:eventId, :chainId, :chainSeq, :occurredAt, 'client-service', 'CLIENT', :entityId,
                    :actorId, CAST(:action AS audit.audit_action), 0, :rowHash)
            """;

    private record Row(UUID eventId, int chainId, long chainSeq, Instant occurredAt, String action, UUID actorId) {}

    private static Row row(UUID eventId, int chainId, long chainSeq, Instant occurredAt, String action, UUID actorId) {
        return new Row(eventId, chainId, chainSeq, occurredAt, action, actorId);
    }

    private static Row action(String action) {
        return row(UUID.randomUUID(), 0, System.nanoTime() % 1_000_000, Instant.now(), action, UUID.randomUUID());
    }

    private void insert(Row r) {
        jdbc.sql(INSERT)
                .param("eventId", r.eventId())
                .param("chainId", r.chainId())
                .param("chainSeq", r.chainSeq())
                .param("occurredAt", r.occurredAt().atOffset(ZoneOffset.UTC))
                .param("actorId", r.actorId())
                .param("action", r.action())
                .param("entityId", UUID.randomUUID())
                .param("rowHash", new byte[32])
                .update();
    }

    private String currentPartition() {
        return jdbc.sql("SELECT 'audit_log_' || to_char(now(), 'YYYY_MM')")
                .query(String.class)
                .single();
    }

    private int triggerCount(String partition) {
        return jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                        + " WHERE c.relname = :partition AND NOT t.tgisinternal")
                .param("partition", partition)
                .query(Integer.class)
                .single();
    }

    private boolean privilege(String kind) {
        return jdbc.sql("SELECT has_table_privilege('client360_app', 'audit.audit_log', :kind)")
                .param("kind", kind)
                .query(Boolean.class)
                .single();
    }

    private static void applyBootstrap() {
        String sql = read("db/init/01_bootstrap.sql");
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
