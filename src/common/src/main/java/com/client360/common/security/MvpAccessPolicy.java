package com.client360.common.security;

import java.util.Optional;

/**
 * The MVP stub (SPEC.md §11.1): every authenticated user is a {@code MANAGER} at {@code ALL}
 * scope. It holds exactly the permissions in the MANAGER column of §9.2.8 — so a manager still
 * cannot delete, merge, reassign or approve KYC — but with scope widened to every client, because
 * the tables that resolve {@code OWN}/{@code TEAM} membership ship with RBAC in v2.
 *
 * <p>v2 replaces this bean. Nothing else changes: callers already ask for permission + scope.
 */
public class MvpAccessPolicy implements AccessPolicy {

    @Override
    public Optional<Scope> scopeOf(CurrentUser user, String permission) {
        return Permissions.MANAGER.contains(permission) ? Optional.of(Scope.ALL) : Optional.empty();
    }
}
