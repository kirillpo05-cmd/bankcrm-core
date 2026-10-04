package com.client360.client.service;

import com.client360.client.domain.Client;
import com.client360.client.persistence.AccessGrantRepository;
import com.client360.client.persistence.ClientRepository;
import com.client360.client.persistence.TeamRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Break-glass access (SPEC.md §9.3, RB-US-05, RB-BR-04, RB-BR-09).
 *
 * <p>A manager covering an urgent call for an absent colleague needs that one client, now, and an
 * admin ticket is not an answer at 16:40. So the grant is self-service to request, requires a second
 * person to approve, covers exactly one client, expires on its own, and is counted every time it is
 * used — which is what makes it safe to hand out without an approval committee.
 *
 * <p>Three things are enforced below rather than trusted:
 *
 * <ul>
 *   <li><strong>The requester is never the approver</strong> (RB-BR-09). Checked at request time
 *       against the acting subject, and backed by {@code ck_ag_separation} so no future code path
 *       can talk its way around it.
 *   <li><strong>A grant is refused for a client already in scope.</strong> Not because it would be
 *       harmful, but because it would be a lie: the audit trail would show break-glass on a client
 *       the caller could always read, and a review that cannot trust its own records is worthless.
 *   <li><strong>The approver must have the client in their own scope.</strong> Holding
 *       {@code access:approve} is not enough — a supervisor approves for their team, which is
 *       exactly what scope means.
 * </ul>
 */
@Service
public class AccessGrantService {

    /** §9.3: 1–8 hours. The ceiling is also {@code ck_ag_window}, which is what makes it real. */
    private static final int MIN_HOURS = 1;

    private static final int MAX_HOURS = 8;

    /** §9.3: an irreversible, audited step needs an account of itself. */
    private static final int MIN_REASON = 20;

    private final AccessGrantRepository grants;
    private final ClientRepository clients;
    private final TeamRepository teams;
    private final ClientAccess access;
    private final AuthEvents events;

    public AccessGrantService(
            AccessGrantRepository grants,
            ClientRepository clients,
            TeamRepository teams,
            ClientAccess access,
            AuthEvents events) {
        this.grants = grants;
        this.clients = clients;
        this.teams = teams;
        this.access = access;
        this.events = events;
    }

    /**
     * {@code POST /access-grants} (§9.3).
     *
     * <p>This endpoint is the second deliberate exception to ER-01, after {@code lookup}: it confirms
     * that a client the caller cannot see exists. It has to — a feature for requesting access to a
     * client outside your scope cannot pretend that client is absent. The exception is narrow (an
     * exact id, supplied by the caller, who already learned of it through {@code lookup}) and every
     * request is audited against the client id.
     *
     * @throws ApiException {@code 404} when no such client exists; {@code 409} when a request or
     *     grant is already outstanding; {@code 422} when the client is already in scope
     */
    @Transactional
    public AccessGrantRepository.Grant request(
            UUID clientId, String rawReason, Integer requestedHours, CurrentUser caller) {
        access.requireScope(caller, Permissions.ACCESS_REQUEST);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation("reason", "must be at least " + MIN_REASON + " characters");
        }
        int hours = requestedHours == null ? MAX_HOURS : requestedHours;
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw ApiException.validation("requestedHours", "must be between " + MIN_HOURS + " and " + MAX_HOURS);
        }

        Client client = clients.findById(clientId).orElseThrow(ClientAccess::notFound);
        // The question is whether they can already *read* this client, so it is asked against the
        // scope of client:read. Asking it against the scope of access:request would be a different
        // question that happens to give the same answer for a manager, where both are OWN — and a
        // wrong answer for any role where they differ.
        boolean alreadyReadable = access.scopeOf(caller, Permissions.CLIENT_READ)
                .filter(readScope -> access.covers(readScope, client, caller, Permissions.CLIENT_READ))
                .isPresent();
        if (alreadyReadable) {
            // Already readable — by role scope, or by a grant that is already running. Either way
            // there is nothing to grant, and recording one would put a break-glass entry in the log
            // for access the caller already had.
            throw ApiException.businessRule("This client is already in your scope.");
        }
        grants.findOutstanding(caller.id(), clientId).ifPresent(existing -> {
            throw ApiException.conflict("ACCESS_GRANT_EXISTS", "A request for this client is already outstanding.")
                    .detail("grantId", existing.id(), "status", existing.status());
        });

        AccessGrantRepository.Grant created = grants.create(caller.id(), clientId, reason, hours);
        events.accessRequested(created.id(), caller.id(), clientId, reason, created.expiresAt());
        return created;
    }

    /**
     * {@code POST /access-grants/{id}/approve} (§9.3, RB-BR-09).
     *
     * @throws ApiException {@code 403} when the approver is the requester; {@code 409} when the grant
     *     is no longer pending
     */
    @Transactional
    public AccessGrantRepository.Grant approve(UUID grantId, CurrentUser caller) {
        AccessGrantRepository.Grant grant = requireApprovable(grantId, caller);
        if (grant.userId().equals(caller.id())) {
            // RB-BR-09. A supervisor may legitimately need access to a client outside their own
            // team, and the answer is that someone else approves it — RB-EC-04 routes such a request
            // to an admin, who holds access:approve at ALL scope and is therefore never the
            // requester's only option.
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED, "A break-glass request cannot be approved by its requester.")
                    .detail("reason", "SELF_APPROVAL_FORBIDDEN");
        }
        if (!grants.approve(grantId, caller.id())) {
            throw ApiException.conflict(
                            ErrorCodes.ILLEGAL_STATE_TRANSITION, "This request is no longer awaiting approval.")
                    .detail("status", grant.status());
        }
        events.accessApproved(grantId, grant.userId(), grant.clientId(), caller.id());
        return grants.findById(grantId).orElseThrow();
    }

    /**
     * {@code POST /access-grants/{id}/revoke} (§9.3) — a denial before approval, or a release after
     * it. The requester may always end their own grant: S-RB-04's "Release access" is the honest way
     * to stop holding access you no longer need, and nobody should need permission for that.
     */
    @Transactional
    public AccessGrantRepository.Grant revoke(UUID grantId, CurrentUser caller) {
        AccessGrantRepository.Grant grant = grants.findById(grantId).orElseThrow(ClientAccess::notFound);
        if (!grant.userId().equals(caller.id())) {
            requireApprovable(grantId, caller);
        }
        if (!grants.revoke(grantId, caller.id())) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "This grant is already closed.")
                    .detail("status", grant.status());
        }
        AccessGrantRepository.Grant revoked = grants.findById(grantId).orElseThrow();
        events.accessRevoked(
                grantId, grant.userId(), grant.clientId(), caller.id(), revoked.status(), revoked.useCount());
        return revoked;
    }

    /**
     * {@code GET /access-grants} (§9.3) — the supervisor's approval queue and the compliance review
     * list.
     *
     * <p>Two callers with two different rights. Anyone holding {@code access:request} may list their
     * own grants, because S-RB-04 has to show them the status of what they asked for. Listing anyone
     * else's needs {@code access:approve}, and at {@code TEAM} scope it is limited to grants on the
     * caller's own team's clients — a supervisor's queue, not the bank's.
     */
    @Transactional(readOnly = true)
    public List<AccessGrantRepository.Grant> search(
            UUID userId, UUID clientId, boolean activeOnly, CurrentUser caller) {
        boolean ownOnly = caller.id().equals(userId);
        if (ownOnly) {
            access.requireScope(caller, Permissions.ACCESS_REQUEST);
            return grants.search(caller.id(), clientId, activeOnly, null);
        }
        Scope scope = access.requireScope(caller, Permissions.ACCESS_APPROVE);
        List<UUID> teamIds = scope == Scope.ALL ? null : List.copyOf(teams.scopeTeamsOf(caller.id()));
        if (teamIds != null && teamIds.isEmpty()) {
            // No team resolved means no queue, never every queue.
            return List.of();
        }
        return grants.search(userId, clientId, activeOnly, teamIds);
    }

    /**
     * The approver holds {@code access:approve} <em>over this client</em>.
     *
     * <p>Scope is the whole of "the client's supervisor, or an admin" (§9.3): a supervisor holds the
     * permission at {@code TEAM} and so covers their own team's clients, an admin holds it at
     * {@code ALL}. Nothing here reads a role name.
     */
    private AccessGrantRepository.Grant requireApprovable(UUID grantId, CurrentUser caller) {
        AccessGrantRepository.Grant grant = grants.findById(grantId).orElseThrow(ClientAccess::notFound);
        Optional<Scope> scope = access.scopeOf(caller, Permissions.ACCESS_APPROVE);
        Client client = clients.findById(grant.clientId()).orElseThrow(ClientAccess::notFound);
        // ACCESS_APPROVE is not grantable (RB-BR-04), so a caller cannot break-glass their way into
        // approving break-glass. The permission argument is what makes that true here.
        if (scope.isEmpty() || !access.covers(scope.get(), client, caller, Permissions.ACCESS_APPROVE)) {
            throw ClientAccess.notFound();
        }
        return grant;
    }
}
