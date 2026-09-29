package com.client360.audit.api;

import com.client360.audit.service.AuditSearchService;
import com.client360.audit.service.HealthService;
import com.client360.audit.verify.ChainVerifier;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.web.KeysetPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of the audit log (SPEC.md §8.3).
 *
 * <p>There is no {@code POST /audit} and there must not be one: entries are created only by
 * consuming Kafka (AT-BR-02). {@code POST /audit/verify} is a POST because it takes a body and does
 * real work, not because it writes anything.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditSearchService search;
    private final ChainVerifier verifier;
    private final HealthService health;
    private final AccessPolicy accessPolicy;

    public AuditController(
            AuditSearchService search, ChainVerifier verifier, HealthService health, AccessPolicy accessPolicy) {
        this.search = search;
        this.verifier = verifier;
        this.health = health;
        this.accessPolicy = accessPolicy;
    }

    /**
     * {@code GET /audit} (AT-US-01, AT-US-02, AT-US-05) — search the log.
     *
     * <p>Keyset-paginated, and never offset: the audit log only grows, and an {@code OFFSET} into
     * seven years of it is a scan wearing a page number (§4.5).
     *
     * <p><strong>Not yet audited as a read of its own</strong> (AT-BR-10). Doing so honestly needs
     * the round trip described in §8.4 — this service may not insert into {@code audit_log}
     * directly, because AT-BR-02 is what makes a forged entry require the broker as well. The path
     * is an {@code audit.events} topic this service produces through its own outbox and then
     * consumes. Until that exists the endpoint works and the self-audit does not, which is stated
     * here rather than papered over with a direct insert that would quietly undo AT-BR-02.
     */
    @GetMapping
    public KeysetPage<AuditEntryView> search(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) List<String> action,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            CurrentUser caller) {
        AuditQuery query = new AuditQuery(clientId, actorId, entityType, entityId, action, service, from, to);
        return search.search(query, cursor, limit, caller);
    }

    /**
     * {@code POST /audit/verify} (AT-US-03).
     *
     * <p><strong>A detected break is a {@code 200} with {@code verified: false}</strong> (S-AT-03).
     * The request succeeded; the data did not. Mapping a break to a {@code 500} would leave an
     * auditor unable to tell "the log is intact" from "the check could not run" — opposite answers
     * that must never share a status code.
     */
    @PostMapping("/verify")
    public ChainVerifier.VerificationReport verify(
            @RequestBody(required = false) VerifyRequest request, CurrentUser caller) {
        requireScopeAll(caller, Permissions.AUDIT_VERIFY);
        VerifyRequest query = request == null ? new VerifyRequest(null, null, null, null, null) : request;
        if (query.isUnbounded()) {
            // Seven years of log is not something to start walking by accident (AT-EC-14's reasoning
            // applied to verification rather than to search).
            throw ApiException.validation("chainId", "a chain or a time range is required");
        }
        return verifier.verify(query.chainId(), query.fromSeq(), query.toSeq(), query.from(), query.to());
    }

    /**
     * {@code GET /audit/health} (AT-US-06). {@code 503} when UNHEALTHY, so an orchestrator reacts
     * to a pipeline that has stopped keeping up rather than waiting for someone to read a dashboard.
     */
    @GetMapping("/health")
    public ResponseEntity<AuditHealth> health(CurrentUser caller) {
        requireScopeAll(caller, Permissions.AUDIT_READ);
        AuditHealth current = health.current();
        HttpStatus status =
                current.status() == AuditHealth.Status.UNHEALTHY ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(current);
    }

    /**
     * Both endpoints are admin-level. Asked as permission + scope, never as a role string
     * (rule 5): holding the permission at {@code ALL} is what "admin" means here, and AT-BR-11
     * keeps managers out of the audit log entirely by not granting it at any scope.
     */
    private void requireScopeAll(CurrentUser caller, String permission) {
        if (!accessPolicy
                .scopeOf(caller, permission)
                .filter(scope -> scope == Scope.ALL)
                .isPresent()) {
            throw ApiException.forbidden(
                            ErrorCodes.ROLE_REQUIRED, "This action requires " + permission + " at ALL scope.")
                    .detail("permission", permission);
        }
    }
}
