package com.client360.client.service;

import com.client360.client.api.ClientErrorCodes;
import com.client360.client.persistence.TeamAdminRepository;
import com.client360.client.persistence.UserAdminRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.outbox.ChangedFields;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.web.ETags;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team administration (SPEC.md §9.3). A team is the unit of the {@code TEAM} data scope
 * (RB-BR-02), so every write here changes who can see whose clients — which is why the
 * membership changes are audited as carefully as a role change.
 */
@Service
public class TeamAdminService {

    private static final String SUPERVISOR_ROLE = "SUPERVISOR";

    private final TeamAdminRepository teams;
    private final UserAdminRepository users;
    private final ClientAccess access;
    private final AuthEvents events;

    public TeamAdminService(
            TeamAdminRepository teams, UserAdminRepository users, ClientAccess access, AuthEvents events) {
        this.teams = teams;
        this.users = users;
        this.access = access;
        this.events = events;
    }

    /** {@code GET /teams} (§9.3) — {@code user:read}, with member counts. */
    @Transactional(readOnly = true)
    public List<TeamAdminRepository.TeamView> list(CurrentUser caller) {
        access.requireScope(caller, Permissions.USER_READ);
        return teams.list();
    }

    @Transactional
    public TeamAdminRepository.Team create(
            String name, String code, String timezone, UUID parentTeamId, UUID supervisorId, CurrentUser caller) {
        access.requireScope(caller, Permissions.TEAM_MANAGE);
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (parentTeamId != null && teams.findById(parentTeamId).isEmpty()) {
            throw ApiException.validation("parentTeamId", "no such team");
        }
        if (supervisorId != null) {
            requireSupervisorEligible(supervisorId);
        }
        TeamAdminRepository.Team created =
                teams.create(name.trim(), normalizedCode, blankToNull(timezone), parentTeamId, supervisorId);
        events.teamCreated(created.id(), created.code(), created.name());
        return created;
    }

    /**
     * {@code PATCH /teams/{id}} with {@code If-Match} (§4.8).
     *
     * <p>Two {@code 422}s of its own: a supervisor who does not hold the {@code SUPERVISOR} role,
     * and a parent that would close a cycle.
     */
    @Transactional
    public TeamAdminRepository.Team update(
            UUID id,
            String ifMatch,
            String name,
            UUID supervisorId,
            boolean clearSupervisor,
            UUID parentTeamId,
            boolean clearParent,
            String timezone,
            Boolean active,
            CurrentUser caller) {
        access.requireScope(caller, Permissions.TEAM_MANAGE);
        int version = ETags.requireIfMatch(ifMatch);
        TeamAdminRepository.Team current = teams.findById(id).orElseThrow(TeamAdminService::teamNotFound);

        if (supervisorId != null && !clearSupervisor) {
            requireSupervisorEligible(supervisorId);
        }
        if (parentTeamId != null && !clearParent) {
            if (teams.findById(parentTeamId).isEmpty()) {
                throw ApiException.validation("parentTeamId", "no such team");
            }
            if (teams.wouldCycle(id, parentTeamId)) {
                throw ApiException.businessRule("That parent would make the team its own ancestor.")
                        .detail("blocker", "TEAM_HIERARCHY_CYCLE", "parentTeamId", parentTeamId);
            }
        }

        TeamAdminRepository.Team saved = teams.update(
                        id,
                        version,
                        blankToNull(name),
                        supervisorId,
                        clearSupervisor,
                        parentTeamId,
                        clearParent,
                        blankToNull(timezone),
                        active)
                .orElseThrow(() -> ApiException.conflict(
                                ErrorCodes.VERSION_CONFLICT,
                                "This team was changed by someone else. Re-read and retry.")
                        .detail("currentVersion", current.version()));

        events.teamUpdated(
                id,
                ChangedFields.create()
                        .put("name", current.name(), saved.name())
                        .put("supervisorId", current.supervisorId(), saved.supervisorId())
                        .put("parentTeamId", current.parentTeamId(), saved.parentTeamId())
                        .put("timezone", current.timezone(), saved.timezone())
                        .put("active", current.active(), saved.active()));
        return saved;
    }

    /** {@code POST /teams/{id}/members} (§9.3). Idempotent, so RB-EC-06's retry converges. */
    @Transactional
    public void addMember(UUID teamId, UUID userId, boolean primary, CurrentUser caller) {
        access.requireScope(caller, Permissions.TEAM_MANAGE);
        teams.findById(teamId).orElseThrow(TeamAdminService::teamNotFound);
        UserAdminRepository.AdminUser user = users.findById(userId)
                .orElseThrow(() ->
                        ApiException.notFound(ClientErrorCodes.USER_NOT_FOUND, "User not found or not in your scope."));
        if (!"ACTIVE".equals(user.status())) {
            throw ApiException.businessRule("An inactive user cannot be added to a team.")
                    .detail("status", user.status());
        }
        if (teams.addMember(teamId, userId, primary)) {
            events.teamMembershipChanged(teamId, userId, true, caller.id());
        }
    }

    /**
     * {@code DELETE /teams/{id}/members/{userId}} (§9.3) — sets {@code left_at}.
     *
     * <p>Two refusals. While the user owns clients in that team, because {@code clients.team_id} is
     * derived from the owner's primary team (CP-BR-03) and removing the membership would leave those
     * clients in a team their owner has left — visible to the wrong supervisor and invisible to the
     * right one. And when it is their only membership, because a user in no team has no
     * {@code TEAM} scope and cannot be assigned a client at all.
     */
    @Transactional
    public void removeMember(UUID teamId, UUID userId, CurrentUser caller) {
        access.requireScope(caller, Permissions.TEAM_MANAGE);
        teams.findById(teamId).orElseThrow(TeamAdminService::teamNotFound);
        if (!teams.isMember(teamId, userId)) {
            // Idempotent: a membership that already ended is the state the caller asked for.
            return;
        }
        int owned = users.ownedClientsInTeam(userId, teamId);
        if (owned > 0) {
            throw ApiException.businessRule("This user still owns clients in this team. Move them first.")
                    .detail("blocker", "OWNED_CLIENTS_IN_TEAM", "count", owned);
        }
        if (teams.otherMemberships(userId, teamId) == 0) {
            throw ApiException.businessRule("This is the user's only team. Add them to another one first.")
                    .detail("blocker", "LAST_TEAM_MEMBERSHIP");
        }
        if (teams.removeMember(teamId, userId)) {
            events.teamMembershipChanged(teamId, userId, false, caller.id());
        }
    }

    /**
     * §9.3: a team's supervisor must hold the {@code SUPERVISOR} role.
     *
     * <p>This reads a role string, and rule 5 says never to authorize on one — which this is not.
     * The rule is about the <em>caller</em>: who may act. This is a statement about the
     * <em>target</em>, and "the person you are naming as supervisor must actually be a supervisor"
     * cannot be expressed as a permission the acting subject holds.
     */
    private void requireSupervisorEligible(UUID supervisorId) {
        UserAdminRepository.AdminUser supervisor =
                users.findById(supervisorId).orElseThrow(() -> ApiException.validation("supervisorId", "no such user"));
        if (!"ACTIVE".equals(supervisor.status())) {
            throw ApiException.businessRule("A team's supervisor must be an active user.")
                    .detail("blocker", "SUPERVISOR_INACTIVE");
        }
        if (!users.roleCodesOf(supervisorId).contains(SUPERVISOR_ROLE)) {
            throw ApiException.businessRule("That user does not hold the SUPERVISOR role.")
                    .detail("blocker", "SUPERVISOR_ROLE_REQUIRED", "roleCode", SUPERVISOR_ROLE);
        }
    }

    private static ApiException teamNotFound() {
        return ApiException.notFound(ClientErrorCodes.TEAM_NOT_FOUND, "Team not found.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
