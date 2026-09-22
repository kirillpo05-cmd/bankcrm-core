package com.client360.client;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

    /** Supervisor of RWN — the approver who is not the owner in the CP-BR-05 tests. */
    protected static final UUID OLA_WISNIEWSKA = UUID.fromString("3f000000-0000-4000-8000-000000000001");

    protected static final UUID ADAM_NOWAK = UUID.fromString("3f000000-0000-4000-8000-000000000002");
    protected static final UUID MARTA_LEWANDOWSKA = UUID.fromString("3f000000-0000-4000-8000-000000000003");

    /** Supervisor of RWS, so TEAM scope excludes every client owned in RWN. */
    protected static final UUID PIOTR_KOWALCZYK = UUID.fromString("3f000000-0000-4000-8000-000000000004");

    protected static final UUID JAN_ZIELINSKI = UUID.fromString("3f000000-0000-4000-8000-000000000005");
    protected static final UUID SOFIA_ADAMSKA = UUID.fromString("3f000000-0000-4000-8000-000000000006");
    protected static final UUID EWA_ZGODNOSC = UUID.fromString("3f000000-0000-4000-8000-000000000008");

    /** Deactivated, so RB-BR-07 and "reassign to a leaver" have something to fail against. */
    protected static final UUID BARTOSZ_BYLY = UUID.fromString("3f000000-0000-4000-8000-000000000009");

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
        return bearerFor(userId, "MANAGER");
    }

    /**
     * The {@code roles} claim reaches authorization only through a test double for the
     * {@code AccessPolicy} seam ({@link MatrixAccessPolicy}); production resolves permission and
     * scope from the database and never reads a role string (RB-BR-01).
     */
    protected String bearerFor(UUID userId, String role) {
        return TestJwt.bearerFor(userId, "test." + userId + "@bank.example", "Test User", java.util.List.of(role));
    }

    /** Creates a client owned by {@code owner}, acting as that owner, and returns its id. */
    protected String createClient(UUID owner, String externalRef, String email, String phone) throws Exception {
        return createClient(owner, "MANAGER", owner, externalRef, email, phone);
    }

    /**
     * Creates a client through the API rather than by INSERT, so every fixture goes through the
     * same validation, encryption and event path the endpoints under test rely on.
     */
    protected String createClient(UUID actor, String role, UUID owner, String externalRef, String email, String phone)
            throws Exception {
        String body = """
                {
                  "externalRef": "%s",
                  "firstName": "Anna",
                  "lastName": "Kowalska",
                  "dateOfBirth": "1988-04-17",
                  "email": "%s",
                  "phone": "%s",
                  "taxId": "PL88%08d",
                  "address": "ul. Prosta 51, 00-838 Warszawa",
                  "preferredChannel": "PHONE",
                  "segment": "RETAIL",
                  "ownerManagerId": "%s"
                }
                """.formatted(
                        externalRef,
                        email,
                        phone,
                        // Tax ID is unique among non-deleted clients (CP-BR-02), so two
                        // fixtures may not share one. Phone deliberately may (CP-EC-03).
                        Math.floorMod(externalRef.hashCode(), 100_000_000),
                        owner);
        MvcResult result = mvc.perform(post("/api/v1/clients")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                        .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonField(result.getResponse().getContentAsString(), "id");
    }

    /** Deliberately crude: the tests assert on the wire shape, not on a deserialized model. */
    protected static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    protected int countEvents(String eventType) {
        return jdbc.sql("SELECT count(*) FROM client.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
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
        insertUser(OLA_WISNIEWSKA, "EMP-1001", "o.wisniewska@bank.example", "Ola Wiśniewska", TEAM_RWN);
        insertUser(ADAM_NOWAK, "EMP-1002", "a.nowak@bank.example", "Adam Nowak", TEAM_RWN);
        insertUser(PIOTR_KOWALCZYK, "EMP-1004", "p.kowalczyk@bank.example", "Piotr Kowalczyk", TEAM_RWS);
        insertUser(EWA_ZGODNOSC, "EMP-1008", "e.zgodnosc@bank.example", "Ewa Zgodność", null);
        // ck_users_deactivated_pair wants the status and the timestamp to agree within the row,
        // so this one cannot go through insertUser.
        jdbc.sql("""
                        INSERT INTO client.users
                            (id, employee_no, email, full_name, password_hash, status, primary_team_id, deactivated_at)
                        VALUES (:id, 'EMP-1009', 'b.byly@bank.example', 'Bartosz Były', '{noop}local-dev-only',
                                'DEACTIVATED', NULL, now() - INTERVAL '30 days')
                        ON CONFLICT (id) DO NOTHING
                        """).param("id", BARTOSZ_BYLY).update();
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
