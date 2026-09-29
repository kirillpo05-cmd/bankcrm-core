package com.client360.client.service;

import com.client360.client.api.AuthErrorCodes;
import com.client360.client.persistence.AuthRepository;
import com.client360.client.persistence.PermissionRepository;
import com.client360.client.persistence.TeamRepository;
import com.client360.client.security.IssuerProperties;
import com.client360.client.security.TokenIssuer;
import com.client360.client.security.TokenSubject;
import com.client360.common.api.ApiException;
import com.client360.common.ratelimit.RateLimiter;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.CurrentUsers;
import com.client360.common.security.Scope;
import com.client360.common.web.RequestMetadata;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authentication: {@code POST /auth/login}, {@code /auth/refresh}, {@code /auth/logout},
 * {@code /auth/logout-all} and the identity behind {@code GET /me} (SPEC.md §9.3).
 *
 * <p>This is the service that ends the MVP's standing assumption. Until now nothing issued tokens:
 * {@code scripts/dev-jwt.sh} minted them from a key on a developer's disk, and every service simply
 * trusted whatever arrived correctly signed. From here a session exists because someone proved they
 * knew a password, and it can be ended.
 *
 * <p>Three rules shape almost every decision below.
 *
 * <ul>
 *   <li><strong>The endpoint must not answer "does this account exist".</strong> One error code for
 *       an unknown address and a wrong password (§9.3), a bcrypt comparison on both paths so the
 *       timing matches (RB-BR-12), and nothing specific about an account until the caller has proved
 *       they know its password.
 *   <li><strong>Every attempt costs the attempter.</strong> The per-account counter and the per-IP
 *       limit are both needed and count independently (RB-BR-11), and the bookkeeping commits in its
 *       own transaction so a rejected request still records the attempt.
 *   <li><strong>Timestamps come from PostgreSQL</strong> (rule 7). Lock expiry, token expiry and
 *       password age are all evaluated in SQL, so two instances agree on when a lock lifts.
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** RB-BR-11: 5 consecutive failures, 15 minutes, counted per account. */
    private static final int LOCK_THRESHOLD = 5;

    private static final Duration LOCK_FOR = Duration.ofMinutes(15);

    /** RB-BR-11: 10 attempts a minute from one IP, counted independently of the account. */
    private static final int IP_ATTEMPTS_PER_MINUTE = 10;

    private static final String LOGIN_BUCKET = "auth.login";

    /** 256 bits of entropy. The token is a bearer secret; it is never derived from anything guessable. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AuthRepository auth;
    private final PermissionRepository permissions;
    private final TeamRepository teams;
    private final TokenIssuer tokens;
    private final IssuerProperties issuer;
    private final PasswordEncoder passwords;
    private final RateLimiter rateLimiter;
    private final AuthEvents events;
    private final SecureRandom random = new SecureRandom();

    /**
     * A bcrypt hash of a value nobody knows, compared against when the address is unknown.
     *
     * <p>Encoded at startup with the same encoder real passwords use, so its cost always matches:
     * a hard-coded hash would stop being constant-time the moment the cost was raised, and the
     * timing difference would be an account-existence oracle again (RB-BR-12).
     */
    private final String dummyHash;

    /**
     * Login bookkeeping commits separately from the request.
     *
     * <p>A failed login must still increment the counter, record the attempt and emit the audit
     * event — those are the whole point — and the request that follows them throws. A
     * {@code TransactionTemplate} rather than {@code @Transactional} on a private method, because a
     * self-invocation would not pass through the proxy and would silently join the caller instead.
     */
    private final TransactionTemplate ownTransaction;

    public AuthService(
            AuthRepository auth,
            PermissionRepository permissions,
            TeamRepository teams,
            TokenIssuer tokens,
            IssuerProperties issuer,
            PasswordEncoder passwords,
            RateLimiter rateLimiter,
            AuthEvents events,
            PlatformTransactionManager transactions) {
        this.auth = auth;
        this.permissions = permissions;
        this.teams = teams;
        this.tokens = tokens;
        this.issuer = issuer;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dummyHash = passwords.encode(newSecret());
    }

    // ---------------------------------------------------------------------- login

    /**
     * @throws ApiException {@code 401 INVALID_CREDENTIALS} for an unknown address or a wrong
     *     password; {@code 403} with a specific code once the password is known to be right;
     *     {@code 429} past the per-IP limit
     */
    public Session login(String email, String password) {
        RequestMetadata request = RequestMetadata.current().orElse(null);
        String ip = request == null ? null : request.ip();
        String userAgent = request == null ? null : request.userAgent();

        // RB-BR-11: per IP. Counting per account alone lets an attacker spray one password across
        // many accounts; counting per IP alone punishes a shared branch NAT. Both, independently.
        rateLimiter.check("ip:" + ip, LOGIN_BUCKET, IP_ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1));

        String normalized = normalize(email);
        Optional<AuthRepository.Credentials> found = auth.findByEmail(normalized);

        // RB-BR-12: the comparison runs on both paths, against a hash of the same cost. An unknown
        // address must cost the same as a known one, or the response time answers the question the
        // status code refuses to.
        boolean correctPassword = found.map(user -> passwords.matches(password, user.passwordHash()))
                .orElseGet(() -> {
                    passwords.matches(password, dummyHash);
                    return false;
                });

        if (found.isEmpty()) {
            recordFailure(normalized, null, "USER_UNKNOWN", ip, userAgent);
            throw invalidCredentials();
        }
        AuthRepository.Credentials user = found.get();
        if (!correctPassword) {
            recordFailure(normalized, user, "BAD_PASSWORD", ip, userAgent);
            throw invalidCredentials();
        }

        // The password is right, so from here the answer may be specific.
        //
        // §9.3 lists ACCOUNT_LOCKED and ACCOUNT_DEACTIVATED as outcomes of a login, and they are —
        // but reporting either to a caller who did *not* know the password would rebuild the
        // enumeration oracle the single INVALID_CREDENTIALS code exists to prevent: five wrong
        // guesses would turn any address into a yes-or-no answer about whether it is a real account.
        // So the order is password first, account state second. A genuine user who types their real
        // password still learns exactly why they cannot get in, which is the case the codes are for.
        if (user.locked()) {
            recordFailure(normalized, user, "ACCOUNT_LOCKED", ip, userAgent);
            throw new ApiException(
                            HttpStatus.FORBIDDEN,
                            AuthErrorCodes.ACCOUNT_LOCKED,
                            "This account is temporarily locked after repeated failed sign-ins.")
                    .detail("lockedUntil", user.lockedUntil());
        }
        if (!user.isActive()) {
            recordFailure(normalized, user, "ACCOUNT_DEACTIVATED", ip, userAgent);
            throw ApiException.forbidden(
                    AuthErrorCodes.ACCOUNT_DEACTIVATED, "This account is no longer active. Contact an administrator.");
        }
        if (user.passwordExpired()) {
            recordFailure(normalized, user, "PASSWORD_EXPIRED", ip, userAgent);
            throw ApiException.forbidden(
                            AuthErrorCodes.PASSWORD_EXPIRED, "This password has expired and must be changed.")
                    .detail("changePasswordUrl", "/password/change");
        }
        return openSession(user, normalized, ip, userAgent);
    }

    /**
     * The attempt row, the counter and the audit event, committed together and separately from the
     * rejection that follows (rule 3: the event shares the transaction of the change it records).
     */
    private void recordFailure(
            String email, AuthRepository.Credentials user, String reason, String ip, String userAgent) {
        ownTransaction.executeWithoutResult(status -> {
            Optional<Instant> lockedUntil =
                    user == null ? Optional.empty() : auth.registerFailure(user.id(), LOCK_THRESHOLD, LOCK_FOR);
            auth.recordAttempt(email, user == null ? null : user.id(), false, reason, ip, userAgent);
            events.loginFailed(user == null ? null : user.id(), email, reason, lockedUntil.isPresent());
            lockedUntil.ifPresent(until ->
                    // Deliberately not the email: rule 10 keeps identifiers in logs, not people.
                    log.warn("Account {} locked until {} after {} failed sign-ins", user.id(), until, LOCK_THRESHOLD));
        });
    }

    private Session openSession(AuthRepository.Credentials user, String email, String ip, String userAgent) {
        Map<String, Scope> effective = permissions.effectivePermissions(user.id());
        List<PermissionRepository.RoleGrant> roles = permissions.rolesOf(user.id());
        List<UUID> teamIds = List.copyOf(teams.scopeTeamsOf(user.id()));
        List<UUID> grantIds = auth.activeGrants(user.id()).stream()
                .map(AuthRepository.ActiveGrant::id)
                .toList();

        TokenIssuer.IssuedToken accessToken = tokens.issue(new TokenSubject(
                user.id(),
                user.email(),
                user.fullName(),
                roles.stream().map(PermissionRepository.RoleGrant::code).toList(),
                effective,
                teamIds,
                grantIds));

        // A fresh login starts a new rotation family: RB-BR-10 revokes a family on reuse, and two
        // devices sharing one family would mean signing out of a laptop killed a phone.
        UUID familyId = UUID.randomUUID();
        String refreshToken = newSecret();
        ownTransaction.executeWithoutResult(status -> {
            auth.storeRefreshToken(sha256(refreshToken), user.id(), familyId, issuer.refreshTokenTtl(), ip, userAgent);
            auth.registerSuccess(user.id());
            auth.recordAttempt(email, user.id(), true, null, ip, userAgent);
            events.loginSucceeded(
                    user.id(),
                    user.email(),
                    roles.isEmpty() ? null : roles.getFirst().code(),
                    user.mustChangePassword());
        });

        return new Session(
                accessToken.value(),
                accessToken.expiresInSeconds(),
                refreshToken,
                issuer.refreshTokenTtl().toSeconds(),
                user.id(),
                user.email(),
                user.fullName(),
                roles.stream().map(PermissionRepository.RoleGrant::code).toList(),
                teams.findById(user.primaryTeamId()).orElse(null),
                user.mustChangePassword());
    }

    // -------------------------------------------------------------------- refresh

    /**
     * Rotation with reuse detection (§9.3, RB-BR-10).
     *
     * <p>The presented token is spent before the new one is returned, and spending it is a
     * conditional update. If it was already spent, there are two holders of a token only one party
     * should ever have had — so the whole family goes, every session with it, and the caller is told
     * which of the three refresh failures happened.
     */
    public Session refresh(String presented) {
        RequestMetadata request = RequestMetadata.current().orElse(null);
        String ip = request == null ? null : request.ip();
        String userAgent = request == null ? null : request.userAgent();

        AuthRepository.StoredToken stored = auth.findRefreshToken(sha256(presented))
                .orElseThrow(() -> unauthorized(AuthErrorCodes.REFRESH_TOKEN_INVALID, "This session is not valid."));

        if (stored.used()) {
            // RB-BR-10. Revoking the family is the response, not a side effect: the legitimate user
            // is signed out everywhere on purpose, because the alternative is leaving a thief with a
            // working session.
            int revoked = ownTransaction.execute(status -> {
                int count = auth.revokeFamily(stored.familyId(), "REUSED");
                events.tokenReuseDetected(stored.userId(), stored.familyId(), count);
                return count;
            });
            log.warn(
                    "Refresh token reuse detected for user {}; revoked family {} ({} live session(s))",
                    stored.userId(),
                    stored.familyId(),
                    revoked);
            throw unauthorized(
                    AuthErrorCodes.REFRESH_TOKEN_REUSED,
                    "This session was ended because a sign-in token was presented twice.");
        }
        if (stored.revoked()) {
            // Includes every token of a family revoked by the branch above, which is why a stolen
            // token stops working for the thief too.
            throw unauthorized(AuthErrorCodes.REFRESH_TOKEN_INVALID, "This session is not valid.");
        }
        if (stored.expired()) {
            throw unauthorized(AuthErrorCodes.REFRESH_TOKEN_EXPIRED, "This session has expired. Sign in again.");
        }
        if (!stored.userIsActive()) {
            throw ApiException.forbidden(
                    AuthErrorCodes.ACCOUNT_DEACTIVATED, "This account is no longer active. Contact an administrator.");
        }

        AuthRepository.Credentials user = auth.findById(stored.userId())
                .orElseThrow(() -> unauthorized(AuthErrorCodes.REFRESH_TOKEN_INVALID, "This session is not valid."));

        // Permissions are re-read here, not carried over: RB-BR-14 says a role change takes effect on
        // the next refresh, and this is that moment.
        Map<String, Scope> effective = permissions.effectivePermissions(user.id());
        List<PermissionRepository.RoleGrant> roles = permissions.rolesOf(user.id());
        TokenIssuer.IssuedToken accessToken = tokens.issue(new TokenSubject(
                user.id(),
                user.email(),
                user.fullName(),
                roles.stream().map(PermissionRepository.RoleGrant::code).toList(),
                effective,
                List.copyOf(teams.scopeTeamsOf(user.id())),
                auth.activeGrants(user.id()).stream()
                        .map(AuthRepository.ActiveGrant::id)
                        .toList()));

        String rotated = newSecret();
        Boolean won = ownTransaction.execute(status -> {
            UUID replacement = auth.storeRefreshToken(
                    sha256(rotated), user.id(), stored.familyId(), issuer.refreshTokenTtl(), ip, userAgent);
            if (!auth.markUsed(stored.id(), replacement)) {
                // Another request spent it between the read above and here. Roll this replacement
                // back and let the loser be treated as the reuse it is indistinguishable from.
                status.setRollbackOnly();
                return false;
            }
            events.tokenRefreshed(user.id(), user.email(), stored.familyId());
            return true;
        });
        if (!Boolean.TRUE.equals(won)) {
            int revoked = ownTransaction.execute(status -> {
                int count = auth.revokeFamily(stored.familyId(), "REUSED");
                events.tokenReuseDetected(stored.userId(), stored.familyId(), count);
                return count;
            });
            log.warn(
                    "Concurrent refresh on one token for user {}; revoked family {} ({} live session(s))",
                    stored.userId(),
                    stored.familyId(),
                    revoked);
            throw unauthorized(
                    AuthErrorCodes.REFRESH_TOKEN_REUSED,
                    "This session was ended because a sign-in token was presented twice.");
        }

        return new Session(
                accessToken.value(),
                accessToken.expiresInSeconds(),
                rotated,
                issuer.refreshTokenTtl().toSeconds(),
                user.id(),
                user.email(),
                user.fullName(),
                roles.stream().map(PermissionRepository.RoleGrant::code).toList(),
                teams.findById(user.primaryTeamId()).orElse(null),
                user.mustChangePassword());
    }

    // --------------------------------------------------------------------- logout

    /**
     * Revokes the presented refresh token. Unknown or already revoked is still {@code 204}: logout is
     * idempotent, and an error here would tell an unauthenticated caller which tokens exist.
     */
    public void logout(String presented) {
        auth.findRefreshToken(sha256(presented))
                .ifPresent(stored -> ownTransaction.executeWithoutResult(status -> {
                    int revoked = auth.revokeToken(stored.id(), "LOGOUT");
                    if (revoked > 0) {
                        events.loggedOut(stored.userId(), null, false, revoked);
                    }
                }));
    }

    /** RB-US-04's "sign out everywhere", and what a supervisor asks for when a laptop goes missing. */
    public void logoutAll(CurrentUser caller) {
        ownTransaction.executeWithoutResult(status -> {
            int revoked = auth.revokeAllForUser(caller.id(), "LOGOUT_ALL");
            events.loggedOut(caller.id(), caller.email(), true, revoked);
        });
    }

    // ------------------------------------------------------------------------ me

    /**
     * The caller's own identity, roles, teams, effective permissions and active grants (§9.3).
     *
     * <p>Read from the tables rather than from the token that authenticated the request. The token is
     * a 15-minute snapshot; this endpoint is what the SPA builds its navigation from, so answering it
     * from the token would leave a user looking at menu items a role change had already taken away.
     */
    public Me me(CurrentUser caller) {
        AuthRepository.Credentials user = auth.findById(caller.id())
                // A valid token whose subject has no row: the account was hard-deleted, which
                // RB-BR-15 says never happens. Refusing is the only honest answer.
                .orElseThrow(() -> unauthorized(AuthErrorCodes.REFRESH_TOKEN_INVALID, "This session is not valid."));
        return new Me(
                user.id(),
                user.fullName(),
                user.email(),
                user.status(),
                permissions.rolesOf(user.id()),
                teams.findById(user.primaryTeamId()).orElse(null),
                teams.scopeMembershipsOf(user.id()),
                permissions.effectivePermissions(user.id()),
                auth.activeGrants(user.id()),
                CurrentUsers.sessionExpiresAt().orElse(null));
    }

    // -------------------------------------------------------------------- helpers

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        // One message for both causes, to match the one code (§9.3).
        return unauthorized(AuthErrorCodes.INVALID_CREDENTIALS, "Email or password is incorrect.");
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private String newSecret() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not bcrypt: the stored value is a 256-bit random secret, so there is nothing to brute
     * force and nothing for key stretching to protect. A plain digest also keeps the lookup a single
     * indexed read, which bcrypt could not be.
     */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** §9.3's login and refresh response. */
    public record Session(
            String accessToken,
            long expiresInSeconds,
            String refreshToken,
            long refreshExpiresInSeconds,
            UUID userId,
            String email,
            String fullName,
            List<String> roles,
            TeamRepository.TeamRef primaryTeam,
            boolean mustChangePassword) {

        /** Never let a token reach a log line through a generated toString. */
        @Override
        public String toString() {
            return "Session[userId=" + userId + "]";
        }
    }

    /** §9.3's {@code GET /me}. */
    public record Me(
            UUID id,
            String fullName,
            String email,
            String status,
            List<PermissionRepository.RoleGrant> roles,
            TeamRepository.TeamRef primaryTeam,
            List<TeamRepository.Membership> teams,
            Map<String, Scope> permissions,
            List<AuthRepository.ActiveGrant> activeGrants,
            Instant sessionExpiresAt) {}
}
