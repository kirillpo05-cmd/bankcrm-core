package com.client360.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.client360.audit.TestKeys;
import com.client360.audit.api.AuditEntryView;
import com.client360.audit.api.AuditQuery;
import com.client360.audit.ingest.AuditIngestion;
import com.client360.common.api.ApiException;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.web.KeysetPage;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@code GET /audit} (SPEC.md §8.3, AT-US-01/02/05, AT-BR-11, AT-EC-14).
 *
 * <p>The refusals matter as much as the results here. An audit search that quietly returns rows
 * nobody scope-checked, or that walks seven years because a filter was left empty, is worse than
 * one that says no.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(AuditSearchServiceTest.Policy.class)
class AuditSearchServiceTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    static {
        POSTGRES.start();
        applyBootstrap();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static int nextChain = 800;

    /** Flipped by a test to change what the doubled policy answers. */
    static volatile Scope scope = Scope.ALL;

    @Autowired
    private AuditSearchService search;

    @Autowired
    private AuditIngestion ingestion;

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
        // This service refuses to start without export credentials, which is right for a
        // deployment and noise for a test that never exports. Placeholders, so the S3 client
        // builds; AuditExportIntegrationTest points them at a real LocalStack instead.
        registry.add("client360.audit.export.access-key", () -> "test");
        registry.add("client360.audit.export.secret-key", () -> "test");
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
    }

    @Nested
    @DisplayName("what it finds")
    class Finding {

        @Test
        void findsEverythingThatHappenedToOneClient_AT_US_01() {
            UUID client = UUID.randomUUID();
            seed(client, 3);

            KeysetPage<AuditEntryView> page = search.search(byClient(client), null, null, auditor());

            assertThat(page.content()).hasSize(3);
            assertThat(page.content())
                    .allSatisfy(row -> assertThat(row.clientId()).isEqualTo(client));
        }

        /** Newest first: an investigation starts from what just happened. */
        @Test
        void returnsNewestFirst() {
            UUID client = UUID.randomUUID();
            seed(client, 3);

            List<AuditEntryView> rows =
                    search.search(byClient(client), null, null, auditor()).content();

            assertThat(rows.get(0).occurredAt()).isAfterOrEqualTo(rows.get(1).occurredAt());
        }

        /** AT-BR-07: the reader sees how far behind the pipeline was, not just when it happened. */
        @Test
        void reportsPipelineLagPerRow_AT_BR_07() {
            UUID client = UUID.randomUUID();
            seed(client, 1);

            assertThat(search.search(byClient(client), null, null, auditor())
                            .content()
                            .getFirst()
                            .lagMs())
                    .isGreaterThanOrEqualTo(0);
        }

        /** AT-BR-04: what changed, never what it became for anything sensitive. */
        @Test
        void carriesOnlyMaskedValues_AR_01() {
            UUID client = UUID.randomUUID();
            seed(client, 1);

            JsonNode changed = search.search(byClient(client), null, null, auditor())
                    .content()
                    .getFirst()
                    .changedFields();
            assertThat(changed.path("email").path("old").asText()).isEqualTo("***MASKED***");
        }

        @Test
        void pagesByKeyset_4_5() {
            UUID client = UUID.randomUUID();
            seed(client, 3);

            KeysetPage<AuditEntryView> first = search.search(byClient(client), null, 2, auditor());
            assertThat(first.content()).hasSize(2);
            assertThat(first.hasMore()).isTrue();

            KeysetPage<AuditEntryView> second = search.search(byClient(client), first.nextCursor(), 2, auditor());
            assertThat(second.content()).hasSize(1);
            assertThat(second.hasMore()).isFalse();
        }

        @Test
        void filtersByAction() {
            UUID client = UUID.randomUUID();
            seed(client, 2);

            assertThat(search.search(
                                    new AuditQuery(client, null, null, null, List.of("DELETE"), null, null, null),
                                    null,
                                    null,
                                    auditor())
                            .content())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusing {

        /** AT-EC-14: an empty filter is a seven-year scan, and it is named as such. */
        @Test
        void refusesAnUnfilteredSearch_AT_EC_14() {
            assertThatThrownBy(() -> search.search(
                            new AuditQuery(null, null, null, null, null, null, null, null), null, null, auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("validation");
        }

        /** A range alone is fine, but only a narrow one — "the last decade" is still a scan. */
        @Test
        void refusesATimeRangeWiderThanNinetyDays_AT_EC_14() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> search.search(
                            new AuditQuery(null, null, null, null, null, null, now.minus(200, ChronoUnit.DAYS), now),
                            null,
                            null,
                            auditor()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        void acceptsANarrowTimeRangeOnItsOwn_AT_EC_14() {
            Instant now = Instant.now();
            assertThat(search.search(
                            new AuditQuery(null, null, null, null, null, null, now.minus(7, ChronoUnit.DAYS), now),
                            null,
                            null,
                            auditor()))
                    .isNotNull();
        }

        /** AT-BR-11: a manager holds audit:read at no scope, and that alone is the refusal. */
        @Test
        void refusesACallerWithoutThePermission_AT_BR_11() {
            scope = null;
            try {
                assertThatThrownBy(() -> search.search(byClient(UUID.randomUUID()), null, null, manager()))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("audit:read");
            } finally {
                scope = Scope.ALL;
            }
        }

        /** TEAM is refused rather than served unchecked — see AuditSearchService#authorize. */
        @Test
        void refusesTeamScopeRatherThanGuessing_AT_BR_11() {
            scope = Scope.TEAM;
            try {
                assertThatThrownBy(() -> search.search(byClient(UUID.randomUUID()), null, null, supervisor()))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("ALL scope");
            } finally {
                scope = Scope.ALL;
            }
        }

        /** An unknown action is a 400 naming the field, not a 500 from an enum cast. */
        @Test
        void refusesAnUnknownAction() {
            assertThatThrownBy(() -> search.search(
                            new AuditQuery(
                                    UUID.randomUUID(), null, null, null, List.of("DROP TABLE"), null, null, null),
                            null,
                            null,
                            auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("validation");
        }

        @Test
        void refusesAnOversizedPage() {
            assertThatThrownBy(() -> search.search(byClient(UUID.randomUUID()), null, 500, auditor()))
                    .isInstanceOf(ApiException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static AuditQuery byClient(UUID clientId) {
        return new AuditQuery(clientId, null, null, null, null, null, null, null);
    }

    private static CurrentUser auditor() {
        return new CurrentUser(UUID.randomUUID(), "auditor@bank.example", "An Auditor", List.of("AUDITOR"));
    }

    private static CurrentUser supervisor() {
        return new CurrentUser(UUID.randomUUID(), "sup@bank.example", "A Supervisor", List.of("SUPERVISOR"));
    }

    private static CurrentUser manager() {
        return new CurrentUser(UUID.randomUUID(), "mgr@bank.example", "A Manager", List.of("MANAGER"));
    }

    private synchronized void seed(UUID clientId, int rows) {
        int chain = nextChain++;
        ingestion.forgetHeads();
        for (int i = 0; i < rows; i++) {
            ingestion.store(event(clientId), chain, i, "client.events");
        }
    }

    private JsonNode event(UUID clientId) {
        try {
            return JSON.readTree(
                    """
                    {
                      "eventId": "%s",
                      "eventType": "client.updated",
                      "occurredAt": "%s",
                      "service": "client-service",
                      "actor": { "userId": "3f000000-0000-4000-8000-000000000002",
                                 "email": "a.nowak@bank.example", "role": "MANAGER", "ip": "10.4.11.87" },
                      "entity": { "type": "CLIENT", "id": "%s" },
                      "clientId": "%s",
                      "payload": { "action": "UPDATE",
                                   "changedFields": { "email": { "old": "***MASKED***", "new": "***MASKED***" } } }
                    }
                    """.formatted(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS), clientId, clientId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The §9.2.8 scope for this caller, flipped per test. Production resolves it from the tables. */
    @TestConfiguration
    static class Policy {

        @Bean
        @Primary
        AccessPolicy scopedPolicy() {
            return (user, permission) ->
                    Permissions.AUDIT_READ.equals(permission) || Permissions.AUDIT_VERIFY.equals(permission)
                            ? Optional.ofNullable(scope)
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
