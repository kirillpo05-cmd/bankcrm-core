package com.client360.audit.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.client360.audit.TestKeys;
import com.client360.audit.ingest.AuditIngestion;
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
import java.util.UUID;
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
 * §8.3 {@code POST /audit/verify} (AT-US-03, AT-BR-05).
 *
 * <p>Tampering is done with the immutability trigger disabled, which is not cheating: the trigger
 * and the revoked privileges stop an application and an operator, and the hash chain is the layer
 * that still works once somebody has enough access to get past both. Testing detection against an
 * attacker who could not have edited the row in the first place would prove nothing.
 */
@SpringBootTest
class ChainVerifierTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    static {
        POSTGRES.start();
        applyBootstrap();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static int nextChain = 500;

    @Autowired
    private AuditIngestion ingestion;

    @Autowired
    private ChainVerifier verifier;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=audit,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        // SecurityConfig refuses to start without a verification key, and rightly so.
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @Nested
    @DisplayName("an intact chain")
    class Intact {

        @Test
        void verifies_AT_BR_05() {
            int chain = seed(5);
            ChainVerifier.VerificationReport report = verifier.verify(chain, null, null, null, null);

            assertThat(report.verified()).isTrue();
            assertThat(report.breaks()).isEmpty();
            assertThat(report.rowsChecked()).isEqualTo(5);
            assertThat(report.externalHeadMatch()).isTrue();
        }

        @Test
        void reportsTheHeadItEndedOn() {
            int chain = seed(3);
            ChainVerifier.VerificationReport report = verifier.verify(chain, null, null, null, null);

            assertThat(report.chains()).singleElement().satisfies(result -> {
                assertThat(result.fromSeq()).isZero();
                assertThat(result.toSeq()).isEqualTo(2);
                assertThat(result.headHash()).hasSize(64);
            });
        }

        /** An empty chain is not a broken one; verification of nothing succeeds. */
        @Test
        void anEmptyRangeVerifies() {
            assertThat(verifier.verify(9999, null, null, null, null).verified()).isTrue();
        }
    }

    @Nested
    @DisplayName("what it catches")
    class Detection {

        /** Somebody edited a row and did not recompute its hash. */
        @Test
        void anEditedRow_AT_BR_05() {
            int chain = seed(5);
            withTriggersOff(
                    () -> jdbc.sql("UPDATE audit_log SET action = 'DELETE'" + " WHERE chain_id = :c AND chain_seq = 2")
                            .param("c", chain)
                            .update());

            ChainVerifier.VerificationReport report = verifier.verify(chain, null, null, null, null);

            assertThat(report.verified()).isFalse();
            assertThat(report.breaks()).singleElement().satisfies(b -> {
                assertThat(b.chainSeq()).isEqualTo(2);
                assertThat(b.diagnosis()).isEqualTo("ROW_MODIFIED_OR_DELETED");
                assertThat(b.expectedHash()).isNotEqualTo(b.actualHash());
            });
        }

        /**
         * The case a per-row checksum could never catch, and the reason each hash covers the one
         * before it: a row removed entirely leaves the rows around it individually valid.
         */
        @Test
        void aDeletedRowBreaksTheLink_AT_BR_05() {
            int chain = seed(5);
            withTriggersOff(() -> jdbc.sql("DELETE FROM audit_log WHERE chain_id = :c AND chain_seq = 2")
                    .param("c", chain)
                    .update());

            ChainVerifier.VerificationReport report = verifier.verify(chain, null, null, null, null);

            assertThat(report.verified()).isFalse();
            assertThat(report.breaks()).anySatisfy(b -> {
                assertThat(b.diagnosis()).isEqualTo("CHAIN_BROKEN");
                assertThat(b.chainSeq()).isEqualTo(3);
            });
            assertThat(report.rowsChecked()).isEqualTo(4);
        }

        /**
         * AT-BR-06: a chain rewritten and recomputed end to end is internally perfect. Only a head
         * sealed elsewhere catches it — which is why heads are mirrored off-box nightly, and why
         * local recomputation alone must not defeat detection.
         */
        @Test
        void aFullyRecomputedChainIsCaughtBySealedHead_AT_BR_06() {
            int chain = seed(3);
            // Seal the head as the ingestion path does every 1 000 rows.
            byte[] head = jdbc.sql("SELECT row_hash FROM audit_log WHERE chain_id = :c AND chain_seq = 2")
                    .param("c", chain)
                    .query(byte[].class)
                    .single();
            jdbc.sql("INSERT INTO audit_chain_head (chain_id, chain_seq, head_hash) VALUES (:c, 2, :h)")
                    .param("c", chain)
                    .param("h", head)
                    .update();

            // An attacker rewrites the last row's hash so the chain agrees with itself again.
            withTriggersOff(
                    () -> jdbc.sql("UPDATE audit_log SET row_hash = :h" + " WHERE chain_id = :c AND chain_seq = 2")
                            .param("c", chain)
                            .param("h", new byte[32])
                            .update());

            ChainVerifier.VerificationReport report = verifier.verify(chain, null, null, null, null);

            assertThat(report.externalHeadMatch()).isFalse();
            assertThat(report.breaks())
                    .anySatisfy(b -> assertThat(b.diagnosis()).isEqualTo("SEALED_HEAD_MISMATCH"));
        }

        /** A young chain has nothing sealed yet, and saying so would be crying wolf. */
        @Test
        void anUnsealedChainIsNotReportedAsMismatched_AT_BR_06() {
            int chain = seed(3);
            assertThat(verifier.verify(chain, null, null, null, null).externalHeadMatch())
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Simulates an attacker who already holds enough privilege to get past the append-only
     * guarantees. That is the threat model the chain answers; the trigger answers the other one.
     */
    private void withTriggersOff(Runnable tampering) {
        jdbc.sql("SET session_replication_role = replica").update();
        try {
            tampering.run();
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }
    }

    private synchronized int seed(int rows) {
        int chain = nextChain++;
        ingestion.forgetHeads();
        for (int i = 0; i < rows; i++) {
            ingestion.store(event(), chain, i, "client.events");
        }
        return chain;
    }

    private JsonNode event() {
        try {
            return JSON.readTree("""
                    {
                      "eventId": "%s",
                      "eventType": "client.updated",
                      "occurredAt": "%s",
                      "service": "client-service",
                      "actor": { "userId": "3f000000-0000-4000-8000-000000000002" },
                      "entity": { "type": "CLIENT", "id": "9b000000-0000-4000-8000-000000000001" },
                      "clientId": "9b000000-0000-4000-8000-000000000001",
                      "payload": { "action": "UPDATE",
                                   "changedFields": { "segment": { "old": "RETAIL", "new": "PREMIUM" } } }
                    }
                    """.formatted(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
