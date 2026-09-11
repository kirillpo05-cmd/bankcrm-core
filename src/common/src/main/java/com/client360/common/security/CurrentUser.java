package com.client360.common.security;

import java.util.List;
import java.util.UUID;

/**
 * The authenticated caller, as carried by the access token (RB-BR-05).
 *
 * <p>{@code roles} feed the event envelope's {@code actor.role} only. Authorization never reads
 * them: it asks the {@link AccessPolicy} for a permission and a scope (RB-BR-01).
 */
public record CurrentUser(UUID id, String email, String fullName, List<String> roles) {

    public CurrentUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** The role recorded as {@code actor.role} on events; {@code null} when the token carries none. */
    public String primaryRole() {
        return roles.isEmpty() ? null : roles.getFirst();
    }

    /** Deliberately omits the email: records' generated toString would put it in any log line. */
    @Override
    public String toString() {
        return "CurrentUser[id=" + id + "]";
    }
}
