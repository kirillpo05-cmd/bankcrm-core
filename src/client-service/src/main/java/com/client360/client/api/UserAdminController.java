package com.client360.client.api;

import com.client360.client.persistence.UserAdminRepository;
import com.client360.client.service.UserAdminService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import com.client360.common.web.OffsetPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration (SPEC.md §9.3, RB-US-02 through RB-US-04).
 *
 * <p>No endpoint deletes a user, and none may be added: RB-BR-15 keeps every {@code author_id} and
 * {@code created_by} reference resolvable years later, which is what makes an audit trail worth
 * keeping for seven years.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) {
        this.users = users;
    }

    /** {@code user:read}. A supervisor sees their own team only. */
    @GetMapping
    public OffsetPage<UserResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            CurrentUser caller) {
        OffsetPage<UserAdminRepository.AdminUser> found = users.list(q, status, teamId, role, page, size, sort, caller);
        return new OffsetPage<>(
                found.content().stream().map(UserResponse::of).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages(),
                found.hasNext());
    }

    /** The {@code ETag} is what a later {@code PATCH} must echo in {@code If-Match} (§4.8). */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> read(@PathVariable UUID id, CurrentUser caller) {
        UserAdminRepository.AdminUser user = users.read(id, caller);
        return ResponseEntity.ok().eTag(ETags.of(user.version())).body(UserResponse.of(user));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest body, CurrentUser caller) {
        UserAdminRepository.AdminUser created =
                users.create(body.employeeNo(), body.email(), body.fullName(), body.primaryTeamId(), caller);
        return ResponseEntity.created(java.net.URI.create("/api/v1/users/" + created.id()))
                .eTag(ETags.of(created.version()))
                .body(UserResponse.of(created));
    }

    /**
     * @param primaryTeamId present and null in the JSON clears it; absent leaves it alone. The
     *     difference is what makes this a merge-patch (§4.6), and it is carried by
     *     {@code clearPrimaryTeam} because a record cannot tell absent from null.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<UserResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateUserRequest body,
            CurrentUser caller) {
        UserAdminRepository.AdminUser saved = users.update(
                id,
                ifMatch,
                body.fullName(),
                body.primaryTeamId(),
                Boolean.TRUE.equals(body.clearPrimaryTeam()),
                caller);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(UserResponse.of(saved));
    }

    /** {@code role:assign}. RB-BR-06 refuses a self-assignment whoever the caller is. */
    @PostMapping("/{id}/roles")
    public List<String> grantRole(
            @PathVariable UUID id, @Valid @RequestBody GrantRoleRequest body, CurrentUser caller) {
        return users.grantRole(id, body.roleCode(), body.reason(), body.expiresAt(), caller);
    }

    /** RB-BR-08: refuses the last role, and the last active admin. */
    @DeleteMapping("/{id}/roles/{roleCode}")
    public List<String> revokeRole(
            @PathVariable UUID id, @PathVariable String roleCode, @RequestParam String reason, CurrentUser caller) {
        return users.revokeRole(id, roleCode, reason, caller);
    }

    /** RB-BR-07: the {@code 409} enumerates every blocker with its count. */
    @PostMapping("/{id}/deactivate")
    public UserResponse deactivate(
            @PathVariable UUID id, @Valid @RequestBody DeactivateRequest body, CurrentUser caller) {
        return UserResponse.of(users.deactivate(id, body.reason(), body.reassignClientsTo(), caller));
    }

    /** Restores {@code ACTIVE}, forces a password change, grants no clients back. */
    @PostMapping("/{id}/reactivate")
    public UserResponse reactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body, CurrentUser caller) {
        return UserResponse.of(users.reactivate(id, body.reason(), caller));
    }

    /** Clears the lockout RB-BR-11 applied after five failures. */
    @PostMapping("/{id}/unlock")
    public UserResponse unlock(@PathVariable UUID id, CurrentUser caller) {
        return UserResponse.of(users.unlock(id, caller));
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 32) String employeeNo,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String fullName,
            UUID primaryTeamId) {}

    public record UpdateUserRequest(@Size(max = 200) String fullName, UUID primaryTeamId, Boolean clearPrimaryTeam) {}

    /** @param expiresAt {@code null} is permanent; a date is cover for an absence (§9.2.7) */
    public record GrantRoleRequest(
            @NotBlank @Size(max = 32) String roleCode,
            @NotBlank @Size(min = 10, max = 2000) String reason,
            Instant expiresAt) {}

    /**
     * @param reassignClientsTo who takes over the leaver's book. Named in the request rather than
     *     chosen by the service: handing forty clients to someone is a decision, not a default.
     */
    public record DeactivateRequest(
            @NotBlank @Size(min = 10, max = 2000) String reason, UUID reassignClientsTo, UUID reassignTasksTo) {}

    public record ReasonRequest(
            @NotBlank @Size(min = 10, max = 2000) String reason) {}

    /**
     * No password hash, and no {@code failedLoginCount} for anyone but an administrator to see —
     * this endpoint is already behind {@code user:read}, and the lockout fields are what an admin
     * needs to answer "why can this person not sign in".
     */
    public record UserResponse(
            UUID id,
            String employeeNo,
            String email,
            String fullName,
            String status,
            UUID primaryTeamId,
            boolean locked,
            Instant lockedUntil,
            int failedLoginCount,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant deactivatedAt,
            Instant createdAt,
            int version) {

        static UserResponse of(UserAdminRepository.AdminUser user) {
            return new UserResponse(
                    user.id(),
                    user.employeeNo(),
                    user.email(),
                    user.fullName(),
                    user.status(),
                    user.primaryTeamId(),
                    user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now()),
                    user.lockedUntil(),
                    user.failedLoginCount(),
                    user.mustChangePassword(),
                    user.lastLoginAt(),
                    user.deactivatedAt(),
                    user.createdAt(),
                    user.version());
        }
    }
}
