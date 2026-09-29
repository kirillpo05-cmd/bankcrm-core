package com.client360.client.security;

import com.client360.common.security.Scope;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything RB-BR-05 puts in an access token, resolved from the database at issue time.
 *
 * <p>A snapshot, not a live view. The token is handed to services that have no RBAC tables of their
 * own, so what it says is true as of issuance and for at most the token's 15 minutes — which is the
 * whole of RB-BR-05's revocation bound. client-service itself never reads these back; it has the
 * tables, and it asks them per request.
 *
 * @param permissions permission code to widest scope (RB-BR-03)
 * @param teamIds every team whose clients are in this user's {@code TEAM} scope (RB-BR-02)
 * @param grantIds active break-glass grants, so a service can attribute a read to one (AT-BR-13)
 */
public record TokenSubject(
        UUID id,
        String email,
        String fullName,
        List<String> roles,
        Map<String, Scope> permissions,
        List<UUID> teamIds,
        List<UUID> grantIds) {

    public TokenSubject {
        roles = List.copyOf(roles);
        permissions = Map.copyOf(permissions);
        teamIds = List.copyOf(teamIds);
        grantIds = List.copyOf(grantIds);
    }

    /** Deliberately omits the email: a record's generated toString would put it in any log line. */
    @Override
    public String toString() {
        return "TokenSubject[id=" + id + "]";
    }
}
