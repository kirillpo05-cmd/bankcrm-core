package com.client360.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.client360.audit.TestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * §8.2.4, pinned byte for byte.
 *
 * <p>A hash chain whose serialization can drift is worse than none: it reports tampering when a
 * library is upgraded, and after the first false alarm nobody believes the next one. The formula in
 * §8.2.4 is written in SQL, so verification could reasonably be run either in Java or in the
 * database — which means the two have to agree exactly. These tests check that against a real
 * PostgreSQL rather than assuming it.
 */
@SpringBootTest
class HashChainTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("client360");

    static {
        POSTGRES.start();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        // Self-audit writes outbox rows (AT-BR-10) and the relay would spend the suite
        // failing to reach a broker. Events are asserted where rule 3 puts them — in
        // outbox_events, in the same transaction as the read — and publishing is the
        // relay's own concern.
        registry.add("client360.outbox.relay.enabled", () -> "false");
        // This service refuses to start without export credentials, which is right for a
        // deployment and noise for a test that never exports. Placeholders, so the S3 client
        // builds; AuditExportIntegrationTest points them at a real LocalStack instead.
        registry.add("client360.audit.export.access-key", () -> "test");
        registry.add("client360.audit.export.secret-key", () -> "test");
        // SecurityConfig refuses to start without a verification key, and rightly so.
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @Nested
    @DisplayName("the canonical timestamp")
    class Timestamp {

        /**
         * {@code ISO_INSTANT} would render this as {@code 2026-09-09T11:42:07.113Z} — a trimmed
         * fraction and a {@code Z} instead of an offset. Either difference changes every hash in
         * every chain, so the format is compared against the database that defines it.
         */
        @Test
        void matchesPostgresToChar_8_2_4() {
            assertCanonical(Instant.parse("2026-09-09T11:42:07.113Z"));
        }

        @Test
        void keepsAllSixFractionalDigits() {
            assertCanonical(Instant.parse("2026-09-09T11:42:07.000001Z"));
        }

        @Test
        void padsAWholeSecond() {
            assertCanonical(Instant.parse("2026-01-05T00:00:00Z"));
            assertThat(HashChain.canonicalTimestamp(Instant.parse("2026-01-05T00:00:00Z")))
                    .isEqualTo("2026-01-05T00:00:00.000000+00");
        }

        private void assertCanonical(Instant at) {
            String fromPostgres = jdbc.sql("SELECT to_char(CAST(:ts AS timestamptz) AT TIME ZONE 'UTC',"
                            + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US') || '+00'")
                    .param("ts", at.toString())
                    .query(String.class)
                    .single();
            assertThat(HashChain.canonicalTimestamp(at)).isEqualTo(fromPostgres);
        }
    }

    @Nested
    @DisplayName("canonical JSON")
    class Canonical {

        /** Jackson keeps insertion order; without sorting the same content hashes two ways. */
        @Test
        void sortsKeysSoContentDecidesTheHash() throws Exception {
            JsonNode one = JSON.readTree("{\"segment\":{\"old\":\"RETAIL\"},\"email\":{\"old\":\"***MASKED***\"}}");
            JsonNode other = JSON.readTree("{\"email\":{\"old\":\"***MASKED***\"},\"segment\":{\"old\":\"RETAIL\"}}");
            assertThat(HashChain.canonicalJson(one)).isEqualTo(HashChain.canonicalJson(other));
        }

        @Test
        void emitsNoWhitespace() throws Exception {
            assertThat(HashChain.canonicalJson(JSON.readTree("{ \"a\" : 1 , \"b\" : [ 2, 3 ] }")))
                    .isEqualTo("{\"a\":1,\"b\":[2,3]}");
        }

        /** Array order is a statement about what happened, so it is content and stays put. */
        @Test
        void doesNotReorderArrays() throws Exception {
            assertThat(HashChain.canonicalJson(JSON.readTree("{\"skipped\":[\"b\",\"a\"]}")))
                    .isEqualTo("{\"skipped\":[\"b\",\"a\"]}");
        }

        @Test
        void escapesWhatJsonRequires() throws Exception {
            assertThat(HashChain.canonicalJson(JSON.readTree("{\"a\":\"say \\\"hi\\\"\\n\"}")))
                    .isEqualTo("{\"a\":\"say \\\"hi\\\"\\n\"}");
        }

        @Test
        void anAbsentChangedFieldsHashesAsEmpty() {
            assertThat(HashChain.canonicalJson(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the chain itself")
    class Chain {

        @Test
        void aRowHashIsThirtyTwoBytes() {
            assertThat(hash(HashChain.GENESIS, "{\"a\":1}")).hasSize(32);
        }

        /** The property the whole design rests on: the hash covers the one before it. */
        @Test
        void changingThePreviousHashChangesThisOne_8_2_4() {
            byte[] other = new byte[32];
            other[0] = 1;
            assertThat(hash(HashChain.GENESIS, "{\"a\":1}")).isNotEqualTo(hash(other, "{\"a\":1}"));
        }

        @Test
        void changingTheContentChangesTheHash() {
            assertThat(hash(HashChain.GENESIS, "{\"a\":1}")).isNotEqualTo(hash(HashChain.GENESIS, "{\"a\":2}"));
        }

        /** Same inputs, same hash — otherwise verification could never pass twice. */
        @Test
        void isDeterministic() {
            assertThat(HexFormat.of().formatHex(hash(HashChain.GENESIS, "{\"a\":1}")))
                    .isEqualTo(HexFormat.of().formatHex(hash(HashChain.GENESIS, "{\"a\":1}")));
        }

        /**
         * A whole chain: breaking one link breaks every link after it, which is what makes deleting
         * a row detectable rather than merely rude.
         */
        @Test
        void tamperingWithOneRowInvalidatesEveryLaterRow_AT_BR_01() {
            byte[] first = hash(HashChain.GENESIS, "{\"a\":1}");
            byte[] second = hash(first, "{\"a\":2}");
            byte[] third = hash(second, "{\"a\":3}");

            // Someone edits the first row's content and recomputes only its own hash.
            byte[] tamperedFirst = hash(HashChain.GENESIS, "{\"a\":99}");
            byte[] recomputedSecond = hash(tamperedFirst, "{\"a\":2}");

            assertThat(recomputedSecond).isNotEqualTo(second);
            assertThat(hash(recomputedSecond, "{\"a\":3}")).isNotEqualTo(third);
        }

        private byte[] hash(byte[] prev, String changedFields) {
            try {
                return HashChain.rowHash(
                        prev,
                        UUID.fromString("0199a4c2-6f1e-7c3b-9a10-2f8c4d1e5b77"),
                        Instant.parse("2026-09-09T11:42:07.113Z"),
                        UUID.fromString("3f000000-0000-4000-8000-000000000002"),
                        "CLIENT",
                        UUID.fromString("9b000000-0000-4000-8000-000000000001"),
                        "UPDATE",
                        JSON.readTree(changedFields));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The whole formula, computed independently in SQL. If the two ever disagree, one of them is
     * what an auditor would be shown — and this is the test that says which.
     */
    @Test
    void theWholeHashMatchesTheSameFormulaInSql_8_2_4() throws Exception {
        UUID eventId = UUID.fromString("0199a4c2-6f1e-7c3b-9a10-2f8c4d1e5b77");
        UUID actorId = UUID.fromString("3f000000-0000-4000-8000-000000000002");
        UUID entityId = UUID.fromString("9b000000-0000-4000-8000-000000000001");
        Instant occurredAt = Instant.parse("2026-09-09T11:42:07.113Z");
        String canonicalFields = "{\"email\":{\"old\":\"***MASKED***\"},\"segment\":{\"new\":\"PREMIUM\"}}";

        byte[] fromJava = HashChain.rowHash(
                HashChain.GENESIS,
                eventId,
                occurredAt,
                actorId,
                "CLIENT",
                entityId,
                "UPDATE",
                JSON.readTree("{\"segment\":{\"new\":\"PREMIUM\"},\"email\":{\"old\":\"***MASKED***\"}}"));

        String fromSql = jdbc.sql("""
                        SELECT encode(sha256(
                              decode(repeat('00', 32), 'hex')
                           || decode(replace(CAST(:eventId AS text), '-', ''), 'hex')
                           || convert_to(to_char(CAST(:occurredAt AS timestamptz) AT TIME ZONE 'UTC',
                                                 'YYYY-MM-DD"T"HH24:MI:SS.US') || '+00', 'UTF8')
                           || convert_to(CAST(:actorId AS text), 'UTF8')
                           || convert_to('CLIENT', 'UTF8')
                           || convert_to(CAST(:entityId AS text), 'UTF8')
                           || convert_to('UPDATE', 'UTF8')
                           || convert_to(:changedFields, 'UTF8')
                        ), 'hex')
                        """)
                .param("eventId", eventId.toString())
                .param("occurredAt", occurredAt.toString())
                .param("actorId", actorId.toString())
                .param("entityId", entityId.toString())
                .param("changedFields", canonicalFields)
                .query(String.class)
                .single();

        assertThat(HexFormat.of().formatHex(fromJava)).isEqualTo(fromSql);
    }
}
