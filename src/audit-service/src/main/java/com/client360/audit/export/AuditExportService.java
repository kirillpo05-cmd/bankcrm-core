package com.client360.audit.export;

import com.client360.audit.api.AuditQuery;
import com.client360.audit.service.AuditEvents;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.ratelimit.RateLimiter;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit exports (SPEC.md §8.3, AT-US-04, AT-EC-09).
 *
 * <p>A compliance officer asks for a filtered slice of the log, gets a job id back immediately, and
 * polls for a download link. The export runs on its own thread because a regulatory request can
 * cover millions of rows, and a request that waits for it is a request that times out.
 *
 * <p><strong>Nothing is ever held in memory.</strong> The rows stream out of PostgreSQL with a
 * 500-row fetch size, through gzip, into a temporary file, and from there to the bucket — so the
 * heap holds one row at a time whether the export is a thousand rows or five million (AT-EC-09).
 * The digest is computed over the same stream, so the checksum is of exactly the bytes that were
 * uploaded rather than of a second pass that could differ.
 *
 * <p>The export carries what the log carries, which is already free of sensitive values
 * (AT-BR-04): {@code changed_fields} says that a field changed, never what it changed to. That is
 * what makes it safe to write the audit log to a file at all.
 */
@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);

    /** AT-EC-09: server-side streaming, 500 rows at a time. */
    private static final int FETCH_SIZE = 500;

    /** §8.3: 5 exports an hour, per requester. */
    private static final int EXPORTS_PER_HOUR = 5;

    private static final String EXPORT_BUCKET = "audit.exports";

    /** §8.3: a reason under 20 characters. An export without a stated purpose is not auditable. */
    private static final int MIN_REASON = 20;

    /** AT-EC-14's reasoning, applied to an export: seven years is the ceiling, not the default. */
    private static final Duration MAX_RANGE = Duration.ofDays(366 * 7);

    private static final List<String> FORMATS = List.of("CSV", "JSONL");

    private final JdbcClient jdbc;
    private final AccessPolicy accessPolicy;
    private final ExportStore store;
    private final ExportProperties properties;
    private final RateLimiter rateLimiter;
    private final AuditEvents events;
    private final ObjectMapper objectMapper;

    public AuditExportService(
            JdbcClient jdbc,
            AccessPolicy accessPolicy,
            ExportStore store,
            ExportProperties properties,
            RateLimiter rateLimiter,
            AuditEvents events,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.store = store;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ request

    /**
     * {@code POST /audit/exports} (§8.3).
     *
     * @throws ApiException {@code 400} on a short reason or an unknown format; {@code 422} past the
     *     row ceiling or the seven-year range; {@code 429} past five an hour
     */
    @Transactional
    public Job request(UUID idempotencyKey, AuditQuery query, String format, String rawReason, CurrentUser caller) {
        requireExportPermission(caller);

        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        String normalizedFormat = format == null ? "CSV" : format.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FORMATS.contains(normalizedFormat)) {
            throw ApiException.validation("format", "must be one of " + FORMATS);
        }
        if (query.from() != null
                && query.to() != null
                && Duration.between(query.from(), query.to()).compareTo(MAX_RANGE) > 0) {
            throw ApiException.businessRule("The range exceeds the seven-year retention period.")
                    .detail("maxRangeDays", MAX_RANGE.toDays());
        }

        // §4.6. Checked before the rate limiter, so a client retrying a request it already made
        // does not spend one of its five — a replay is the same request, not a second one.
        if (idempotencyKey != null) {
            Optional<Job> existing = findByIdempotencyKey(caller.id(), idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        rateLimiter.check(caller.id().toString(), EXPORT_BUCKET, EXPORTS_PER_HOUR, Duration.ofHours(1));

        long estimated = count(query);
        if (estimated > properties.maxRows()) {
            // A legitimate regulatory request can be millions of rows; this is the one that is a
            // filter somebody forgot to set.
            throw ApiException.businessRule("This export is too large. Narrow the filter and try again.")
                    .detail("estimatedRows", estimated, "maxRows", properties.maxRows());
        }

        Job job = insert(idempotencyKey, query, normalizedFormat, caller, estimated);
        events.exportRequested(caller, job.id(), query.summary(), reason, normalizedFormat);
        log.info("export {} queued by {} ({} estimated rows)", job.id(), caller.id(), estimated);
        // Runs after this transaction commits, so the worker cannot read a job row that is not
        // there yet — the one ordering mistake an async hand-off invites.
        runAfterCommit(job.id(), query, normalizedFormat);
        return job;
    }

    // -------------------------------------------------------------------- status

    /**
     * {@code GET /audit/exports/{jobId}} (§8.3).
     *
     * @throws ApiException {@code 404} when unknown; {@code 403} when it is somebody else's;
     *     {@code 410} once it has expired and the file is gone
     */
    @Transactional(readOnly = true)
    public JobView status(UUID jobId, CurrentUser caller) {
        requireExportPermission(caller);
        Job job = find(jobId).orElseThrow(AuditExportService::notFound);

        if (!job.requestedBy().equals(caller.id())
                && accessPolicy
                        .scopeOf(caller, Permissions.AUDIT_READ)
                        .filter(scope -> scope == Scope.ALL)
                        .isEmpty()) {
            // §8.3: "exports are not shared by URL". 403 rather than 404 because this depends on
            // the caller alone — they know the job exists, they asked for the wrong one.
            throw ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, "This export was requested by someone else.");
        }
        if ("EXPIRED".equals(job.status())
                || (job.expiresAt() != null && job.expiresAt().isBefore(Instant.now()))) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.GONE,
                    "EXPORT_EXPIRED",
                    "This export has expired. Request it again.");
        }

        URI downloadUrl = null;
        if ("COMPLETED".equals(job.status()) && job.storageKey() != null) {
            downloadUrl = store.presignedGet(job.storageKey(), filename(job));
            // §8.3: "Downloading an export emits an EXPORT audit event carrying the jobId, row
            // count and checksum." Recorded when the link is issued, which is the last moment this
            // service sees the request — the download itself goes straight to the bucket.
            events.exportDownloaded(caller, job.id(), job.rowCount() == null ? 0 : job.rowCount(), job.checksum());
        }
        return new JobView(
                job.id(),
                job.status(),
                progressPercent(job),
                job.rowCount(),
                downloadUrl,
                job.checksum(),
                job.expiresAt(),
                job.errorMessage());
    }

    // ------------------------------------------------------------------- worker

    /**
     * Generates the file and uploads it (AT-EC-09).
     *
     * <p>Not transactional, deliberately: a transaction open for the length of a five-million-row
     * export would hold a snapshot for minutes and block the vacuum that keeps the log's partitions
     * healthy. The job row's three state transitions are each their own small write instead.
     */
    @Async
    public void run(UUID jobId, AuditQuery query, String format) {
        if (!markRunning(jobId)) {
            // Another worker took it, or it was cancelled. Not an error.
            return;
        }
        Path temp = null;
        String key = "audit-exports/" + jobId + "." + format.toLowerCase(java.util.Locale.ROOT) + ".gz";
        try {
            temp = Files.createTempFile("audit-export-" + jobId, ".gz");
            Generated generated = generate(query, format, temp);
            try (InputStream body = Files.newInputStream(temp)) {
                store.put(key, body, Files.size(temp), "CSV".equals(format) ? "text/csv" : "application/x-ndjson");
            }
            markCompleted(jobId, key, generated.rows(), generated.checksum());
            log.info("export {} completed: {} rows, {} bytes", jobId, generated.rows(), Files.size(temp));
        } catch (Exception e) {
            // The message is the class name and nothing else: an exception from the store can carry
            // the endpoint and a signature, and this string reaches an API response.
            log.error("export {} failed: {}", jobId, e.getClass().getSimpleName(), e);
            markFailed(jobId, e.getClass().getSimpleName());
            store.deleteQuietly(key);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Streams the log into a gzipped file, hashing as it goes.
     *
     * <p>The digest wraps the compressed stream, so the checksum is of the bytes that were
     * uploaded. Hashing the uncompressed rows instead would give a number nobody receiving the file
     * could reproduce.
     */
    private Generated generate(AuditQuery query, String format, Path target) throws IOException {
        MessageDigest digest = sha256();
        long rows;
        try (OutputStream file = Files.newOutputStream(target);
                DigestOutputStream digested = new DigestOutputStream(file, digest);
                GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(digested));
                Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            if ("CSV".equals(format)) {
                writer.write(String.join(",", COLUMNS));
                writer.write("\n");
            }
            // A ResultSetExtractor rather than a row mapper: rows are written out as they
            // arrive and never collected, which is the whole of AT-EC-09. A mapper would hand
            // back a List of five million objects. It returns the count because JdbcClient
            // requires an extractor to produce something, and the count is what the caller
            // wanted anyway.
            org.springframework.jdbc.core.ResultSetExtractor<Long> stream = resultSet -> {
                // The driver is told to fetch in pages rather than hand over the whole result;
                // without this the client buffers every row before the first one is seen, which
                // is the failure AT-EC-09 names.
                resultSet.setFetchSize(FETCH_SIZE);
                long written = 0;
                try {
                    while (resultSet.next()) {
                        writer.write("CSV".equals(format) ? csvRow(resultSet) : jsonRow(resultSet));
                        writer.write("\n");
                        written++;
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return written;
            };
            Long written = jdbc.sql(selectSql()).params(bind(query)).query(stream);
            rows = written == null ? 0 : written;
        }
        // Read after the streams are closed: gzip writes its trailer on close, and a digest
        // taken before that is of a prefix of the file rather than of the file.
        return new Generated(rows, hex(digest.digest()));
    }

    /**
     * Deletes expired exports and their objects (§8.3).
     *
     * <p>The job row stays, as {@code EXPIRED}: that someone exported this slice on this date is
     * audit-relevant long after the file stops being useful, and the row is the only place it is
     * recorded outside the audit log itself.
     */
    @Scheduled(cron = "${client360.audit.export-sweep-cron:0 30 * * * *}")
    public void expireFinishedExports() {
        List<Job> expired = jdbc.sql("SELECT " + JOB_COLUMNS + " FROM audit_export_jobs"
                        + " WHERE status = 'COMPLETED' AND expires_at <= now()")
                .query(AuditExportService::job)
                .list();
        for (Job job : expired) {
            if (job.storageKey() != null) {
                store.deleteQuietly(job.storageKey());
            }
            // storage_key is cleared with the status, because ck_export_completed ties the two
            // together: a COMPLETED job must have a key, so an EXPIRED one must not claim to.
            jdbc.sql("UPDATE audit_export_jobs SET status = 'EXPIRED', storage_key = NULL,"
                            + " completed_at = NULL WHERE id = :id")
                    .param("id", job.id())
                    .update();
            log.info("export {} expired; object deleted", job.id());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Hand-off point, overridden in tests so an export can be driven synchronously. */
    void runAfterCommit(UUID jobId, AuditQuery query, String format) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        run(jobId, query, format);
                    }
                });
    }

    private void requireExportPermission(CurrentUser caller) {
        accessPolicy
                .scopeOf(caller, Permissions.AUDIT_EXPORT)
                .filter(scope -> scope == Scope.ALL)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.ROLE_REQUIRED, "Exporting the audit log requires audit:export at ALL scope.")
                        .detail("permission", Permissions.AUDIT_EXPORT));
    }

    private static final List<String> COLUMNS = List.of(
            "id",
            "occurred_at",
            "recorded_at",
            "actor_id",
            "actor_email",
            "actor_role",
            "service",
            "entity_type",
            "entity_id",
            "client_id",
            "action",
            "request_id",
            "chain_id",
            "chain_seq",
            "changed_fields",
            "context");

    private static String selectSql() {
        return "SELECT " + String.join(", ", COLUMNS).replace("action,", "action::text AS action,") + " FROM audit_log "
                + WHERE + " ORDER BY occurred_at, id";
    }

    private static final String WHERE = """
            WHERE (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))
              AND (CAST(:actorId AS uuid) IS NULL OR actor_id = CAST(:actorId AS uuid))
              AND (CAST(:entityType AS text) IS NULL OR entity_type = CAST(:entityType AS text))
              AND (CAST(:entityId AS uuid) IS NULL OR entity_id = CAST(:entityId AS uuid))
              AND (CAST(:actions AS text[]) IS NULL OR action::text = ANY (CAST(:actions AS text[])))
              AND (CAST(:service AS text) IS NULL OR service = CAST(:service AS text))
              AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz))
            """;

    private static Map<String, Object> bind(AuditQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("clientId", query.clientId());
        params.put("actorId", query.actorId());
        params.put("entityType", query.entityType());
        params.put("entityId", query.entityId());
        params.put("actions", query.actions().isEmpty() ? null : query.actions().toArray(String[]::new));
        params.put("service", query.service());
        params.put(
                "from", query.from() == null ? null : OffsetDateTime.ofInstant(query.from(), java.time.ZoneOffset.UTC));
        params.put("to", query.to() == null ? null : OffsetDateTime.ofInstant(query.to(), java.time.ZoneOffset.UTC));
        return params;
    }

    private long count(AuditQuery query) {
        return jdbc.sql("SELECT count(*) FROM audit_log " + WHERE)
                .params(bind(query))
                .query(Long.class)
                .single();
    }

    private static String csvRow(ResultSet rs) throws SQLException {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < COLUMNS.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csvCell(rs.getString(COLUMNS.get(i))));
        }
        return row.toString();
    }

    /** RFC 4180: quote when the value could otherwise end the field or the row. */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private String jsonRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : COLUMNS) {
            String value = rs.getString(column);
            if ("changed_fields".equals(column) || "context".equals(column)) {
                // Already JSON in the column; embedded as an object rather than as a string, so a
                // JSONL consumer can read it without a second parse.
                row.put(column, value == null ? null : readJson(value));
            } else {
                row.put(column, value);
            }
        }
        try {
            return objectMapper.writeValueAsString(row);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize an audit row", e);
        }
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Unreachable for a jsonb column, and a lie if it were silently dropped.
            throw new IllegalStateException("audit row holds invalid JSON", e);
        }
    }

    private Job insert(UUID idempotencyKey, AuditQuery query, String format, CurrentUser caller, long estimated) {
        String filter;
        try {
            filter = objectMapper.writeValueAsString(Map.of("summary", query.summary()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize an export filter", e);
        }
        return jdbc.sql("INSERT INTO audit_export_jobs"
                        + " (requested_by, filter, format, expires_at, idempotency_key, row_count)"
                        + " VALUES (:requestedBy, CAST(:filter AS jsonb), :format,"
                        + "         now() + make_interval(secs => :retentionSeconds), :key, NULL)"
                        + " RETURNING " + JOB_COLUMNS)
                .param("requestedBy", caller.id())
                .param("filter", filter)
                .param("format", format)
                .param("retentionSeconds", properties.retention().toSeconds())
                .param("key", idempotencyKey)
                .query(AuditExportService::job)
                .single()
                .withEstimate(estimated);
    }

    private static final String JOB_COLUMNS = """
            id, requested_by, filter::text AS filter, format, status::text AS status, row_count,
            storage_key, checksum_sha256, error_message, requested_at, started_at, completed_at,
            expires_at""";

    private Optional<Job> find(UUID jobId) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM audit_export_jobs WHERE id = :id")
                .param("id", jobId)
                .query(AuditExportService::job)
                .optional();
    }

    private Optional<Job> findByIdempotencyKey(UUID requestedBy, UUID key) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM audit_export_jobs"
                        + " WHERE requested_by = :requestedBy AND idempotency_key = :key")
                .param("requestedBy", requestedBy)
                .param("key", key)
                .query(AuditExportService::job)
                .optional();
    }

    private boolean markRunning(UUID jobId) {
        return jdbc.sql("UPDATE audit_export_jobs SET status = 'RUNNING', started_at = now()"
                                + " WHERE id = :id AND status = 'QUEUED'")
                        .param("id", jobId)
                        .update()
                == 1;
    }

    private void markCompleted(UUID jobId, String key, long rows, String checksumHex) {
        jdbc.sql("UPDATE audit_export_jobs SET status = 'COMPLETED', completed_at = now(),"
                        + " storage_key = :key, row_count = :rows,"
                        + " checksum_sha256 = decode(:checksum, 'hex') WHERE id = :id")
                .param("id", jobId)
                .param("key", key)
                .param("rows", rows)
                .param("checksum", checksumHex)
                .update();
    }

    private void markFailed(UUID jobId, String message) {
        jdbc.sql("UPDATE audit_export_jobs SET status = 'FAILED', completed_at = now(),"
                        + " error_message = :message WHERE id = :id")
                .param("id", jobId)
                .param("message", message)
                .update();
    }

    private static Integer progressPercent(Job job) {
        return switch (job.status()) {
            case "QUEUED" -> 0;
            // There is no row-by-row progress to report: the generator streams and does not stop to
            // count against an estimate that was only ever approximate. Saying 50 would be a made-up
            // number, and saying nothing would leave the UI with no bar at all.
            case "RUNNING" -> 50;
            case "COMPLETED" -> 100;
            default -> null;
        };
    }

    private static String filename(Job job) {
        return "audit-export-" + job.id() + "." + job.format().toLowerCase(java.util.Locale.ROOT) + ".gz";
    }

    private static Job job(ResultSet rs, int rowNum) throws SQLException {
        byte[] checksum = rs.getBytes("checksum_sha256");
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("requested_by", UUID.class),
                rs.getString("format"),
                rs.getString("status"),
                rs.getObject("row_count") == null ? null : rs.getLong("row_count"),
                rs.getString("storage_key"),
                checksum == null ? null : hex(checksum),
                rs.getString("error_message"),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                null);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn(
                    "could not delete a temporary export file: {}", e.getClass().getSimpleName());
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("EXPORT_JOB_NOT_FOUND", "Export job not found.");
    }

    private record Generated(long rows, String checksum) {}

    /** @param estimatedRows only set on the job the request just created; null when read back */
    public record Job(
            UUID id,
            UUID requestedBy,
            String format,
            String status,
            Long rowCount,
            String storageKey,
            String checksum,
            String errorMessage,
            Instant expiresAt,
            Long estimatedRows) {

        Job withEstimate(long estimate) {
            return new Job(
                    id, requestedBy, format, status, rowCount, storageKey, checksum, errorMessage, expiresAt, estimate);
        }
    }

    /** §8.3's status body. {@code downloadUrl} is present only while the job is {@code COMPLETED}. */
    public record JobView(
            UUID jobId,
            String status,
            Integer progressPercent,
            Long rowCount,
            URI downloadUrl,
            String checksumSha256,
            Instant expiresAt,
            String error) {}
}
