package com.client360.audit.api;

import com.client360.audit.export.AuditExportService;
import com.client360.common.api.ApiException;
import com.client360.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit exports (SPEC.md §8.3, AT-US-04).
 *
 * <p>Still reads only, despite the {@code POST}: an export request creates a job and a file, and
 * adds nothing to the log. AT-BR-02's rule is that audit <em>entries</em> come only from Kafka, and
 * this endpoint creates none — the entry recording that someone asked for an export goes out
 * through the outbox like every other self-audit (AT-BR-10).
 */
@RestController
@RequestMapping("/api/v1/audit/exports")
public class AuditExportController {

    private final AuditExportService exports;

    public AuditExportController(AuditExportService exports) {
        this.exports = exports;
    }

    /**
     * {@code 202 Accepted} with a {@code Location} — the file does not exist yet, and a regulatory
     * request can cover millions of rows, so the caller polls rather than waits (AT-EC-09).
     */
    @PostMapping
    public ResponseEntity<AcceptedExport> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ExportRequest body,
            CurrentUser caller) {
        AuditQuery query =
                body.filter() == null ? AuditQuery.unfiltered() : body.filter().toQuery();
        AuditExportService.Job job =
                exports.request(parseKey(idempotencyKey), query, body.format(), body.reason(), caller);
        return ResponseEntity.accepted()
                .location(java.net.URI.create("/api/v1/audit/exports/" + job.id()))
                .body(new AcceptedExport(job.id(), job.status(), job.estimatedRows(), job.expiresAt()));
    }

    /** The download link is minted here and lives fifteen minutes (§8.3). */
    @GetMapping("/{jobId}")
    public AuditExportService.JobView status(@PathVariable UUID jobId, CurrentUser caller) {
        return exports.status(jobId, caller);
    }

    /**
     * Optional, unlike every other create in the system.
     *
     * <p>§4.6 requires the header on a create because a duplicate would make a second client or a
     * second interaction. A duplicate export makes a second copy of a file nobody has downloaded
     * yet, and the five-an-hour limit already bounds the damage — so a caller that sends the header
     * gets the same job back, and one that does not is not refused for it.
     */
    private static UUID parseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawKey.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Idempotency-Key", "must be a client-generated UUID");
        }
    }

    /**
     * @param reason §8.3: at least 20 characters. An export of the audit log without a stated
     *     purpose is the one thing in this module that cannot be audited after the fact, because
     *     the file leaves the system
     */
    public record ExportRequest(
            ExportFilter filter,
            @Size(max = 8) String format,
            @NotBlank @Size(min = 20, max = 2000) String reason) {}

    /** The same filter {@code GET /audit} takes, as a body rather than a query string. */
    public record ExportFilter(
            UUID clientId,
            UUID actorId,
            String entityType,
            UUID entityId,
            List<String> action,
            String service,
            Instant from,
            Instant to) {

        AuditQuery toQuery() {
            return new AuditQuery(clientId, actorId, entityType, entityId, action, service, from, to);
        }
    }

    /** @param estimatedRows what the filter matches now; the export may differ if rows arrive */
    public record AcceptedExport(UUID jobId, String status, Long estimatedRows, Instant expiresAt) {}
}
