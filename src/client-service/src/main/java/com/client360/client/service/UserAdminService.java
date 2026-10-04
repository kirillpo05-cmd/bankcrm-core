package com.client360.client.service;

import com.client360.client.api.ClientErrorCodes;
import com.client360.client.client.WorkloadClient;
import com.client360.client.persistence.ClientRepository;
import com.client360.client.persistence.TeamAdminRepository;
import com.client360.client.persistence.UserAdminRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.web.ETags;
import com.client360.common.web.OffsetPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (SPEC.md §9.3): the list, the create, the patch, role assignment, lockout
 * recovery and the deactivation flow.
 *
 * <p>The rules that make this more than CRUD:
 *
 * <ul>
 *   <li><strong>RB-BR-06, no self-elevation.</strong> A user cannot grant themselves a role.
 *       Checked against the acting subject at request time, so holding {@code role:assign} is not
 *       enough — which is the whole point, since the only people who hold it could otherwise make
 *       themselves anything.
 *   <li><strong>RB-BR-08, the last admin.</strong> Removing the {@code ADMIN} role from the last
 *       active admin, or deactivating them, is refused. A system nobody can administer is
 *       unrecoverable, and no amount of care at the UI layer substitutes for refusing it here.
 *   <li><strong>RB-BR-07, referential safety.</strong> Deactivation is refused while the user owns
 *       clients, is the assignee of open tickets, or is a team's sole supervisor. The {@code 409}
 *       enumerates every blocker with its count, so an admin clears them in one pass instead of
 *       discovering them one failed attempt at a time. This is what makes
 *       {@code ON DELETE RESTRICT} on {@code owner_manager_id} a safety net rather than a wall.
 * </ul>
 */
@Service
public class UserAdminService {

    /** §9.3's {@code ?sort=} whitelist: API name to column. Anything else is {@code 400}. */
    private static final Map<String, String> SORTABLE = Map.of(
            "fullName", "full_name",
            "employeeNo", "employee_no",
            "email", "email",
            "createdAt", "created_at",
            "lastLoginAt", "last_login_at");

    /** RB-BR-14: a role change carries an account of itself. {@code ck_ur_reason} agrees. */
    private static final int MIN_REASON = 10;

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserAdminRepository users;
    private final TeamAdminRepository teams;
    private final ClientRepository clients;
    private final ClientAccess access;
    private final WorkloadClient workload;
    private final AuthEvents events;
    private final PasswordEncoder passwords;

    public UserAdminService(
            UserAdminRepository users,
            TeamAdminRepository teams,
            ClientRepository clients,
            ClientAccess access,
            WorkloadClient workload,
            AuthEvents events,
            PasswordEncoder passwords) {
        this.users = users;
        this.teams = teams;
        this.clients = clients;
        this.access = access;
        this.workload = workload;
        this.events = events;
        this.passwords = passwords;
    }

    // --------------------------------------------------------------------- read

    /** {@code GET /users} (§9.3). A supervisor sees their own team only, which is what TEAM means. */
    @Transactional(readOnly = true)
    public OffsetPage<UserAdminRepository.AdminUser> list(
            String q,
            String status,
            UUID teamId,
            String role,
            Integer page,
            Integer size,
            String sort,
            CurrentUser caller) {
        Scope scope = access.requireScope(caller, Permissions.USER_READ);
        int p = page == null ? 0 : page;
        int s = size == null ? OffsetPage.DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.validation("page", "must be zero or greater");
        }
        if (s < 1 || s > OffsetPage.MAX_SIZE) {
            throw ApiException.validation("size", "must be between 1 and " + OffsetPage.MAX_SIZE);
        }
        String column = SORTABLE.get(sort == null || sort.isBlank() ? "fullName" : sort);
        if (column == null) {
            throw ApiException.validation("sort", "must be one of " + SORTABLE.keySet());
        }
        if (status != null && !List.of("ACTIVE", "SUSPENDED", "DEACTIVATED").contains(status)) {
            throw ApiException.validation("status", "must be ACTIVE, SUSPENDED or DEACTIVATED");
        }

        if (scope == Scope.OWN) {
            // Nobody holds user:read at OWN in §9.2.8, but a custom role could, and "your own
            // record" is the only honest reading of it — so the list is that one row rather than a
            // team filter that would quietly match colleagues.
            return new OffsetPage<>(users.findById(caller.id()).map(List::of).orElseGet(List::of), 0, s, 1, 1, false);
        }
        List<UUID> scopeTeamIds = null;
        if (scope == Scope.TEAM) {
            Set<UUID> own = access.scopeTeamsOf(caller);
            if (own.isEmpty()) {
                // No team resolves to nothing. Dropping the filter would widen the list to every
                // employee in the bank, which is the opposite of what the scope says.
                return new OffsetPage<>(List.of(), p, s, 0, 0, false);
            }
            scopeTeamIds = List.copyOf(own);
        }

        UserAdminRepository.UserSearch criteria =
                new UserAdminRepository.UserSearch(blankToNull(q), status, teamId, blankToNull(role), scopeTeamIds);
        long total = users.countSearch(criteria);
        List<UserAdminRepository.AdminUser> content = users.search(criteria, column, isAscending(sort), s, p * s);
        int totalPages = (int) Math.ceil((double) total / s);
        return new OffsetPage<>(content, p, s, total, totalPages, (long) (p + 1) * s < total);
    }

    /** {@code GET /users/{id}} — {@code 404 USER_NOT_FOUND} out of scope, as §9.3 requires. */
    @Transactional(readOnly = true)
    public UserAdminRepository.AdminUser read(UUID id, CurrentUser caller) {
        Scope scope = access.requireScope(caller, Permissions.USER_READ);
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        requireVisible(user, scope, caller);
        return user;
    }

    // -------------------------------------------------------------------- write

    /**
     * {@code POST /users} (§9.3). The password is never in the request: the row gets a hash nobody
     * knows and {@code must_change_password}, so the account exists and cannot be signed into until
     * an invitation sets a real one.
     *
     * <p>The invitation email itself is not built. That is a gap, not a decision — but the gap fails
     * closed, because the stored hash is random.
     */
    @Transactional
    public UserAdminRepository.AdminUser create(
            String employeeNo, String email, String fullName, UUID primaryTeamId, CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_WRITE);
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (primaryTeamId != null && teams.findById(primaryTeamId).isEmpty()) {
            throw ApiException.validation("primaryTeamId", "no such team");
        }
        UserAdminRepository.AdminUser created = users.create(
                employeeNo.trim(),
                normalizedEmail,
                fullName.trim(),
                primaryTeamId,
                // One random UUID through the real encoder: 122 bits nobody holds, and the same
                // format every other hash has, so nothing downstream needs a special case for "no
                // password yet". Deliberately one and not two — bcrypt silently refuses an input
                // over 72 bytes, and two UUIDs with a separator is 73.
                passwords.encode(UUID.randomUUID().toString()));
        events.userCreated(created.id(), created.email(), created.fullName(), created.primaryTeamId());
        return created;
    }

    /**
     * {@code PATCH /users/{id}} with {@code If-Match} (§4.8).
     *
     * <p>RB-EC-09: moving someone's primary team is refused while they own clients in the old one.
     * {@code clients.team_id} is derived from the owner's primary team (CP-BR-03), so a silent move
     * would leave every client they own pointing at a team they have left — visible to the wrong
     * supervisor, and invisible to the right one.
     */
    @Transactional
    public UserAdminRepository.AdminUser update(
            UUID id, String ifMatch, String fullName, UUID primaryTeamId, boolean clearTeam, CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_WRITE);
        int version = ETags.requireIfMatch(ifMatch);
        UserAdminRepository.AdminUser current = users.findById(id).orElseThrow(UserAdminService::userNotFound);

        boolean movingTeam = clearTeam
                ? current.primaryTeamId() != null
                : primaryTeamId != null && !primaryTeamId.equals(current.primaryTeamId());
        if (movingTeam) {
            if (!clearTeam && teams.findById(primaryTeamId).isEmpty()) {
                throw ApiException.validation("primaryTeamId", "no such team");
            }
            int owned = users.ownedClientsInTeam(id, current.primaryTeamId());
            if (owned > 0) {
                throw ApiException.businessRule(
                                "This user still owns clients in their current team. Move the clients first.")
                        .detail(
                                "blocker",
                                "OWNED_CLIENTS_IN_CURRENT_TEAM",
                                "count",
                                owned,
                                "teamId",
                                current.primaryTeamId());
            }
        }

        UserAdminRepository.AdminUser saved = users.update(id, version, blankToNull(fullName), primaryTeamId, clearTeam)
                .orElseThrow(() -> ApiException.conflict(
                                ErrorCodes.VERSION_CONFLICT,
                                "This user was changed by someone else. Re-read and retry.")
                        .detail("currentVersion", current.version()));
        events.userUpdated(current, saved);
        return saved;
    }

    /** §9.3: clears the lockout RB-BR-11 applied. Idempotent — an unlocked account is already fine. */
    @Transactional
    public UserAdminRepository.AdminUser unlock(UUID id, CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_WRITE);
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        if (users.unlock(id)) {
            events.userUnlocked(id, user.email());
        }
        return users.findById(id).orElseThrow();
    }

    // --------------------------------------------------------------------- roles

    /**
     * {@code POST /users/{id}/roles} (§9.3, RB-BR-06, RB-BR-14).
     *
     * @throws ApiException {@code 422} on self-assignment, or an unknown role code
     */
    @Transactional
    public List<String> grantRole(UUID id, String roleCode, String rawReason, Instant expiresAt, CurrentUser caller) {
        access.requireScope(caller, Permissions.ROLE_ASSIGN);
        String reason = requireReason(rawReason);
        if (caller.id().equals(id)) {
            // RB-BR-06. The only users who hold role:assign are admins, so without this the rule
            // would be "an admin may become anything", which is not a rule.
            throw ApiException.businessRule("A user cannot grant themselves a role.")
                    .detail("reason", "SELF_ELEVATION_FORBIDDEN");
        }
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        if (!users.roleExists(roleCode)) {
            throw ApiException.validation("roleCode", "no such role");
        }
        if (!"ACTIVE".equals(user.status())) {
            throw ApiException.businessRule("Roles cannot be granted to an inactive user.")
                    .detail("status", user.status());
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            // ck_ur_expiry says the same thing; saying it here gives the admin a field name.
            throw ApiException.validation("expiresAt", "must be in the future");
        }
        if (users.grantRole(id, roleCode, caller.id(), reason, expiresAt)) {
            events.roleChanged(id, user.email(), roleCode, "GRANT", reason, caller.id());
        }
        return users.roleCodesOf(id);
    }

    /**
     * {@code DELETE /users/{id}/roles/{roleCode}} (§9.3, RB-BR-08).
     *
     * @throws ApiException {@code 422} when it would remove the user's last role, or the last admin
     */
    @Transactional
    public List<String> revokeRole(UUID id, String roleCode, String rawReason, CurrentUser caller) {
        access.requireScope(caller, Permissions.ROLE_ASSIGN);
        String reason = requireReason(rawReason);
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        List<String> held = users.roleCodesOf(id);
        if (!held.contains(roleCode)) {
            // Idempotent: a retry of a delete that already happened is not an error (RB-EC-06).
            return held;
        }
        if (held.size() == 1) {
            throw ApiException.businessRule("A user must keep at least one role. Grant a replacement first.")
                    .detail("blocker", "LAST_ROLE", "roleCode", roleCode);
        }
        requireNotLastAdmin(id, roleCode);
        if (users.revokeRole(id, roleCode)) {
            events.roleChanged(id, user.email(), roleCode, "REVOKE", reason, caller.id());
        }
        return users.roleCodesOf(id);
    }

    // -------------------------------------------------------------- deactivation

    /**
     * {@code POST /users/{id}/deactivate} (§9.3, RB-BR-07, RB-BR-08).
     *
     * <p>Every blocker is reported together, with counts. Reassignment of the user's clients happens
     * in the same transaction when {@code reassignClientsTo} is given, so the deactivation and the
     * handover cannot come apart — a user deactivated without their book moving is exactly the
     * orphaned-client state {@code ON DELETE RESTRICT} exists to prevent (CP-EC-06).
     */
    @Transactional
    public UserAdminRepository.AdminUser deactivate(
            UUID id, String rawReason, UUID reassignClientsTo, CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_WRITE);
        String reason = requireReason(rawReason);
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        if ("DEACTIVATED".equals(user.status())) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "This user is already deactivated.");
        }
        // RB-EC-05: the last admin cannot deactivate themselves, and the UI disables it beforehand —
        // but the rule lives here, because the UI is not a control.
        requireNotLastAdmin(id, ADMIN_ROLE);

        UserAdminRepository.Blockers blockers = users.blockers(id);
        int ownedClients = blockers.ownedClients();
        if (ownedClients > 0 && reassignClientsTo != null) {
            UserAdminRepository.AdminUser target = users.findById(reassignClientsTo)
                    .orElseThrow(() -> ApiException.validation("reassignClientsTo", "no such user"));
            if (!"ACTIVE".equals(target.status())) {
                throw ApiException.validation("reassignClientsTo", "must be an active user");
            }
            if (target.primaryTeamId() == null) {
                // CP-BR-03: team_id is derived from the owner's primary team, so an owner without one
                // leaves the clients unassignable to a team.
                throw ApiException.validation("reassignClientsTo", "must have a primary team (CP-BR-03)");
            }
            int moved = clients.reassignAllOwnedBy(id, reassignClientsTo, target.primaryTeamId(), caller.id());
            ownedClients -= moved;
        }

        List<Map<String, Object>> remaining = new ArrayList<>();
        if (ownedClients > 0) {
            remaining.add(blocker("OWNED_CLIENTS", ownedClients, "reassignClientsTo"));
        }
        if (blockers.soleSupervisorOf() > 0) {
            remaining.add(blocker("SOLE_TEAM_SUPERVISOR", blockers.soleSupervisorOf(), null));
        }
        int openTickets = workload.openTickets(id);
        if (openTickets > 0) {
            remaining.add(blocker("OPEN_TICKETS", openTickets, "reassignTasksTo"));
        }
        if (!remaining.isEmpty()) {
            ApiException refusal = ApiException.conflict(
                    ClientErrorCodes.USER_HAS_OWNED_CLIENTS,
                    "This user still has work assigned to them. Clear every blocker listed.");
            remaining.forEach(refusal::detail);
            throw refusal;
        }

        users.deactivate(id, caller.id());
        UserAdminRepository.AdminUser deactivated = users.findById(id).orElseThrow();
        events.userDeactivated(deactivated, reason, caller.id());
        return deactivated;
    }

    /** {@code POST /users/{id}/reactivate} — {@code ACTIVE} again, forced to set a password, no clients back. */
    @Transactional
    public UserAdminRepository.AdminUser reactivate(UUID id, String rawReason, CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_WRITE);
        String reason = requireReason(rawReason);
        UserAdminRepository.AdminUser user = users.findById(id).orElseThrow(UserAdminService::userNotFound);
        if (!"DEACTIVATED".equals(user.status())) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "This user is not deactivated.")
                    .detail("status", user.status());
        }
        users.reactivate(id);
        UserAdminRepository.AdminUser restored = users.findById(id).orElseThrow();
        events.userReactivated(restored, reason, caller.id());
        return restored;
    }

    // ------------------------------------------------------------------ helpers

    private void requireNotLastAdmin(UUID id, String roleCode) {
        if (!ADMIN_ROLE.equals(roleCode) || !users.roleCodesOf(id).contains(ADMIN_ROLE)) {
            return;
        }
        if (users.otherActiveAdmins(id) == 0) {
            throw ApiException.businessRule("At least one active administrator must remain. Appoint another one first.")
                    .detail("blocker", "LAST_ACTIVE_ADMIN");
        }
    }

    /**
     * Visibility of one user, by the caller's {@code user:read} scope.
     *
     * <p>{@code TEAM} is the caller's own teams; a user with no primary team — an admin, an auditor —
     * is visible only at {@code ALL}. A supervisor has no business reading the admin's record, and
     * {@code 404} rather than {@code 403} keeps that consistent with ER-01 everywhere else.
     */
    private void requireVisible(UserAdminRepository.AdminUser user, Scope scope, CurrentUser caller) {
        boolean visible =
                switch (scope) {
                    case ALL -> true;
                    case TEAM ->
                        user.primaryTeamId() != null
                                && access.scopeTeamsOf(caller).contains(user.primaryTeamId());
                    case OWN -> caller.id().equals(user.id());
                };
        if (!visible) {
            throw userNotFound();
        }
    }

    private static Map<String, Object> blocker(String code, int count, String clearedBy) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("blocker", code);
        entry.put("count", count);
        if (clearedBy != null) {
            entry.put("clearedBy", clearedBy);
        }
        return entry;
    }

    private static String requireReason(String raw) {
        String reason = raw == null ? "" : raw.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        return reason;
    }

    private static ApiException userNotFound() {
        return ApiException.notFound(ClientErrorCodes.USER_NOT_FOUND, "User not found or not in your scope.");
    }

    private static boolean isAscending(String sort) {
        return !"lastLoginAt".equals(sort) && !"createdAt".equals(sort);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
