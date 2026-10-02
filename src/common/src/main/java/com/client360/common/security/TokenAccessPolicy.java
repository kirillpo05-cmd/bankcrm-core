package com.client360.common.security;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves permission and scope from the access token's {@code permissions} claim (RB-BR-05).
 *
 * <p>This is how a service that holds no RBAC tables authorizes. There is exactly one authorization
 * authority — client-service owns {@code role_permissions} and {@code user_roles} (§3.1) — and the
 * two services that only consume tokens must not read those tables, nor make an HTTP call per
 * permission check. So client-service writes the answer into the token when it issues one, and the
 * token's 15-minute life is the bound on how stale that answer can be, which is RB-BR-05 exactly.
 *
 * <p>It replaces {@code MvpAccessPolicy}, which answered MANAGER at {@code ALL} for every
 * authenticated caller. That stub was honest about being one — the tables did not exist — but it
 * meant an auditor's token and a manager's token bought the same access in both of these services.
 *
 * <p>Per-client decisions are still not made here. "Is client X in this caller's scope" is RB-BR-02
 * and needs the client's owner and team, so interaction-service asks client-service through
 * {@code GET /internal/clients/{id}/access}. This answers only the question that depends on the
 * caller alone: which permissions do they hold, and how wide.
 */
public class TokenAccessPolicy implements AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(TokenAccessPolicy.class);

    @Override
    public Optional<Scope> scopeOf(CurrentUser user, String permission) {
        // The claim describes whoever authenticated this request. Answering from it about a
        // different subject would be a silently wrong authorization decision, which is the worst
        // kind, so the mismatch is refused rather than guessed at. Every caller in the codebase
        // passes the current caller; this is the guard that keeps it that way.
        CurrentUser authenticated = CurrentUsers.find().orElse(null);
        if (authenticated == null || !authenticated.id().equals(user.id())) {
            return Optional.empty();
        }
        Map<String, String> claim = CurrentUsers.permissionClaims();
        String scope = claim.get(permission);
        if (scope == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Scope.valueOf(scope));
        } catch (IllegalArgumentException e) {
            // The issuer and this service disagree about what a scope is. Fail closed and be loud:
            // it is a deployment fault, not a caller's, and denying is the only safe reading.
            log.warn("Access token claims an unknown scope {} for {}; treating it as no access", scope, permission);
            return Optional.empty();
        }
    }
}
