package com.client360.audit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.client360.audit.TestKeys;
import com.client360.audit.chain.HashChain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Ingestion against the real schema (SPEC.md §8, AT-BR-02/03/07/08).
 *
 * <p>Driven through {@link AuditIngestion#store} rather than through a broker: what is worth testing
 * here is what the service does with an event — the chain it builds, the redelivery it absorbs, the
 * event it refuses — and none of that is Kafka's behaviour. Delivery itself is Spring's.
 */
@SpringBootTest
class AuditIngestionTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    static {
        POSTGRES.start();
        applyBootstrap();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AuditIngestion ingestion;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private com.client360.audit.persistence.AuditRepository audit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=audit,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        // SecurityConfig refuses to start without a verification key, and rightly so.
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @BeforeEach
    void reset() {
        // The log is append-only, so a test cannot delete from it. Each test works on its own chain
        // instead — which is also closer to how the chains actually behave.
        ingestion.forgetHeads();
    }

    @Nested
    @DisplayName("the chain it builds")
    class Chain {

        @Test
        void theFirstEntryOfAChainStartsFromGenesis_8_2_4() {
            int chain = chain();
            ingestion.store(event(), chain, 0, "client.events");

            assertThat(seqOf(chain, 0)).isZero();
            assertThat(prevHashOf(chain, 0)).isEqualTo(HashChain.GENESIS);
        }

        /** Each row's prev_hash is the row before it, which is what makes a deletion detectable. */
        @Test
        void eachEntryLinksToTheOneBefore_8_2_4() {
            int chain = chain();
            ingestion.store(event(), chain, 0, "client.events");
            ingestion.store(event(), chain, 1, "client.events");

            assertThat(prevHashOf(chain, 1)).isEqualTo(rowHashOf(chain, 0));
        }

        /** Sequences are per chain, not global — six partitions run independently (§8.2.4). */
        @Test
        void chainsAreIndependent_8_2_4() {
            int first = chain();
            int second = chain();
            ingestion.store(event(), first, 0, "client.events");
            ingestion.store(event(), second, 0, "client.events");

            assertThat(seqOf(first, 0)).isZero();
            assertThat(seqOf(second, 0)).isZero();
        }

        /**
         * A consumer that has just been assigned a partition must read the real head, not assume an
         * empty chain — appending from zero again would collide with ux_audit_chain and, worse,
         * would mean the chain had been restarted.
         */
        @Test
        void reseedsTheHeadFromTheLogAfterAReassignment() {
            int chain = chain();
            ingestion.store(event(), chain, 0, "client.events");
            byte[] firstHash = rowHashOf(chain, 0);

            ingestion.forgetHeads();
            ingestion.store(event(), chain, 1, "client.events");

            assertThat(seqOf(chain, 1)).isEqualTo(1);
            assertThat(prevHashOf(chain, 1)).isEqualTo(firstHash);
        }
    }

    @Nested
    @DisplayName("what it refuses, and what it absorbs")
    class Refusals {

        /** AT-BR-03: at-least-once delivery becomes exactly-once storage. */
        @Test
        void aRedeliveryIsAbsorbedNotDuplicated_AT_BR_03() {
            int chain = chain();
            JsonNode event = event();

            assertThat(ingestion.store(event, chain, 0, "client.events")).isEqualTo(AuditIngestion.Result.STORED);
            assertThat(ingestion.store(event, chain, 0, "client.events")).isEqualTo(AuditIngestion.Result.DUPLICATE);
            assertThat(countIn(chain)).isEqualTo(1);
        }

        /** And it does not burn a sequence number: the chain has no gap where the repeat was. */
        @Test
        void aRedeliveryLeavesTheChainContiguous_AT_BR_03() {
            int chain = chain();
            JsonNode first = event();
            ingestion.store(first, chain, 0, "client.events");
            ingestion.store(first, chain, 0, "client.events");
            ingestion.store(event(), chain, 1, "client.events");

            assertThat(sequencesIn(chain)).containsExactly(0L, 1L);
        }

        /** AT-EC-04: a producer's clock that far out is not to be trusted, and the DLT decides. */
        @Test
        void aBackDatedEventIsRefusedForTheDlt_AT_EC_04() {
            int chain = chain();
            JsonNode old = eventAt(Instant.now().minus(3, ChronoUnit.HOURS));

            assertThatThrownBy(() -> ingestion.store(old, chain, 0, "client.events"))
                    .isInstanceOf(ClockSkewSuspected.class)
                    .hasMessageContaining("CLOCK_SKEW_SUSPECTED");
            assertThat(countIn(chain)).isZero();
        }

        /**
         * AT-BR-08: an event that cannot be stored stops its partition instead of being stepped
         * over. The throw is what keeps the offset where it is.
         */
        @Test
        void anUnusableEnvelopeIsNotSkipped_AT_BR_08() {
            int chain = chain();
            assertThatThrownBy(() ->
                            ingestion.store(parse("{\"eventType\":\"client.created\"}"), chain, 0, "client.events"))
                    .isInstanceOf(UnprocessableAuditEvent.class);
            assertThat(countIn(chain)).isZero();
        }

        /** Rule 10 and AR-01: the refusal names coordinates, never any of the payload. */
        @Test
        void aRefusalCarriesNoPayload() {
            int chain = chain();
            assertThatThrownBy(() -> ingestion.store(
                            parse("{\"clientId\":\"9b000000-0000-4000-8000-000000000001\"}"),
                            chain,
                            7,
                            "client.events"))
                    .isInstanceOf(UnprocessableAuditEvent.class)
                    .hasMessageContaining("offset 7")
                    .hasMessageNotContaining("9b000000");
        }
    }

    @Nested
    @DisplayName("what it records")
    class Recording {

        /** AT-BR-07: both clocks, so a delayed entry reads as delayed rather than back-dated. */
        @Test
        void keepsBothTheProducersClockAndItsOwn_AT_BR_07() {
            int chain = chain();
            Instant occurredAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
            ingestion.store(eventAt(occurredAt), chain, 0, "client.events");

            assertThat(jdbc.sql("SELECT recorded_at > occurred_at FROM audit_log WHERE chain_id = :c")
                            .param("c", chain)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
        }

        @Test
        void storesTheMaskedChangedFieldsAsGiven_AR_01() {
            int chain = chain();
            ingestion.store(event(), chain, 0, "client.events");

            assertThat(jdbc.sql("SELECT changed_fields->'email'->>'old' FROM audit_log WHERE chain_id = :c")
                            .param("c", chain)
                            .query(String.class)
                            .single())
                    .isEqualTo("***MASKED***");
        }

        /** The health endpoint reads this to report lag per partition (§8.2.6). */
        @Test
        void recordsIngestionProgressPerPartition() {
            int chain = chain();
            ingestion.store(event(), chain, 42, "client.events");

            assertThat(jdbc.sql("SELECT last_offset FROM audit_consumer_state"
                                    + " WHERE topic = 'client.events' AND partition_no = :c")
                            .param("c", chain)
                            .query(Long.class)
                            .single())
                    .isEqualTo(42);
        }

        /**
         * AT-EC-13: an event for a month nobody created a partition for still lands, in a partition
         * that is armed — because {@code create_audit_partition} makes it and arms it or does
         * neither.
         *
         * <p>Driven through the repository rather than the consumer, and with a date in the past.
         * A future date would be the obvious way to guarantee a missing partition, but
         * {@code ck_audit_recorded_after} refuses one — the schema guards the future and the
         * consumer's skew rule guards the past, and between them there is no month without a
         * partition that an event can legitimately reach from the outside. The recovery is real
         * all the same: it is what happens when the scheduled job falls behind.
         */
        @Test
        void createsAMissingPartitionAndStillStores_AT_EC_13() {
            int chain = chain();
            Instant twoMonthsAgo = Instant.now().minus(62, ChronoUnit.DAYS);
            String partition = jdbc.sql("SELECT 'audit_log_' || to_char(CAST(:at AS timestamptz), 'YYYY_MM')")
                    .param("at", twoMonthsAgo.atOffset(java.time.ZoneOffset.UTC))
                    .query(String.class)
                    .single();
            assertThat(partitionExists(partition)).isFalse();

            boolean stored = audit.insert(new com.client360.audit.persistence.AuditRepository.AuditRow(
                    UUID.randomUUID(),
                    chain,
                    0,
                    twoMonthsAgo,
                    UUID.fromString("3f000000-0000-4000-8000-000000000002"),
                    "a.nowak@bank.example",
                    "MANAGER",
                    null,
                    null,
                    "client-service",
                    "CLIENT",
                    UUID.fromString("9b000000-0000-4000-8000-000000000001"),
                    null,
                    "UPDATE",
                    null,
                    null,
                    null,
                    null,
                    0,
                    HashChain.GENESIS,
                    new byte[32]));

            assertThat(stored).isTrue();
            assertThat(partitionExists(partition)).isTrue();
            assertThat(triggerCount(partition)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh chain per test, because an append-only log cannot be truncated between them. */
    private static int nextChain = 100;

    private static synchronized int chain() {
        return nextChain++;
    }

    private JsonNode event() {
        return eventAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private JsonNode eventAt(Instant occurredAt) {
        return parse("""
                {
                  "eventId": "%s",
                  "eventType": "client.updated",
                  "eventVersion": 1,
                  "occurredAt": "%s",
                  "service": "client-service",
                  "actor": { "userId": "3f000000-0000-4000-8000-000000000002",
                             "email": "a.nowak@bank.example", "role": "MANAGER", "ip": "10.4.11.87" },
                  "entity": { "type": "CLIENT", "id": "9b000000-0000-4000-8000-000000000001" },
                  "clientId": "9b000000-0000-4000-8000-000000000001",
                  "requestId": "c4d00000-0000-4000-8000-000000000001",
                  "payload": {
                    "action": "UPDATE",
                    "changedFields": { "segment": { "old": "RETAIL", "new": "PREMIUM" },
                                       "email": { "old": "***MASKED***", "new": "***MASKED***" } }
                  }
                }
                """.formatted(UUID.randomUUID(), occurredAt));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countIn(int chain) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE chain_id = :c")
                .param("c", chain)
                .query(Integer.class)
                .single();
    }

    private List<Long> sequencesIn(int chain) {
        return jdbc.sql("SELECT chain_seq FROM audit_log WHERE chain_id = :c ORDER BY chain_seq")
                .param("c", chain)
                .query(Long.class)
                .list();
    }

    private long seqOf(int chain, long expectedSeq) {
        return jdbc.sql("SELECT chain_seq FROM audit_log WHERE chain_id = :c AND chain_seq = :s")
                .param("c", chain)
                .param("s", expectedSeq)
                .query(Long.class)
                .single();
    }

    private byte[] prevHashOf(int chain, long seq) {
        return jdbc.sql("SELECT prev_hash FROM audit_log WHERE chain_id = :c AND chain_seq = :s")
                .param("c", chain)
                .param("s", seq)
                .query(byte[].class)
                .single();
    }

    private byte[] rowHashOf(int chain, long seq) {
        return jdbc.sql("SELECT row_hash FROM audit_log WHERE chain_id = :c AND chain_seq = :s")
                .param("c", chain)
                .param("s", seq)
                .query(byte[].class)
                .single();
    }

    private boolean partitionExists(String partition) {
        return jdbc.sql("SELECT to_regclass('audit.' || :partition) IS NOT NULL")
                .param("partition", partition)
                .query(Boolean.class)
                .single();
    }

    private int triggerCount(String partition) {
        return jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                        + " WHERE c.relname = :partition AND NOT t.tgisinternal")
                .param("partition", partition)
                .query(Integer.class)
                .single();
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
