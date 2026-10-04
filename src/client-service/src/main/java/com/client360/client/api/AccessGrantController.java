package com.client360.client.api;

import com.client360.client.persistence.AccessGrantRepository;
import com.client360.client.service.AccessGrantService;
import com.client360.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Break-glass access (SPEC.md §9.3, RB-US-05): request, approve, revoke, list.
 *
 * <p>No {@code Idempotency-Key} on the create, unlike every other {@code POST} in this service. A
 * duplicate request is not a duplicate record here: the second one is refused with
 * {@code 409 ACCESS_GRANT_EXISTS} by the outstanding-grant check, which is a stronger guarantee than
 * replaying a stored response — it holds for two different requests a day apart, not just a retry of
 * the same one.
 */
@RestController
@RequestMapping("/api/v1/access-grants")
public class AccessGrantController {

    private final AccessGrantService grants;

    public AccessGrantController(AccessGrantService grants) {
        this.grants = grants;
    }

    @PostMapping
    public ResponseEntity<GrantResponse> request(@Valid @RequestBody CreateGrantRequest body, CurrentUser caller) {
        AccessGrantRepository.Grant created =
                grants.request(body.clientId(), body.reason(), body.requestedHours(), caller);
        return ResponseEntity.created(java.net.URI.create("/api/v1/access-grants/" + created.id()))
                .body(GrantResponse.of(created));
    }

    /** RB-BR-09: a second person, never the requester. */
    @PostMapping("/{id}/approve")
    public GrantResponse approve(@PathVariable UUID id, CurrentUser caller) {
        return GrantResponse.of(grants.approve(id, caller));
    }

    /** A denial before approval, or a release after it — {@code status} says which it was. */
    @PostMapping("/{id}/revoke")
    public GrantResponse revoke(@PathVariable UUID id, CurrentUser caller) {
        return GrantResponse.of(grants.revoke(id, caller));
    }

    /**
     * @param userId whose grants to list; the caller's own needs only {@code access:request},
     *     anyone else's needs {@code access:approve} over that client's team
     */
    @GetMapping
    public List<GrantResponse> search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false, defaultValue = "false") boolean active,
            CurrentUser caller) {
        return grants.search(userId, clientId, active, caller).stream()
                .map(GrantResponse::of)
                .toList();
    }

    /**
     * @param requestedHours 1–8; defaults to the 8-hour ceiling when omitted
     */
    public record CreateGrantRequest(
            @NotNull UUID clientId,
            @NotNull @Size(min = 20, max = 2000) String reason,
            Integer requestedHours) {}

    /**
     * @param status derived from the timestamps, never stored: {@code PENDING_APPROVAL},
     *     {@code ACTIVE}, {@code DENIED}, {@code REVOKED} or {@code EXPIRED}
     * @param expiresAt measured from the request, not the approval — a slow approval shortens the
     *     access it grants, because {@code ck_ag_window} compares the two (§9.2.6)
     * @param useCount how many reads this grant has authorized (AT-BR-13), so a pattern of repeated
     *     break-glass on one client is visible in S-RB-05's history
     */
    public record GrantResponse(
            UUID id,
            UUID userId,
            UUID clientId,
            String reason,
            String status,
            Instant requestedAt,
            UUID approvedBy,
            Instant approvedAt,
            Instant expiresAt,
            int useCount) {

        static GrantResponse of(AccessGrantRepository.Grant grant) {
            return new GrantResponse(
                    grant.id(),
                    grant.userId(),
                    grant.clientId(),
                    grant.reason(),
                    grant.status(),
                    grant.requestedAt(),
                    grant.approvedBy(),
                    grant.approvedAt(),
                    grant.expiresAt(),
                    grant.useCount());
        }
    }
}
