package com.client360.client.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code client.access_grants} — break-glass (SPEC.md §9.2.6, RB-US-05).
 *
 * <p>The table carries no status column, and that is deliberate: a status would be a fourth fact
 * that could disagree with the three timestamps it is derived from. Every query below derives it in
 * SQL against {@code now()} (rule 7), so a grant expires at the same instant for every replica and
 * nothing has to run a job to mark it.
 */
@Repository
public class AccessGrantRepository {

    /**
     * Status derived from the timestamps, in the order the checks must happen: revoked beats
     * expired, and a grant revoked before it was ever approved was declined rather than released
     * (S-RB-04's {@code denied} state).
     */
    private static final String STATUS_SQL = """
            CASE
                WHEN revoked_at IS NOT NULL AND approved_at IS NULL THEN 'DENIED'
                WHEN revoked_at IS NOT NULL                         THEN 'REVOKED'
                WHEN expires_at <= now()                            THEN 'EXPIRED'
                WHEN approved_at IS NULL                            THEN 'PENDING_APPROVAL'
                ELSE 'ACTIVE'
            END""";

    private final JdbcClient jdbc;

    public AccessGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param hours 1–8, validated at the API; the ceiling is also {@code ck_ag_window}, which is
     *     what makes it unbypassable (§9.2.6)
     */
    public Grant create(UUID userId, UUID clientId, String reason, int hours) {
        // expires_at is measured from requested_at, not from approval: ck_ag_window compares the two,
        // and a window that started when someone got round to approving could be eight hours after
        // an emergency had passed. A slow approval shortens the access it grants.
        return jdbc.sql("INSERT INTO access_grants (user_id, client_id, reason, expires_at)"
                        + " VALUES (:userId, :clientId, :reason, now() + make_interval(hours => :hours))"
                        + " RETURNING id, user_id, client_id, reason, requested_at, approved_by, approved_at,"
                        + "           expires_at, revoked_at, revoked_by, use_count, " + STATUS_SQL + " AS status")
                .param("userId", userId)
                .param("clientId", clientId)
                .param("reason", reason)
                .param("hours", hours)
                .query(AccessGrantRepository::grant)
                .single();
    }

    public Optional<Grant> findById(UUID id) {
        return jdbc.sql("SELECT id, user_id, client_id, reason, requested_at, approved_by, approved_at,"
                        + "       expires_at, revoked_at, revoked_by, use_count, " + STATUS_SQL + " AS status"
                        + "  FROM access_grants WHERE id = :id")
                .param("id", id)
                .query(AccessGrantRepository::grant)
                .optional();
    }

    /**
     * The grant that authorizes a read right now: approved, not revoked, still inside its window —
     * the three conditions {@code ix_ag_active} is built for.
     *
     * <p>Asked only after role scope has already failed to cover the client, so an ordinary
     * in-scope read never pays for this query.
     */
    public Optional<Grant> findActive(UUID userId, UUID clientId) {
        return jdbc.sql("SELECT id, user_id, client_id, reason, requested_at, approved_by, approved_at,"
                        + "       expires_at, revoked_at, revoked_by, use_count, " + STATUS_SQL + " AS status"
                        + "  FROM access_grants"
                        + " WHERE user_id = :userId AND client_id = :clientId"
                        + "   AND approved_at IS NOT NULL AND revoked_at IS NULL AND expires_at > now()"
                        + " ORDER BY expires_at DESC"
                        + " LIMIT 1")
                .param("userId", userId)
                .param("clientId", clientId)
                .query(AccessGrantRepository::grant)
                .optional();
    }

    /**
     * Anything that would make a new request redundant: an approved grant still running, or one
     * still waiting for an approver (§9.3 {@code 409 ACCESS_GRANT_EXISTS}). A request nobody has
     * answered yet is a live request, not an absent one.
     */
    public Optional<Grant> findOutstanding(UUID userId, UUID clientId) {
        return jdbc.sql("SELECT id, user_id, client_id, reason, requested_at, approved_by, approved_at,"
                        + "       expires_at, revoked_at, revoked_by, use_count, " + STATUS_SQL + " AS status"
                        + "  FROM access_grants"
                        + " WHERE user_id = :userId AND client_id = :clientId"
                        + "   AND revoked_at IS NULL AND expires_at > now()"
                        + " ORDER BY requested_at DESC"
                        + " LIMIT 1")
                .param("userId", userId)
                .param("clientId", clientId)
                .query(AccessGrantRepository::grant)
                .optional();
    }

    /**
     * Approves, if it is still pending. One conditional statement rather than a read and a write, so
     * two approvers racing produce one approval and the loser is told the state changed.
     *
     * @return false when it was already approved, revoked or expired ({@code 409})
     */
    public boolean approve(UUID id, UUID approverId) {
        return jdbc.sql("""
                        UPDATE access_grants
                           SET approved_by = :approver, approved_at = now()
                         WHERE id = :id
                           AND approved_at IS NULL
                           AND revoked_at IS NULL
                           AND expires_at > now()
                        """).param("id", id).param("approver", approverId).update() == 1;
    }

    /**
     * Revokes a pending request (a denial) or an approved grant (a release). Expired is excluded:
     * there is nothing left to revoke, and reporting success would misrepresent what happened.
     */
    public boolean revoke(UUID id, UUID revokerId) {
        return jdbc.sql("""
                        UPDATE access_grants
                           SET revoked_by = :revoker, revoked_at = now()
                         WHERE id = :id
                           AND revoked_at IS NULL
                           AND expires_at > now()
                        """).param("id", id).param("revoker", revokerId).update() == 1;
    }

    /**
     * AT-BR-13: every read under a grant is counted, so S-RB-05's history can show a pattern of
     * repeated break-glass on the same client. A grant used forty times is a scope problem, and the
     * count is the only place that becomes visible.
     */
    public void recordUse(UUID id) {
        jdbc.sql("UPDATE access_grants SET use_count = use_count + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * {@code GET /access-grants} — the supervisor's approval queue and the compliance review list.
     *
     * @param teamIds when non-null, limits the list to grants on clients in these teams, which is how
     *     a supervisor's queue stops at their own team (RB-BR-02). A caller at {@code ALL} scope
     *     passes null.
     */
    public List<Grant> search(UUID userId, UUID clientId, boolean activeOnly, List<UUID> teamIds) {
        // The join is unaliased on purpose: `clients` carries no approved_at, revoked_at or
        // expires_at, so STATUS_SQL's unqualified names stay unambiguous and the one definition of
        // status is reused rather than rewritten with a prefix.
        return jdbc.sql("SELECT access_grants.id, user_id, client_id, reason, requested_at, approved_by,"
                        + "       approved_at, expires_at, revoked_at, revoked_by, use_count,"
                        + "       " + STATUS_SQL + " AS status"
                        + "  FROM access_grants"
                        + "  JOIN clients ON clients.id = access_grants.client_id"
                        + " WHERE (CAST(:userId AS uuid) IS NULL OR user_id = CAST(:userId AS uuid))"
                        + "   AND (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))"
                        + "   AND (NOT :activeOnly OR (approved_at IS NOT NULL AND revoked_at IS NULL"
                        + "                            AND expires_at > now()))"
                        + "   AND (CAST(:teamIds AS uuid[]) IS NULL OR clients.team_id = ANY (CAST(:teamIds AS uuid[])))"
                        + " ORDER BY requested_at DESC"
                        + " LIMIT 200")
                .param("userId", userId)
                .param("clientId", clientId)
                .param("activeOnly", activeOnly)
                .param("teamIds", teamIds == null || teamIds.isEmpty() ? null : teamIds.toArray(UUID[]::new))
                .query(AccessGrantRepository::grant)
                .list();
    }

    private static Grant grant(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Grant(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getString("reason"),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                rs.getObject("approved_by", UUID.class),
                instant(rs.getObject("approved_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("revoked_at", OffsetDateTime.class)),
                rs.getObject("revoked_by", UUID.class),
                rs.getInt("use_count"),
                rs.getString("status"));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * @param status derived, never stored: {@code PENDING_APPROVAL}, {@code ACTIVE},
     *     {@code DENIED}, {@code REVOKED} or {@code EXPIRED}
     */
    public record Grant(
            UUID id,
            UUID userId,
            UUID clientId,
            String reason,
            Instant requestedAt,
            UUID approvedBy,
            Instant approvedAt,
            Instant expiresAt,
            Instant revokedAt,
            UUID revokedBy,
            int useCount,
            String status) {}
}
