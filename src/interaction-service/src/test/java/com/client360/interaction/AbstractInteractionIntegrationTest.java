package com.client360.interaction;

import com.client360.interaction.api.UserSummary;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.client.ClientAccessView;
import com.client360.interaction.client.UserDirectoryClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL 16 with both schemas applied: {@code interaction} carries foreign keys into
 * {@code client.clients} and {@code client.users} in the shared development database, so its
 * migrations cannot run alone.
 *
 * <p>The two cross-service HTTP calls are replaced by doubles. What they hide is one hop; what
 * they leave under test is everything this service actually owns — the schema, its constraints and
 * triggers, body encryption, the PAN detector, keyset paging, the visibility rule and the outbox.
 * The HTTP contract itself is not covered here and needs a contract test once both services run
 * together.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractInteractionIntegrationTest {

    protected static final UUID TEAM_RWN = UUID.fromString("7a000000-0000-4000-8000-000000000001");
    protected static final UUID OLA_WISNIEWSKA = UUID.fromString("3f000000-0000-4000-8000-000000000001");
    protected static final UUID ADAM_NOWAK = UUID.fromString("3f000000-0000-4000-8000-000000000002");
    protected static final UUID MARTA_LEWANDOWSKA = UUID.fromString("3f000000-0000-4000-8000-000000000003");
    protected static final UUID CLIENT_ID = UUID.fromString("9b000000-0000-4000-8000-000000000001");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    /** What the doubled client-service answers. A test flips this to close the client (CP-BR-08). */
    protected static volatile String clientStatus = "ACTIVE";

    /** When set, the doubled client-service refuses the next access check the way ER-01 requires. */
    protected static volatile boolean clientInScope = true;

    static {
        POSTGRES.start();
        applyBootstrap();
        applyClientSchema();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=interaction,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("client360.crypto.data-key", () -> "ZGV2LW9ubHktMzItYnl0ZS1rZXktcmVwbGFjZS1tZSE=");
        registry.add("client360.crypto.pepper", () -> "ZGV2LW9ubHktcGVwcGVyLXJlcGxhY2UtbWUtcGxlYXNl");
        registry.add("client360.security.jwt.public-key", TestJwt::publicKeyPem);
        registry.add("client360.client-service.base-url", () -> "http://localhost:0");
        registry.add("client360.outbox.relay.enabled", () -> "false");
    }

    @BeforeEach
    void resetData() {
        clientStatus = "ACTIVE";
        clientInScope = true;
        jdbc.sql("DELETE FROM interaction.interactions").update();
        jdbc.sql("DELETE FROM interaction.outbox_events").update();
        jdbc.sql("DELETE FROM interaction.idempotency_keys").update();
        seedClientSchema();
    }

    protected String bearerFor(UUID userId) {
        return bearerFor(userId, "MANAGER");
    }

    /**
     * The {@code roles} claim reaches authorization only through {@link Doubles#accessPolicy}, a
     * test stand-in for the seam. Production never reads a role string (RB-BR-01).
     */
    protected String bearerFor(UUID userId, String role) {
        return TestJwt.bearerFor(userId, "test." + userId + "@bank.example", "Test User", List.of(role));
    }

    protected int countEvents(String eventType) {
        return jdbc.sql("SELECT count(*) FROM interaction.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    /** client-service owns these tables; the fixtures exist only to satisfy the development FKs. */
    private void seedClientSchema() {
        jdbc.sql("""
                        INSERT INTO client.teams (id, name, code, timezone)
                        VALUES (:team, 'Retail Warsaw North', 'RWN', 'Europe/Warsaw')
                        ON CONFLICT (id) DO NOTHING
                        """).param("team", TEAM_RWN).update();
        insertUser(OLA_WISNIEWSKA, "EMP-1001", "o.wisniewska@bank.example", "Ola Wiśniewska");
        insertUser(ADAM_NOWAK, "EMP-1002", "a.nowak@bank.example", "Adam Nowak");
        insertUser(MARTA_LEWANDOWSKA, "EMP-1003", "m.lewandowska@bank.example", "Marta Lewandowska");
        // The encrypted columns hold placeholder bytes: this service never decrypts a client, and
        // producing real ciphertext here would only test client-service's cipher twice.
        jdbc.sql("""
                        INSERT INTO client.clients
                            (id, external_ref, first_name, last_name, date_of_birth,
                             phone_enc, phone_hash, key_version, owner_manager_id, team_id,
                             created_by, updated_by)
                        VALUES (:id, 'CIF-0092841', 'Anna', 'Kowalska', DATE '1988-04-17',
                                '\\x00'::bytea, '\\x00'::bytea, 1, :owner, :team, :owner, :owner)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", CLIENT_ID)
                .param("owner", ADAM_NOWAK)
                .param("team", TEAM_RWN)
                .update();
    }

    private void insertUser(UUID id, String employeeNo, String email, String fullName) {
        jdbc.sql("""
                        INSERT INTO client.users
                            (id, employee_no, email, full_name, password_hash, status, primary_team_id)
                        VALUES (:id, :employeeNo, :email, :fullName, '{noop}local-dev-only', 'ACTIVE', :team)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("employeeNo", employeeNo)
                .param("email", email)
                .param("fullName", fullName)
                .param("team", TEAM_RWN)
                .update();
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

    /**
     * client-service's own migrations, in their own schema with their own history table — the same
     * separation compose gives the two Flyway containers. Spring's Flyway then applies this
     * service's migrations to {@code interaction}.
     */
    private static void applyClientSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration-client")
                .schemas("client")
                .defaultSchema("client")
                .table("flyway_schema_history_client")
                .load()
                .migrate();
    }

    private static String read(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing test resource " + classpathLocation, e);
        }
    }

    /**
     * Stands in for the two calls into client-service. Authorization still runs — the double
     * answers the way client-service would, including ER-01's 404 — but over a method call rather
     * than HTTP.
     */
    @TestConfiguration
    public static class Doubles {

        /**
         * {@code MvpAccessPolicy} makes everyone a MANAGER, which cannot express IL-BR-09 or the
         * delete and {@code includeDeleted} rules of §6.3 — each only means something when callers
         * differ. The rows below follow the §9.2.8 matrix for the permissions this service asks.
         */
        @Bean
        @Primary
        com.client360.common.security.AccessPolicy accessPolicy() {
            com.client360.common.security.AccessPolicy manager = new com.client360.common.security.MvpAccessPolicy();
            java.util.Set<String> auditorAll = java.util.Set.of(
                    com.client360.common.security.Permissions.INTERACTION_READ,
                    com.client360.common.security.Permissions.AUDIT_READ,
                    com.client360.common.security.Permissions.CLIENT_READ);
            java.util.Set<String> supervisorTeamExtra = java.util.Set.of(
                    com.client360.common.security.Permissions.INTERACTION_DELETE,
                    com.client360.common.security.Permissions.AUDIT_READ);
            return (user, permission) -> {
                if (user.roles().contains("ADMIN")) {
                    return java.util.Optional.of(com.client360.common.security.Scope.ALL);
                }
                if (user.roles().contains("AUDITOR")) {
                    return auditorAll.contains(permission)
                            ? java.util.Optional.of(com.client360.common.security.Scope.ALL)
                            : java.util.Optional.empty();
                }
                if (user.roles().contains("SUPERVISOR") && supervisorTeamExtra.contains(permission)) {
                    return java.util.Optional.of(com.client360.common.security.Scope.TEAM);
                }
                return manager.scopeOf(user, permission);
            };
        }

        @Bean
        @Primary
        ClientAccessClient clientAccessDouble() {
            return new ClientAccessClient(
                    org.springframework.web.client.RestClient.builder(),
                    "http://localhost:0",
                    java.time.Duration.ofSeconds(1)) {
                @Override
                public ClientAccessView require(UUID clientId, String permission) {
                    if (!clientInScope) {
                        throw com.client360.common.api.ApiException.notFound(
                                "CLIENT_NOT_FOUND", "Client not found or not in your scope.");
                    }
                    return new ClientAccessView(clientId, ADAM_NOWAK, TEAM_RWN, clientStatus, "NOT_STARTED");
                }
            };
        }

        @Bean
        @Primary
        UserDirectoryClient userDirectoryDouble() {
            return new UserDirectoryClient(
                    org.springframework.web.client.RestClient.builder(),
                    "http://localhost:0",
                    java.time.Duration.ofSeconds(1)) {
                @Override
                public Map<UUID, UserSummary> byIds(Collection<UUID> ids) {
                    return ids.stream()
                            .distinct()
                            .collect(Collectors.toMap(Function.identity(), id -> new UserSummary(id, name(id))));
                }

                @Override
                public UserSummary byId(UUID id) {
                    return new UserSummary(id, name(id));
                }

                private String name(UUID id) {
                    if (ADAM_NOWAK.equals(id)) {
                        return "Adam Nowak";
                    }
                    return MARTA_LEWANDOWSKA.equals(id) ? "Marta Lewandowska" : "Ola Wiśniewska";
                }
            };
        }
    }
}
