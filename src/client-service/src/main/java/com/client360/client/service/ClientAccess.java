package com.client360.client.service;

import com.client360.client.api.ClientErrorCodes;
import com.client360.client.domain.Client;
import com.client360.client.persistence.UserRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Scope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a permission plus a scope into a decision about one client (RB-BR-02). This is the
 * authorization authority for the whole system — interaction-service asks it, never the reverse.
 *
 * <p>Two rules shape everything here:
 *
 * <ul>
 *   <li>Ask {@link AccessPolicy} for a permission and a scope, never for a role string
 *       (CLAUDE.md rule 5). Nothing in this class mentions SUPERVISOR or ADMIN.
 *   <li>An out-of-scope read is {@code 404}, not {@code 403} (ER-01), so a caller cannot probe
 *       which records exist. The real reason goes to the audit log as {@code PERMISSION_DENIED} —
 *       see {@link ClientEvents#recordDenial}.
 * </ul>
 */
@Component
public class ClientAccess {

    private final AccessPolicy accessPolicy;
    private final UserRepository users;

    public ClientAccess(AccessPolicy accessPolicy, UserRepository users) {
        this.accessPolicy = accessPolicy;
        this.users = users;
    }

    /**
     * The scope at which the caller holds {@code permission}.
     *
     * @throws ApiException {@code 403 PERMISSION_DENIED} when it is held at no scope. Not holding
     *     a permission at all is safe to state plainly: it says nothing about which records exist.
     */
    public Scope requireScope(CurrentUser caller, String permission) {
        return accessPolicy
                .scopeOf(caller, permission)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.PERMISSION_DENIED, "This action requires the " + permission + " permission.")
                        .detail("permission", permission));
    }

    public Optional<Scope> scopeOf(CurrentUser caller, String permission) {
        return accessPolicy.scopeOf(caller, permission);
    }

    /**
     * Whether {@code scope} covers this particular client (RB-BR-02).
     *
     * <p>{@code TEAM} resolves against the caller's {@code primary_team_id}, the only membership
     * the MVP schema records. v2 widens it to {@code team_members} plus supervised teams; because
     * every caller asks through this one method, that is a change here and nowhere else.
     */
    public boolean covers(Scope scope, Client client, CurrentUser caller) {
        return switch (scope) {
            case ALL -> true;
            case TEAM ->
                callerTeam(caller).map(team -> team.equals(client.teamId())).orElse(false);
            case OWN -> caller.id().equals(client.ownerManagerId());
        };
    }

    /**
     * Asserts the caller may act on this client, or fails the way ER-01 requires.
     *
     * @throws ApiException {@code 404 CLIENT_NOT_FOUND} — deliberately indistinguishable from "no
     *     such client", for both a missing permission and an out-of-scope record
     */
    public void requireCovers(Client client, CurrentUser caller, String permission) {
        Optional<Scope> scope = accessPolicy.scopeOf(caller, permission);
        if (scope.isEmpty() || !covers(scope.get(), client, caller)) {
            throw notFound();
        }
    }

    /** The one shape every out-of-scope and every absent client collapses into (ER-01). */
    public static ApiException notFound() {
        return ApiException.notFound(ClientErrorCodes.CLIENT_NOT_FOUND, "Client not found or not in your scope.");
    }

    /**
     * The caller's own team, for rules that compare it against someone else's — reassigning a
     * client to a manager outside the supervisor's team, for instance (CP-US-05).
     */
    public Optional<UUID> teamOf(CurrentUser caller) {
        return callerTeam(caller);
    }

    private Optional<UUID> callerTeam(CurrentUser caller) {
        return users.findById(caller.id()).map(UserRepository.UserRef::primaryTeamId);
    }
}
