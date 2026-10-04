package com.client360.client.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * User administration (SPEC.md §9.3). Separate from {@link UserRepository}, which answers the two
 * questions the Client Profile asks — a display name and a primary team — and is read by another
 * service through {@code /internal/users}. The writes live here because they are a different
 * concern with a different permission, and because nothing in the card path should be able to reach
 * them.
 *
 * <p>{@code users} rows are never deleted (RB-BR-15): deactivation preserves {@code author_id},
 * {@code created_by} and {@code completed_by} references across every module, so history stays
 * attributable years later. There is no delete method below, and there must not be one.
 */
@Repository
public class UserAdminRepository {

    private static final String COLUMNS = """
            id, employee_no, email, full_name, status::text AS status, primary_team_id,
            failed_login_count, locked_until, password_changed_at, must_change_password,
            last_login_at, deactivated_at, deactivated_by, created_at, updated_at, version""";

    private final JdbcClient jdbc;

    public UserAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdminUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
                .param("id", id)
                .query(UserAdminRepository::user)
                .optional();
    }

    /**
     * {@code GET /users} (§9.3). Every filter in one clause, nulls expressed in SQL rather than by
     * assembling a different statement — one plan, and nothing built from what was sent.
     *
     * @param scopeTeamIds the caller's own visibility; null means unrestricted. A supervisor sees
     *     their team only, and that is a set because RB-BR-02's {@code TEAM} is a set.
     */
    public List<AdminUser> search(UserSearch criteria, String sortColumn, boolean ascending, int limit, int offset) {
        return bind(
                        jdbc.sql("SELECT " + COLUMNS + " FROM users " + WHERE
                                + " ORDER BY " + sortColumn + (ascending ? " ASC" : " DESC") + ", id ASC"
                                + " LIMIT :limit OFFSET :offset"),
                        criteria)
                .param("limit", limit)
                .param("offset", offset)
                .query(UserAdminRepository::user)
                .list();
    }

    public long countSearch(UserSearch criteria) {
        return bind(jdbc.sql("SELECT count(*) FROM users " + WHERE), criteria)
                .query(Long.class)
                .single();
    }

    private static final String WHERE = """
            WHERE (CAST(:status AS text) IS NULL OR status::text = CAST(:status AS text))
              AND (CAST(:teamId AS uuid) IS NULL OR primary_team_id = CAST(:teamId AS uuid))
              AND (CAST(:scopeTeamIds AS uuid[]) IS NULL
                   OR primary_team_id = ANY (CAST(:scopeTeamIds AS uuid[])))
              AND (CAST(:q AS text) IS NULL
                   OR lower(full_name) LIKE '%' || lower(CAST(:q AS text)) || '%'
                   OR lower(email) LIKE '%' || lower(CAST(:q AS text)) || '%'
                   OR lower(employee_no) LIKE '%' || lower(CAST(:q AS text)) || '%')
              AND (CAST(:role AS text) IS NULL
                   OR EXISTS (SELECT 1 FROM user_roles ur
                                JOIN roles r ON r.id = ur.role_id
                               WHERE ur.user_id = users.id
                                 AND r.code = CAST(:role AS text)
                                 AND (ur.expires_at IS NULL OR ur.expires_at > now())))
            """;

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UserSearch criteria) {
        return spec.param("status", criteria.status())
                .param("teamId", criteria.teamId())
                .param("role", criteria.role())
                .param("q", criteria.q())
                .param(
                        "scopeTeamIds",
                        criteria.scopeTeamIds() == null
                                        || criteria.scopeTeamIds().isEmpty()
                                ? null
                                : criteria.scopeTeamIds().toArray(UUID[]::new));
    }

    /**
     * Creates a user with a hash nobody knows (§9.3: "Password is set via an emailed one-time link,
     * never in the request").
     *
     * <p>{@code must_change_password} is set, so once the invitation flow exists the first sign-in
     * forces a real password. Until then the account simply cannot be signed into, because the hash
     * it holds is random — which is the right failure for a half-built invitation path: no access,
     * rather than a default password.
     */
    public AdminUser create(
            String employeeNo, String email, String fullName, UUID primaryTeamId, String unusablePasswordHash) {
        return jdbc.sql("INSERT INTO users (employee_no, email, full_name, password_hash, primary_team_id,"
                        + "                    must_change_password)"
                        + " VALUES (:employeeNo, :email, :fullName, :hash, :teamId, true)"
                        + " RETURNING " + COLUMNS)
                .param("employeeNo", employeeNo)
                .param("email", email)
                .param("fullName", fullName)
                .param("hash", unusablePasswordHash)
                .param("teamId", primaryTeamId)
                .query(UserAdminRepository::user)
                .single();
    }

    /**
     * {@code PATCH /users/{id}} with {@code If-Match} (§4.8). Nulls mean "leave alone", which is
     * what makes this a merge-patch rather than a replace.
     *
     * @return empty when the version did not match, which the caller turns into {@code 409}
     */
    public Optional<AdminUser> update(
            UUID id, int expectedVersion, String fullName, UUID primaryTeamId, boolean clearTeam) {
        return jdbc.sql("""
                        UPDATE users
                           SET full_name = coalesce(:fullName, full_name),
                               primary_team_id = CASE WHEN :clearTeam THEN NULL
                                                      ELSE coalesce(:teamId, primary_team_id) END,
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("fullName", fullName)
                .param("teamId", primaryTeamId)
                .param("clearTeam", clearTeam)
                .query(UserAdminRepository::user)
                .optional();
    }

    /** RB-BR-15: the row stays, the status changes. {@code ck_users_deactivated_pair} wants both at once. */
    public boolean deactivate(UUID id, UUID actorId) {
        return jdbc.sql("""
                        UPDATE users
                           SET status = 'DEACTIVATED',
                               deactivated_at = now(),
                               deactivated_by = :actor,
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND status <> 'DEACTIVATED'
                        """).param("id", id).param("actor", actorId).update() == 1;
    }

    /**
     * §9.3: restores {@code ACTIVE} and forces a password change. It grants no clients back — a
     * reactivated user starts with an empty book, because their clients were reassigned to someone
     * who has been working them since.
     */
    public boolean reactivate(UUID id) {
        return jdbc.sql("""
                        UPDATE users
                           SET status = 'ACTIVE',
                               deactivated_at = NULL,
                               deactivated_by = NULL,
                               must_change_password = true,
                               failed_login_count = 0,
                               locked_until = NULL,
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'DEACTIVATED'
                        """).param("id", id).update() == 1;
    }

    /** §9.3: clears {@code locked_until} and {@code failed_login_count} (RB-BR-11's lockout). */
    public boolean unlock(UUID id) {
        return jdbc.sql("""
                        UPDATE users
                           SET failed_login_count = 0, locked_until = NULL, updated_at = now()
                         WHERE id = :id AND (locked_until IS NOT NULL OR failed_login_count > 0)
                        """).param("id", id).update() == 1;
    }

    // ----------------------------------------------------------------- blockers

    /**
     * RB-BR-07's blockers, as counts, in one round trip.
     *
     * <p>The {@code 409} enumerates every blocker rather than the first one found, so an admin can
     * clear them in one pass instead of discovering them one failed attempt at a time. Open tasks
     * and tickets are not here: they belong to interaction-service, which is asked separately.
     */
    public Blockers blockers(UUID id) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM clients
                                 WHERE owner_manager_id = :id AND deleted_at IS NULL) AS owned_clients,
                               (SELECT count(*) FROM teams t
                                 WHERE t.supervisor_id = :id
                                   AND NOT EXISTS (
                                       -- Another active supervisor for the same team. A team whose
                                       -- only supervisor leaves has nobody to approve break-glass or
                                       -- read its book, so the deactivation waits for a replacement.
                                       SELECT 1 FROM team_members tm
                                         JOIN users u ON u.id = tm.user_id
                                         JOIN user_roles ur ON ur.user_id = u.id
                                         JOIN roles r ON r.id = ur.role_id
                                        WHERE tm.team_id = t.id AND tm.left_at IS NULL
                                          AND u.id <> :id AND u.status = 'ACTIVE'
                                          AND r.code = 'SUPERVISOR'
                                          AND (ur.expires_at IS NULL OR ur.expires_at > now()))) AS sole_supervisor_of
                        """)
                .param("id", id)
                .query((rs, n) -> new Blockers(rs.getInt("owned_clients"), rs.getInt("sole_supervisor_of")))
                .single();
    }

    /**
     * RB-BR-08: at least one {@code ACTIVE} user with the {@code ADMIN} role must exist at all times.
     * A system nobody can administer is unrecoverable.
     *
     * @param excluding the user about to lose the role or be deactivated, who must not be counted as
     *     the reason it is safe to remove them
     */
    public int otherActiveAdmins(UUID excluding) {
        return jdbc.sql("""
                        SELECT count(DISTINCT u.id)
                          FROM users u
                          JOIN user_roles ur ON ur.user_id = u.id
                          JOIN roles r ON r.id = ur.role_id
                         WHERE r.code = 'ADMIN'
                           AND u.status = 'ACTIVE'
                           AND u.id <> :excluding
                           AND (ur.expires_at IS NULL OR ur.expires_at > now())
                        """).param("excluding", excluding).query(Integer.class).single();
    }

    // -------------------------------------------------------------------- roles

    /**
     * RB-BR-14: a role change carries a mandatory reason of at least ten characters, which
     * {@code ck_ur_reason} also enforces. Idempotent, so RB-EC-06's retry converges.
     *
     * @return false when the user already holds the role
     */
    public boolean grantRole(UUID userId, String roleCode, UUID grantedBy, String reason, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO user_roles (user_id, role_id, granted_by, reason, expires_at)
                        SELECT :userId, r.id, :grantedBy, :reason, :expiresAt
                          FROM roles r WHERE r.code = :role
                        ON CONFLICT (user_id, role_id) DO NOTHING
                        """)
                        .param("userId", userId)
                        .param("role", roleCode)
                        .param("grantedBy", grantedBy)
                        .param("reason", reason)
                        .param(
                                "expiresAt",
                                expiresAt == null
                                        ? null
                                        : OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** @return false when the user did not hold the role, so a retry is harmless (RB-EC-06) */
    public boolean revokeRole(UUID userId, String roleCode) {
        return jdbc.sql("""
                        DELETE FROM user_roles
                         WHERE user_id = :userId
                           AND role_id = (SELECT id FROM roles WHERE code = :role)
                        """).param("userId", userId).param("role", roleCode).update() == 1;
    }

    public List<String> roleCodesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT r.code FROM user_roles ur
                          JOIN roles r ON r.id = ur.role_id
                         WHERE ur.user_id = :userId
                           AND (ur.expires_at IS NULL OR ur.expires_at > now())
                         ORDER BY r.code
                        """).param("userId", userId).query(String.class).list();
    }

    public boolean roleExists(String roleCode) {
        return jdbc.sql("SELECT count(*) FROM roles WHERE code = :role")
                        .param("role", roleCode)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** How many clients this user owns in a given team — RB-EC-09's blocker on a team move. */
    public int ownedClientsInTeam(UUID userId, UUID teamId) {
        if (teamId == null) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM clients
                         WHERE owner_manager_id = :userId AND team_id = :teamId AND deleted_at IS NULL
                        """)
                .param("userId", userId)
                .param("teamId", teamId)
                .query(Integer.class)
                .single();
    }

    private static AdminUser user(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AdminUser(
                rs.getObject("id", UUID.class),
                rs.getString("employee_no"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("status"),
                rs.getObject("primary_team_id", UUID.class),
                rs.getInt("failed_login_count"),
                instant(rs.getObject("locked_until", OffsetDateTime.class)),
                instant(rs.getObject("password_changed_at", OffsetDateTime.class)),
                rs.getBoolean("must_change_password"),
                instant(rs.getObject("last_login_at", OffsetDateTime.class)),
                instant(rs.getObject("deactivated_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getInt("version"));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** A user as administration sees them. No password hash: nothing above this layer may read one. */
    public record AdminUser(
            UUID id,
            String employeeNo,
            String email,
            String fullName,
            String status,
            UUID primaryTeamId,
            int failedLoginCount,
            Instant lockedUntil,
            Instant passwordChangedAt,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant deactivatedAt,
            Instant createdAt,
            int version) {}

    public record UserSearch(String q, String status, UUID teamId, String role, List<UUID> scopeTeamIds) {}

    /** @param soleSupervisorOf teams where this user is the supervisor and no other active one exists */
    public record Blockers(int ownedClients, int soleSupervisorOf) {}
}
