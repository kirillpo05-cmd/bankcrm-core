package com.client360.client.persistence;

import com.client360.common.security.Scope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the authorization tables as a whole, for the three places that need more than one answer:
 * the token's {@code permissions} claim (RB-BR-05), {@code GET /me}, and
 * {@code GET /permissions/matrix} (RB-US-08).
 *
 * <p>{@code DatabaseAccessPolicy} deliberately does not use this. An enforcement decision asks about
 * one permission and wants one indexed row; loading a user's whole permission set to answer "may
 * they read this client" would be slower and would tempt a caller to cache it.
 */
@Repository
public class PermissionRepository {

    private final JdbcClient jdbc;

    public PermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every permission the user holds, at the widest scope they hold it (RB-BR-03).
     *
     * <p>{@code MAX} over {@code access_scope} is the union rule itself: the enum is declared
     * {@code OWN < TEAM < ALL}, so the widest grant wins. Over {@code text} it would be wrong —
     * {@code ALL} sorts before {@code OWN} alphabetically.
     */
    public Map<String, Scope> effectivePermissions(UUID userId) {
        Map<String, Scope> permissions = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT p.code, MAX(rp.scope)::text AS scope
                          FROM user_roles ur
                          JOIN role_permissions rp ON rp.role_id = ur.role_id
                          JOIN permissions p ON p.id = rp.permission_id
                          JOIN users u ON u.id = ur.user_id
                         WHERE ur.user_id = :userId
                           AND (ur.expires_at IS NULL OR ur.expires_at > now())
                           -- The same condition DatabaseAccessPolicy applies, for the same reason:
                           -- a suspended or deactivated account holds no permission at any scope.
                           -- Without it /me would list permissions the enforcement layer refuses,
                           -- and worse, a token minted here would carry them to the two services
                           -- that authorize from the claim.
                           AND u.status = 'ACTIVE'
                         GROUP BY p.code
                         ORDER BY p.code
                        """)
                .param("userId", userId)
                .query((rs, n) -> Map.entry(rs.getString("code"), Scope.valueOf(rs.getString("scope"))))
                .list()
                .forEach(entry -> permissions.put(entry.getKey(), entry.getValue()));
        return permissions;
    }

    /**
     * The roles the user currently holds, with their expiry (§9.3's {@code /me}).
     *
     * <p>Roles are shown, never enforced on (RB-BR-01). {@code /me} returns them because an admin
     * reading the screen thinks in roles, and the audit envelope records one as {@code actor.role};
     * every decision in the system is still made on a permission and a scope.
     */
    public List<RoleGrant> rolesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT r.code, ur.expires_at
                          FROM user_roles ur
                          JOIN roles r ON r.id = ur.role_id
                          JOIN users u ON u.id = ur.user_id
                         WHERE ur.user_id = :userId
                           AND (ur.expires_at IS NULL OR ur.expires_at > now())
                           -- Held, in the sense of granting something. An administrator reading a
                           -- deactivated user's record sees their rows through UserAdminRepository;
                           -- this answers what the caller can currently do, which is nothing.
                           AND u.status = 'ACTIVE'
                         ORDER BY r.code
                        """)
                .param("userId", userId)
                .query((rs, n) ->
                        new RoleGrant(rs.getString("code"), instant(rs.getObject("expires_at", OffsetDateTime.class))))
                .list();
    }

    /**
     * RB-US-08: the matrix, read from {@code role_permissions} — the same rows the enforcement layer
     * consults, so the documentation cannot drift from the behaviour. That is the whole point of the
     * endpoint, and the reason it must never be served from a hand-maintained constant.
     */
    public List<MatrixEntry> matrix() {
        return jdbc.sql("""
                        SELECT r.code AS role_code, r.name AS role_name, p.code AS permission_code,
                               rp.scope::text AS scope
                          FROM role_permissions rp
                          JOIN roles r ON r.id = rp.role_id
                          JOIN permissions p ON p.id = rp.permission_id
                         ORDER BY r.code, p.code
                        """)
                .query((rs, n) -> new MatrixEntry(
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("permission_code"),
                        Scope.valueOf(rs.getString("scope"))))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** @param expiresAt {@code null} is permanent; a date is cover for an absence (§9.2.7) */
    public record RoleGrant(String code, Instant expiresAt) {}

    public record MatrixEntry(String roleCode, String roleName, String permissionCode, Scope scope) {}
}
