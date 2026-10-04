package com.client360.client.service;

import com.client360.client.api.ClientErrorCodes;
import com.client360.client.domain.Client;
import com.client360.client.persistence.AccessGrantRepository;
import com.client360.client.persistence.TeamRepository;
import com.client360.client.persistence.UserRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import java.util.Optional;
import java.util.Set;
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
    private final TeamRepository teams;
    private final AccessGrantRepository grants;

    public ClientAccess(
            AccessPolicy accessPolicy, UserRepository users, TeamRepository teams, AccessGrantRepository grants) {
        this.accessPolicy = accessPolicy;
        this.users = users;
        this.teams = teams;
        this.grants = grants;
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
     * Exactly the permissions a break-glass grant may widen (RB-BR-04).
     *
     * <p>An allow list, not a deny list, and that is the whole design of it: a permission code added
     * to the matrix next year is not grantable until someone decides it should be. A deny list would
     * have made every future code break-glass-able by default, which is the wrong direction for a
     * mechanism whose entire purpose is to be narrow.
     *
     * <p>SPEC.md's RB-BR-04 says {@code interaction:*} and {@code task:*}; this narrows that to the
     * read and write codes and leaves {@code interaction:delete} and {@code task:reassign} out.
     * Break-glass exists for continuity of service — covering an urgent call for an absent
     * colleague — and destroying or reassigning someone else's records is not continuity. SPEC.md
     * records the narrowing.
     */
    private static final Set<String> GRANTABLE = Set.of(
            Permissions.CLIENT_READ,
            Permissions.CLIENT_WRITE,
            Permissions.INTERACTION_READ,
            Permissions.INTERACTION_WRITE,
            Permissions.TICKET_READ,
            Permissions.TICKET_WRITE,
            Permissions.TASK_READ,
            Permissions.TASK_WRITE);

    /**
     * Whether the caller may act on this particular client, by role scope (RB-BR-02) or by an active
     * break-glass grant (RB-BR-04).
     *
     * <p>{@code TEAM} is every team the caller supervises or is an active member of, plus their
     * primary one. It used to be {@code primary_team_id} alone, the only membership the MVP schema
     * recorded; V8's {@code team_members} widened it, and because every caller asks through this one
     * method that was a change here and nowhere else.
     *
     * <p>The permission is a parameter because a grant widens some codes and not others, and the
     * list of which belongs in one place rather than at eleven call sites. A grant is consulted only
     * after scope has already failed, so an ordinary in-scope read costs no extra query.
     *
     * @param permission the code being authorized, so RB-BR-04 can be applied
     */
    public boolean covers(Scope scope, Client client, CurrentUser caller, String permission) {
        return authorize(scope, client, caller, permission).allowed();
    }

    /**
     * The same decision, saying <em>how</em> it was reached.
     *
     * <p>A caller that is about to disclose PII or take an action needs to know whether a grant was
     * what allowed it, because AT-BR-13 requires the grant id and its reason in the audit context — a
     * {@code READ_SENSITIVE} outside normal scope has to explain itself in the log without a join
     * into another service.
     */
    public Decision authorize(Scope scope, Client client, CurrentUser caller, String permission) {
        boolean inScope =
                switch (scope) {
                    case ALL -> true;
                    case TEAM ->
                        client.teamId() != null
                                && teams.scopeTeamsOf(caller.id()).contains(client.teamId());
                    case OWN -> caller.id().equals(client.ownerManagerId());
                };
        if (inScope) {
            return new Decision(true, null);
        }
        return grantFor(client, caller, permission)
                .map(grant -> new Decision(true, grant))
                .orElseGet(() -> new Decision(false, null));
    }

    /**
     * @param grant the break-glass grant that authorized this, or {@code null} when ordinary role
     *     scope did. Non-null is the signal to count a use and to name the grant in the audit
     *     context (AT-BR-13).
     */
    public record Decision(boolean allowed, AccessGrantRepository.Grant grant) {

        public Optional<AccessGrantRepository.Grant> byGrant() {
            return Optional.ofNullable(grant);
        }
    }

    /**
     * Counts one action taken under a grant (AT-BR-13).
     *
     * <p>Actions, not authorization checks. {@code authorize} runs speculatively in places — deciding
     * whether a duplicate-detection hit is visible enough to name, for instance — and counting those
     * would turn the number into noise. What S-RB-05's history needs to show is how much a grant was
     * actually used, because forty reads under break-glass is a scope problem rather than an
     * emergency.
     */
    public void recordGrantUse(AccessGrantRepository.Grant grant) {
        grants.recordUse(grant.id());
    }

    /**
     * The grant that would authorize this action, if any (RB-BR-04).
     *
     * <p>A grant widens a permission the caller already holds; it never manufactures one. The caller
     * reaches this method only having been found to hold {@code permission} at some scope, so a
     * manager with a grant can read that one client — and a manager with a grant still cannot delete
     * an interaction, because they hold no such permission at any scope for a grant to widen.
     *
     * <p>Exposed so the paths that disclose PII can name the grant in the audit context (AT-BR-13):
     * a {@code READ_SENSITIVE} outside normal scope must explain itself in the log without a join
     * into another service.
     */
    public Optional<AccessGrantRepository.Grant> grantFor(Client client, CurrentUser caller, String permission) {
        if (!GRANTABLE.contains(permission)) {
            return Optional.empty();
        }
        return grants.findActive(caller.id(), client.id());
    }

    /**
     * Asserts the caller may act on this client, or fails the way ER-01 requires.
     *
     * @throws ApiException {@code 404 CLIENT_NOT_FOUND} — deliberately indistinguishable from "no
     *     such client", for both a missing permission and an out-of-scope record
     */
    public void requireCovers(Client client, CurrentUser caller, String permission) {
        Optional<Scope> scope = accessPolicy.scopeOf(caller, permission);
        if (scope.isEmpty() || !covers(scope.get(), client, caller, permission)) {
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

    /**
     * Every team in the caller's {@code TEAM} scope (RB-BR-02) — what a list or a feed filters on,
     * as opposed to {@link #teamOf}, which is the one primary team CP-BR-03 derives from.
     */
    public Set<UUID> scopeTeamsOf(CurrentUser caller) {
        return teams.scopeTeamsOf(caller.id());
    }

    private Optional<UUID> callerTeam(CurrentUser caller) {
        return users.findById(caller.id()).map(UserRepository.UserRef::primaryTeamId);
    }
}
