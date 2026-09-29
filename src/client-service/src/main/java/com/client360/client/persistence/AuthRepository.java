package com.client360.client.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tables {@code POST /auth/login} and {@code POST /auth/refresh} read and write: credentials on
 * {@code users}, the lockout counters, {@code login_attempts} and {@code refresh_tokens}
 * (SPEC.md §9.2.9, §9.2.10).
 *
 * <p>Every timestamp comparison happens in SQL against {@code now()} (rule 7). A lockout evaluated
 * against the JVM clock would expire at a different moment on each instance, and "am I still locked
 * out" would depend on which replica answered.
 */
@Repository
public class AuthRepository {

    /** RB-BR-12: 90 days. A property of the stored hash, not of a request. */
    private static final int PASSWORD_MAX_AGE_DAYS = 90;

    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ credentials

    /**
     * Everything a login decision needs, in one read, including whether the account is locked and
     * whether the password has aged out as the database sees it.
     *
     * <p>Looked up by the normalized email. User email is a plaintext column on purpose — corporate
     * directory data, not client PII (§9.2.3) — which is also what makes login one indexed read
     * rather than a scan over encrypted values.
     */
    public Optional<Credentials> findByEmail(String normalizedEmail) {
        return jdbc.sql(CREDENTIALS_SQL + " WHERE email = :key")
                .param("key", normalizedEmail)
                .param("maxAgeDays", PASSWORD_MAX_AGE_DAYS)
                .query(AuthRepository::credentials)
                .optional();
    }

    /**
     * The same projection by id, for a request that is already authenticated: {@code /auth/refresh}
     * holds a token, and {@code GET /me} holds a subject claim. Neither should have to go back
     * through an email to re-read the row its own session points at.
     */
    public Optional<Credentials> findById(UUID userId) {
        return jdbc.sql(CREDENTIALS_SQL + " WHERE id = :key")
                .param("key", userId)
                .param("maxAgeDays", PASSWORD_MAX_AGE_DAYS)
                .query(AuthRepository::credentials)
                .optional();
    }

    private static final String CREDENTIALS_SQL = """
            SELECT id, email, full_name, password_hash, status::text AS status,
                   primary_team_id, must_change_password,
                   locked_until IS NOT NULL AND locked_until > now() AS locked,
                   locked_until,
                   password_changed_at < now() - make_interval(days => :maxAgeDays) AS password_expired
              FROM users""";

    private static Credentials credentials(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Credentials(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getObject("primary_team_id", UUID.class),
                rs.getBoolean("must_change_password"),
                rs.getBoolean("locked"),
                instant(rs.getObject("locked_until", OffsetDateTime.class)),
                rs.getBoolean("password_expired"));
    }

    /**
     * Counts one failure and locks the account at the threshold (RB-BR-11: 5 consecutive failures,
     * 15 minutes).
     *
     * <p>One statement, so two simultaneous wrong passwords cannot both read four and both write
     * five: the counter is incremented and tested inside the same {@code UPDATE}, and the row lock
     * serializes them.
     *
     * @return the instant the lock lifts, or empty when this failure did not reach the threshold
     */
    public Optional<Instant> registerFailure(UUID userId, int threshold, Duration lockFor) {
        return jdbc.sql("""
                        UPDATE users
                           SET failed_login_count = failed_login_count + 1,
                               locked_until = CASE
                                   WHEN failed_login_count + 1 >= :threshold
                                   THEN now() + make_interval(secs => :lockSeconds)
                                   ELSE locked_until
                               END,
                               updated_at = now()
                         WHERE id = :id
                        RETURNING CASE WHEN failed_login_count >= :threshold THEN locked_until END
                                  AS locked_until
                        """)
                .param("id", userId)
                .param("threshold", threshold)
                .param("lockSeconds", lockFor.toSeconds())
                .query((rs, n) -> instant(rs.getObject("locked_until", OffsetDateTime.class)))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    /** RB-BR-11: the counter resets on success, and the lock with it. */
    public void registerSuccess(UUID userId) {
        jdbc.sql("""
                        UPDATE users
                           SET failed_login_count = 0,
                               locked_until = NULL,
                               last_login_at = now(),
                               updated_at = now()
                         WHERE id = :id
                        """).param("id", userId).update();
    }

    /**
     * Hot operational data for lockout decisions, 90-day retention. The durable record of a login is
     * the {@code LOGIN_SUCCESS} / {@code LOGIN_FAILURE} audit event, kept for seven years — this row
     * is allowed to be pruned precisely because it is not the evidence (§9.2.10).
     *
     * @param userId {@code null} when the email matched no account; the pattern of attempts against
     *     addresses that do not exist is itself worth seeing
     */
    public void recordAttempt(
            String email, UUID userId, boolean success, String failureReason, String ip, String userAgent) {
        jdbc.sql("""
                        INSERT INTO login_attempts (email, user_id, success, failure_reason, ip, user_agent)
                        VALUES (:email, :userId, :success, :reason, CAST(:ip AS inet), :userAgent)
                        """)
                .param("email", email)
                .param("userId", userId)
                .param("success", success)
                .param("reason", success ? null : failureReason)
                .param("ip", ip)
                .param("userAgent", userAgent)
                .update();
    }

    /** How many failures this address has produced inside the window, for the lockout audit context. */
    public int recentFailures(String normalizedEmail, Duration window) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM login_attempts
                         WHERE email = :email
                           AND NOT success
                           AND attempted_at > now() - make_interval(secs => :windowSeconds)
                        """)
                .param("email", normalizedEmail)
                .param("windowSeconds", window.toSeconds())
                .query(Integer.class)
                .single();
    }

    // --------------------------------------------------------------- refresh tokens

    /**
     * Stores a refresh token by its SHA-256. The raw value exists only in the response body and in
     * the caller's memory: a stolen database must not become a set of working sessions.
     *
     * @param familyId the rotation lineage (RB-BR-10); a fresh login starts its own
     */
    public UUID storeRefreshToken(
            byte[] tokenHash, UUID userId, UUID familyId, Duration ttl, String ip, String userAgent) {
        return jdbc.sql("""
                        INSERT INTO refresh_tokens (token_hash, user_id, family_id, expires_at, ip, user_agent)
                        VALUES (:hash, :userId, :familyId, now() + make_interval(secs => :ttlSeconds),
                                CAST(:ip AS inet), :userAgent)
                        RETURNING id
                        """)
                .param("hash", tokenHash)
                .param("userId", userId)
                .param("familyId", familyId)
                .param("ttlSeconds", ttl.toSeconds())
                .param("ip", ip)
                .param("userAgent", userAgent)
                .query(UUID.class)
                .single();
    }

    public Optional<StoredToken> findRefreshToken(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT rt.id, rt.user_id, rt.family_id,
                               rt.used_at IS NOT NULL AS used,
                               rt.revoked_at IS NOT NULL AS revoked,
                               rt.expires_at <= now() AS expired,
                               u.status::text AS user_status
                          FROM refresh_tokens rt
                          JOIN users u ON u.id = rt.user_id
                         WHERE rt.token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> new StoredToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getBoolean("used"),
                        rs.getBoolean("revoked"),
                        rs.getBoolean("expired"),
                        rs.getString("user_status")))
                .optional();
    }

    /**
     * Rotation: the presented token is spent the moment its replacement exists (§9.3).
     *
     * <p>{@code AND used_at IS NULL} makes it the guard as well as the record. Two refreshes racing
     * on one token produce one winner, and the loser finds the row already spent — which is the
     * reuse RB-BR-10 exists to catch, reported by the row count rather than by a second read.
     *
     * @return true when this call was the one that spent the token
     */
    public boolean markUsed(UUID tokenId, UUID replacedBy) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                           SET used_at = now(), replaced_by = :replacedBy
                         WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL
                        """)
                        .param("id", tokenId)
                        .param("replacedBy", replacedBy)
                        .update()
                == 1;
    }

    /**
     * RB-BR-10: a token presented twice means two holders exist, and only one of them is the user.
     * The whole lineage goes, not just the token — the thief may already have rotated it once.
     *
     * @return how many live tokens were revoked, which is the number of sessions just ended
     */
    public int revokeFamily(UUID familyId, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                           SET revoked_at = now(), revoked_reason = :reason
                         WHERE family_id = :familyId AND revoked_at IS NULL
                        """).param("familyId", familyId).param("reason", reason).update();
    }

    public int revokeToken(UUID tokenId, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                           SET revoked_at = now(), revoked_reason = :reason
                         WHERE id = :tokenId AND revoked_at IS NULL
                        """).param("tokenId", tokenId).param("reason", reason).update();
    }

    /** {@code POST /auth/logout-all}, and what a deactivation will call (RB-BR-07). */
    public int revokeAllForUser(UUID userId, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                           SET revoked_at = now(), revoked_reason = :reason
                         WHERE user_id = :userId AND revoked_at IS NULL
                        """).param("userId", userId).param("reason", reason).update();
    }

    /**
     * Break-glass grants in force right now (RB-BR-04), for the token claim and for {@code /me}.
     * Approved, not revoked, inside their window — the same three conditions
     * {@code ix_ag_active} covers.
     */
    public java.util.List<ActiveGrant> activeGrants(UUID userId) {
        return jdbc.sql("""
                        SELECT id, client_id, expires_at
                          FROM access_grants
                         WHERE user_id = :userId
                           AND approved_at IS NOT NULL
                           AND revoked_at IS NULL
                           AND expires_at > now()
                         ORDER BY expires_at
                        """)
                .param("userId", userId)
                .query((rs, n) -> new ActiveGrant(
                        rs.getObject("id", UUID.class),
                        rs.getObject("client_id", UUID.class),
                        instant(rs.getObject("expires_at", OffsetDateTime.class))))
                .list();
    }

    /** Expired tokens are not evidence — the audit events are — so they may be deleted. */
    public int purgeExpiredRefreshTokens(Duration keepAfterExpiry) {
        return jdbc.sql("""
                        DELETE FROM refresh_tokens
                         WHERE expires_at < now() - make_interval(secs => :graceSeconds)
                        """).param("graceSeconds", keepAfterExpiry.toSeconds()).update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * @param locked evaluated by the database, not by comparing {@code lockedUntil} here
     * @param passwordExpired RB-BR-12's 90-day maximum age, likewise
     */
    public record Credentials(
            UUID id,
            String email,
            String fullName,
            String passwordHash,
            String status,
            UUID primaryTeamId,
            boolean mustChangePassword,
            boolean locked,
            Instant lockedUntil,
            boolean passwordExpired) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        /** Never let a password hash or an email reach a log line through a generated toString. */
        @Override
        public String toString() {
            return "Credentials[id=" + id + ",status=" + status + "]";
        }
    }

    public record StoredToken(
            UUID id, UUID userId, UUID familyId, boolean used, boolean revoked, boolean expired, String userStatus) {

        public boolean userIsActive() {
            return "ACTIVE".equals(userStatus);
        }
    }

    public record ActiveGrant(UUID id, UUID clientId, Instant expiresAt) {}
}
