package com.client360.common.security;

import java.util.Optional;

/**
 * The authorization seam (SPEC.md §11.1 sequencing note, CLAUDE.md rule 5). Every endpoint asks
 * this interface "does the user hold {@code permission}, and at what scope?" — never "is the user
 * a supervisor?".
 *
 * <p>The MVP binds {@link MvpAccessPolicy}. v2 binds an implementation backed by
 * {@code role_permissions}, {@code user_roles} and break-glass grants, and no caller changes.
 * Turning a scope into a decision about a specific client is RB-BR-02 and lives in
 * {@code client-service}, the authorization authority.
 */
public interface AccessPolicy {

    /**
     * The widest scope at which {@code user} holds {@code permission} (RB-BR-03), or empty when
     * the permission is not held at any scope.
     */
    Optional<Scope> scopeOf(CurrentUser user, String permission);

    default boolean holds(CurrentUser user, String permission) {
        return scopeOf(user, permission).isPresent();
    }

    default boolean holdsAtScopeAll(CurrentUser user, String permission) {
        return scopeOf(user, permission).filter(s -> s == Scope.ALL).isPresent();
    }
}
