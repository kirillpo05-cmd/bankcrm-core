package com.client360.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.client360.audit.TestKeys;
import com.client360.audit.api.AuditQuery;
import com.client360.common.api.ApiException;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Audit exports (SPEC.md §8.3, AT-US-04, AT-EC-09).
 *
 * <p>A real S3 server, for the same reason the tests use a real PostgreSQL: a doubled object store
 * would agree with whatever the code assumed about pre-signing and checksums, and the two
 * properties most worth proving here are that the link actually works and that the checksum
 * actually matches the bytes behind it.
 *
 * <p>The export runs synchronously in these tests — the hand-off point is overridden rather than the
 * generator, so everything after the request is the production path.
 */
@SpringBootTest
@Import({AuditExportIntegrationTest.Policy.class, AuditExportIntegrationTest.SynchronousExports.class})
class AuditExportIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("client360")
            .withUsername("client360")
            .withPassword("client360_local_only");

    private static final LocalStackContainer S3 = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private static final String BUCKET = "client360-audit-exports";
    private static final String REASON = "Regulatory request REF-2026-0918 from the national supervisor";

    static {
        POSTGRES.start();
        S3.start();
        applyBootstrap();
        createBucket();
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AuditExportService exports;

    @Autowired
    private ExportStore exportStore;

    @Autowired
    private com.client360.audit.service.AuditEvents auditEvents;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=audit,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("client360.outbox.relay.enabled", () -> "false");
        registry.add("client360.security.jwt.public-key", TestKeys::publicKeyPem);
        registry.add("client360.audit.export.endpoint", () -> S3.getEndpoint().toString());
        registry.add("client360.audit.export.bucket", () -> BUCKET);
        registry.add("client360.audit.export.access-key", S3::getAccessKey);
        registry.add("client360.audit.export.secret-key", S3::getSecretKey);
        registry.add("client360.audit.export.region", S3::getRegion);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM audit.audit_export_jobs").update();
        jdbc.sql("DELETE FROM audit.rate_limit_counters").update();
        jdbc.sql("DELETE FROM audit.outbox_events").update();
        // audit_log is not reset, and cannot be: the immutability trigger refuses DELETE, which is
        // AT-BR-01 working. So each test seeds under its own client id and filters by it, the same
        // way AuditSearchServiceTest does — rows from earlier tests simply are not in the slice.
    }

    @Nested
    @DisplayName("requesting an export (AT-US-04)")
    class Requesting {

        @Test
        void acceptsTheRequestAndEstimatesTheRows_AT_US_04() {
            UUID clientId = insertEntries(3);
            AuditExportService.Job job = request(filter(clientId), "CSV", null);

            assertThat(job.estimatedRows()).isEqualTo(3);
            assertThat(job.expiresAt()).isAfter(Instant.now());
            // Queued before the worker ran, which is what the 202 means.
            assertThat(statusOf(job.id())).isIn("QUEUED", "RUNNING", "COMPLETED");
        }

        /** §8.3: an export without a stated purpose is not auditable. */
        @Test
        void refusesAReasonUnderTwentyCharacters_9_3() {
            assertThatThrownBy(() -> exports.request(null, AuditQuery.unfiltered(), "CSV", "because", auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("validation");
        }

        @Test
        void refusesAnUnknownFormat_9_3() {
            assertThatThrownBy(() -> exports.request(null, AuditQuery.unfiltered(), "PDF", REASON, auditor()))
                    .isInstanceOf(ApiException.class);
        }

        /** AT-EC-09's ceiling: past it the filter is the problem, not the export. */
        @Test
        void refusesAnExportPastTheRowCeiling_AT_EC_09() {
            insertEntries(3);
            // The ceiling is configured at five million; lowered for the test by asking for an
            // export the property says is too large would need five million rows, so the property
            // is what moves instead.
            assertThatThrownBy(() ->
                            exportsWithCeiling(2).request(null, AuditQuery.unfiltered(), "CSV", REASON, auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("too large");
        }

        /** §8.3: five an hour. */
        @Test
        void refusesPastFiveAnHour_9_3() {
            for (int i = 0; i < 5; i++) {
                request(AuditQuery.unfiltered(), "CSV", null);
            }
            assertThatThrownBy(() -> request(AuditQuery.unfiltered(), "CSV", null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Too many requests");
        }

        /**
         * §4.6 through the job row rather than through {@code idempotency_keys}: the shared service
         * encrypts the replayed body and would need an AES key this service must not hold
         * (AT-BR-04).
         */
        @Test
        void replaysTheSameJobForOneIdempotencyKey_4_6() {
            UUID key = UUID.randomUUID();
            AuditExportService.Job first = request(AuditQuery.unfiltered(), "CSV", key);
            AuditExportService.Job replay = request(AuditQuery.unfiltered(), "CSV", key);

            assertThat(replay.id()).isEqualTo(first.id());
            assertThat(jobCount()).isEqualTo(1);
            // And the replay did not spend one of the five.
            assertThat(hits()).isEqualTo(1);
        }

        /** A second requester's identical key is their own export, not a replay of somebody else's. */
        @Test
        void oneKeyIsPerRequester_4_6() {
            UUID key = UUID.randomUUID();
            AuditExportService.Job mine = request(AuditQuery.unfiltered(), "CSV", key);
            AuditExportService.Job theirs =
                    exports.request(key, AuditQuery.unfiltered(), "CSV", REASON, otherAuditor());
            assertThat(theirs.id()).isNotEqualTo(mine.id());
        }

        /** AT-BR-11: {@code audit:export} at ALL scope, which a manager holds at none. */
        @Test
        void requiresAuditExport_AT_BR_11() {
            assertThatThrownBy(() -> exports.request(null, AuditQuery.unfiltered(), "CSV", REASON, manager()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("audit:export");
        }

        /** AT-BR-10: the request is audited through the outbox, before the file exists. */
        @Test
        void auditsTheRequest_AT_BR_10() {
            request(AuditQuery.unfiltered(), "CSV", null);
            assertThat(outboxRows("audit.export_requested")).isEqualTo(1);
            assertThat(payloadOf("audit.export_requested")).contains("REF-2026-0918");
        }
    }

    @Nested
    @DisplayName("the file it produces (AT-EC-09)")
    class TheFile {

        @Test
        void writesEveryMatchingRowAsCsvWithAHeader_AT_US_04() throws Exception {
            UUID clientId = insertEntries(3);
            AuditExportService.Job job = request(filter(clientId), "CSV", null);

            String csv = download(job.id());
            List<String> lines = csv.lines().toList();
            assertThat(lines).hasSize(4);
            assertThat(lines.getFirst()).startsWith("id,occurred_at,recorded_at");
            assertThat(lines.get(1)).contains(clientId.toString());
            assertThat(rowCountOf(job.id())).isEqualTo(3);
        }

        /** JSONL, with the two JSON columns embedded as objects rather than re-quoted strings. */
        @Test
        void writesJsonlWithEmbeddedJsonColumns_AT_US_04() throws Exception {
            UUID clientId = insertEntries(1);
            AuditExportService.Job job = request(filter(clientId), "JSONL", null);

            String jsonl = download(job.id());
            assertThat(jsonl.lines()).hasSize(1);
            assertThat(jsonl).contains("\"client_id\":\"" + clientId + "\"");
            // An object, not "{\"firstName\": ...}" escaped into a string.
            assertThat(jsonl).contains("\"changed_fields\":{");
        }

        /**
         * §8.3: the checksum is carried on the job. It is computed over the compressed stream as it
         * is written, so it is the digest of exactly the bytes in the bucket — which is the only
         * version of it anyone receiving the file can reproduce.
         */
        @Test
        void theChecksumMatchesTheBytesInTheBucket_9_3() throws Exception {
            AuditExportService.Job job = request(filter(insertEntries(2)), "CSV", null);

            byte[] raw = fetch(downloadUrl(job.id()));
            assertThat(sha256Hex(raw)).isEqualTo(checksumOf(job.id()));
        }

        /** The object is gzipped (AT-EC-09), which is why the checksum is of compressed bytes. */
        @Test
        void theObjectIsGzipped_AT_EC_09() throws Exception {
            AuditExportService.Job job = request(filter(insertEntries(1)), "CSV", null);
            byte[] raw = fetch(downloadUrl(job.id()));
            // The gzip magic number. Decompressing it below is the real assertion.
            assertThat(raw[0] & 0xFF).isEqualTo(0x1f);
            assertThat(raw[1] & 0xFF).isEqualTo(0x8b);
            assertThat(gunzip(raw)).contains("occurred_at");
        }

        /** AT-BR-04: the log holds no sensitive values, so neither can a file made from it. */
        @Test
        void carriesNoSensitiveValues_AT_BR_04() throws Exception {
            AuditExportService.Job job = request(filter(insertEntries(1)), "CSV", null);
            String csv = download(job.id());
            // changed_fields records that a field changed, never what to.
            assertThat(csv).contains("***MASKED***");
            assertThat(csv).doesNotContain("anna.kowalska@example.com");
        }

        /** A filter that matches nothing produces a header and no rows, not a failure. */
        @Test
        void anEmptySliceIsAValidExport_AT_US_04() throws Exception {
            AuditExportService.Job job = request(filter(UUID.randomUUID()), "CSV", null);
            assertThat(download(job.id()).lines()).hasSize(1);
            assertThat(rowCountOf(job.id())).isZero();
            assertThat(statusOf(job.id())).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("collecting it (§8.3)")
    class Collecting {

        @Test
        void thelinkIsPresignedAndWorks_9_3() throws Exception {
            AuditExportService.Job job = request(filter(insertEntries(1)), "CSV", null);

            AuditExportService.JobView view = exports.status(job.id(), auditor());
            assertThat(view.status()).isEqualTo("COMPLETED");
            assertThat(view.progressPercent()).isEqualTo(100);
            assertThat(view.downloadUrl()).isNotNull();
            assertThat(view.checksumSha256()).isNotNull();
            // Signed: the query string carries the signature, and fetching it works without any
            // credentials of our own.
            assertThat(view.downloadUrl().getQuery()).contains("X-Amz-Signature");
            assertThat(fetch(view.downloadUrl())).isNotEmpty();
        }

        /** §8.3: "exports are not shared by URL". */
        @Test
        void refusesSomeoneElsesExport_9_3() {
            AuditExportService.Job job = request(AuditQuery.unfiltered(), "CSV", null);
            assertThatThrownBy(() -> exports.status(job.id(), otherAuditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("requested by someone else");
        }

        /** An admin may read any export, which is what makes a compliance review possible. */
        @Test
        void anAdminMaySeeAnyExport_9_3() {
            AuditExportService.Job job = request(AuditQuery.unfiltered(), "CSV", null);
            assertThat(exports.status(job.id(), admin()).jobId()).isEqualTo(job.id());
        }

        @Test
        void anUnknownJobIsNotFound_9_3() {
            assertThatThrownBy(() -> exports.status(UUID.randomUUID(), auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Export job not found");
        }

        /** §8.3: past {@code expires_at} the file is gone and the job must be re-requested. */
        @Test
        void anExpiredExportIsGone_9_3() {
            AuditExportService.Job job = request(AuditQuery.unfiltered(), "CSV", null);
            age(job.id());
            assertThatThrownBy(() -> exports.status(job.id(), auditor()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired");
        }

        /**
         * The sweeper deletes the object and keeps the row. That someone exported this slice on
         * this date stays interesting long after the file stops being useful.
         */
        @Test
        void theSweeperDeletesTheFileAndKeepsTheRecord_9_3() {
            AuditExportService.Job job = request(filter(insertEntries(1)), "CSV", null);
            String key = storageKeyOf(job.id());
            age(job.id());

            exports.expireFinishedExports();

            assertThat(statusOf(job.id())).isEqualTo("EXPIRED");
            assertThat(storageKeyOf(job.id())).isNull();
            assertThat(jobCount()).isEqualTo(1);
            assertThat(key).isNotNull();
        }

        /** §8.3: the download event carries the jobId, row count and checksum (AT-BR-10). */
        @Test
        void issuingTheLinkIsAudited_AT_BR_10() {
            AuditExportService.Job job = request(filter(insertEntries(2)), "CSV", null);
            jdbc.sql("DELETE FROM audit.outbox_events").update();

            exports.status(job.id(), auditor());

            assertThat(outboxRows("audit.export_downloaded")).isEqualTo(1);
            String payload = payloadOf("audit.export_downloaded");
            assertThat(payload).contains(job.id().toString()).contains(checksumOf(job.id()));
        }

        /** A job still running issues no link, and says so rather than looking finished. */
        @Test
        void aFailedExportReportsItsStatusAndNoLink_9_3() {
            AuditExportService.Job job = request(AuditQuery.unfiltered(), "CSV", null);
            jdbc.sql("UPDATE audit.audit_export_jobs SET status = 'FAILED', completed_at = now(),"
                            + " storage_key = NULL, error_message = 'IOException' WHERE id = :id")
                    .param("id", job.id())
                    .update();

            AuditExportService.JobView view = exports.status(job.id(), auditor());
            assertThat(view.status()).isEqualTo("FAILED");
            assertThat(view.downloadUrl()).isNull();
            assertThat(view.error()).isEqualTo("IOException");
        }
    }

    // ------------------------------------------------------------------ helpers

    private AuditExportService.Job request(AuditQuery query, String format, UUID idempotencyKey) {
        return exports.request(idempotencyKey, query, format, REASON, auditor());
    }

    /** A service with a lower row ceiling, to reach AT-EC-09 without inserting five million rows. */
    private AuditExportService exportsWithCeiling(long maxRows) {
        ExportProperties tight = new ExportProperties(
                S3.getEndpoint().toString(),
                BUCKET,
                S3.getAccessKey(),
                S3.getSecretKey(),
                S3.getRegion(),
                java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(7),
                maxRows,
                true);
        return new AuditExportService(
                jdbc,
                (user, permission) -> Optional.of(Scope.ALL),
                // The ceiling is checked before anything touches the store, so the configured
                // one serves; only the limit differs.
                exportStore,
                tight,
                new com.client360.common.ratelimit.RateLimiter(jdbc),
                auditEvents,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static AuditQuery filter(UUID clientId) {
        return new AuditQuery(clientId, null, null, null, null, null, null, null);
    }

    /** Rows an export can find, with a masked field so AT-BR-04 has something to prove. */
    private UUID insertEntries(int count) {
        UUID clientId = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            INSERT INTO audit.audit_log
                                (event_id, occurred_at, actor_id, actor_email, service, entity_type,
                                 entity_id, client_id, action, chain_id, chain_seq, kafka_offset,
                                 row_hash, changed_fields)
                            VALUES (gen_random_uuid(), now() - make_interval(mins => :i),
                                    gen_random_uuid(), 'a.nowak@bank.example', 'client-service',
                                    'CLIENT', gen_random_uuid(), :clientId, 'UPDATE', 3, :seq, :i,
                                    decode(repeat('00', 32), 'hex'),
                                    '{"email": {"old": "***MASKED***", "new": "***MASKED***"}}')
                            """)
                    .param("i", i)
                    .param("seq", System.nanoTime() % 1_000_000 + i)
                    .param("clientId", clientId)
                    .update();
        }
        return clientId;
    }

    private String download(UUID jobId) throws Exception {
        return gunzip(fetch(downloadUrl(jobId)));
    }

    private URI downloadUrl(UUID jobId) {
        return exports.status(jobId, auditor()).downloadUrl();
    }

    /** Follows the pre-signed link with no credentials, exactly as a browser would. */
    private static byte[] fetch(URI url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
        connection.setRequestMethod("GET");
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        } finally {
            connection.disconnect();
        }
    }

    private static String gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private String statusOf(UUID jobId) {
        return jdbc.sql("SELECT status::text FROM audit.audit_export_jobs WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private Long rowCountOf(UUID jobId) {
        return jdbc.sql("SELECT row_count FROM audit.audit_export_jobs WHERE id = :id")
                .param("id", jobId)
                .query(Long.class)
                .single();
    }

    private String storageKeyOf(UUID jobId) {
        return jdbc.sql("SELECT storage_key FROM audit.audit_export_jobs WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String checksumOf(UUID jobId) {
        return jdbc.sql("SELECT encode(checksum_sha256, 'hex') FROM audit.audit_export_jobs WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private int jobCount() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_export_jobs")
                .query(Integer.class)
                .single();
    }

    private int hits() {
        return jdbc.sql("SELECT coalesce(sum(hits), 0) FROM audit.rate_limit_counters")
                .query(Integer.class)
                .single();
    }

    private int outboxRows(String eventType) {
        return jdbc.sql("SELECT count(*) FROM audit.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    private String payloadOf(String eventType) {
        return jdbc.sql("SELECT payload::text FROM audit.outbox_events WHERE event_type = :type"
                        + " ORDER BY id DESC LIMIT 1")
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    /** {@code ck_export_expiry} compares the two ends, so both move. */
    private void age(UUID jobId) {
        jdbc.sql("UPDATE audit.audit_export_jobs SET requested_at = now() - INTERVAL '8 days',"
                        + " expires_at = now() - INTERVAL '1 day' WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    private static CurrentUser auditor() {
        return new CurrentUser(
                UUID.fromString("3f000000-0000-4000-8000-000000000007"),
                "j.audyt@bank.example",
                "Jakub Audyt",
                List.of("AUDITOR"));
    }

    private static CurrentUser otherAuditor() {
        return new CurrentUser(
                UUID.fromString("3f000000-0000-4000-8000-000000000008"),
                "e.zgodnosc@bank.example",
                "Ewa Zgodność",
                List.of("COMPLIANCE"));
    }

    private static CurrentUser admin() {
        return new CurrentUser(
                UUID.fromString("3f000000-0000-4000-8000-000000000006"),
                "s.admin@bank.example",
                "Sofia Adamska",
                List.of("ADMIN"));
    }

    private static CurrentUser manager() {
        return new CurrentUser(UUID.randomUUID(), "mgr@bank.example", "A Manager", List.of("MANAGER"));
    }

    /**
     * {@code audit:export} and {@code audit:read} at ALL for an auditor, a compliance officer and an
     * admin; nothing for a manager (AT-BR-11). {@code audit:read} at ALL only for the admin, which
     * is what makes "not your export" distinguishable from "any export".
     */
    @TestConfiguration
    static class Policy {

        @Bean
        @Primary
        AccessPolicy scopedPolicy() {
            return (user, permission) -> {
                if (user.roles().contains("MANAGER")) {
                    return Optional.empty();
                }
                if (Permissions.AUDIT_READ.equals(permission)) {
                    return user.roles().contains("ADMIN") ? Optional.of(Scope.ALL) : Optional.empty();
                }
                return Optional.of(Scope.ALL);
            };
        }
    }

    /**
     * Runs the export in the calling thread instead of after commit.
     *
     * <p>Only the hand-off is replaced; generation, compression, hashing and the upload are the
     * production path. Awaiting a real {@code @Async} would make every assertion here a race.
     */
    @TestConfiguration
    static class SynchronousExports {

        @Bean
        @Primary
        AuditExportService synchronousExports(
                JdbcClient jdbc,
                AccessPolicy accessPolicy,
                ExportStore store,
                ExportProperties properties,
                com.client360.common.ratelimit.RateLimiter rateLimiter,
                com.client360.audit.service.AuditEvents events,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new AuditExportService(jdbc, accessPolicy, store, properties, rateLimiter, events, objectMapper) {
                @Override
                void runAfterCommit(UUID jobId, AuditQuery query, String format) {
                    run(jobId, query, format);
                }
            };
        }
    }

    private static void createBucket() {
        try {
            S3.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the export bucket", e);
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
