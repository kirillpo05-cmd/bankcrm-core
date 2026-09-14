package com.client360.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL 16 with the project's own bootstrap and Flyway migrations applied — the same
 * files {@code docker compose} applies, packaged into the jar by the pom. Constraints, triggers,
 * partial indexes and {@code now()} all behave here exactly as they do in the running service,
 * which is the point: a test against an in-memory database would not exercise any of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Seeded by {@code db/seed/local/seed_local.sql}; re-inserted here so tests own their data. */
    protected static final UUID TEAM_RWN = UUID.fromString("7a000000-0000-4000-8000-000000000001");

    protected static final UUID TEAM_RWS = UUID.fromString("7a000000-0000-4000-8000-000000000002");
    protected static final UUID ADAM_NOWAK = UUID.fromString("3f000000-0000-4000-8000-000000000002");
    protected static final UUID MARTA_LEWANDOWSKA = UUID.fromString("3f000000-0000-4000-8000-000000000003");
    protected static final UUID JAN_ZIELINSKI = UUID.fromString("3f000000-0000-4000-8000-000000000005");
    protected static final UUID SOFIA_ADAMSKA = UUID.fromString("3f000000-0000-4000-8000-000000000006");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    static {
        POSTGRES.start();
        applyBootstrap();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // `public` must stay on the search path: pg_trgm's similarity() and the % operator are
        // resolved at query time, and the extensions live in public (db/init/01_bootstrap.sql).
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=client,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Local stand-ins for KMS material: 32 bytes of AES key, 32 bytes of HMAC pepper.
        registry.add("client360.crypto.data-key", () -> "ZGV2LW9ubHktMzItYnl0ZS1rZXktcmVwbGFjZS1tZSE=");
        registry.add("client360.crypto.pepper", () -> "ZGV2LW9ubHktcGVwcGVyLXJlcGxhY2UtbWUtcGxlYXNl");
        registry.add("client360.security.jwt.public-key", TestJwt::publicKeyPem);

        // Events are asserted where rule 3 puts them — in outbox_events, in the same transaction
        // as the business change. Publishing to a broker is OutboxRelay's own concern.
        registry.add("client360.outbox.relay.enabled", () -> "false");
    }

    /**
     * Every test starts from the same rows. Clients are deleted rather than truncated so the
     * FK from {@code client_products} and the seeded users both survive.
     */
    @BeforeEach
    void resetData() {
        jdbc.sql("DELETE FROM client.clients").update();
        jdbc.sql("DELETE FROM client.outbox_events").update();
        jdbc.sql("DELETE FROM client.idempotency_keys").update();
        seedTeamsAndUsers();
    }

    protected String bearerFor(UUID userId) {
        return TestJwt.bearerFor(userId, "test." + userId + "@bank.example", "Test User", java.util.List.of("MANAGER"));
    }

    /**
     * The bootstrap is database-wide (extensions, schemas, the restricted role) and therefore not
     * any migration's to make. Executed as one statement so the dollar-quoted {@code DO} block
     * survives — a naive split on {@code ;} would cut it in half.
     */
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

    private void seedTeamsAndUsers() {
        jdbc.sql("""
                        INSERT INTO client.teams (id, name, code, timezone) VALUES
                            (:rwn, 'Retail Warsaw North', 'RWN', 'Europe/Warsaw'),
                            (:rws, 'Retail Warsaw South', 'RWS', 'Europe/Warsaw')
                        ON CONFLICT (id) DO NOTHING
                        """).param("rwn", TEAM_RWN).param("rws", TEAM_RWS).update();
        insertUser(ADAM_NOWAK, "EMP-1002", "a.nowak@bank.example", "Adam Nowak", TEAM_RWN);
        insertUser(MARTA_LEWANDOWSKA, "EMP-1003", "m.lewandowska@bank.example", "Marta Lewandowska", TEAM_RWN);
        insertUser(JAN_ZIELINSKI, "EMP-1005", "j.zielinski@bank.example", "Jan Zieliński", TEAM_RWS);
        // Cross-team role: no primary team, which is what makes CP-BR-03 unsatisfiable for them.
        insertUser(SOFIA_ADAMSKA, "EMP-1006", "s.admin@bank.example", "Sofia Adamska", null);
    }

    private void insertUser(UUID id, String employeeNo, String email, String fullName, UUID teamId) {
        jdbc.sql("""
                        INSERT INTO client.users
                            (id, employee_no, email, full_name, password_hash, status, primary_team_id)
                        VALUES (:id, :employeeNo, :email, :fullName, '{noop}local-dev-only', 'ACTIVE', :teamId)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("employeeNo", employeeNo)
                .param("email", email)
                .param("fullName", fullName)
                .param("teamId", teamId)
                .update();
    }

    private static String read(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing test resource " + classpathLocation, e);
        }
    }
}
