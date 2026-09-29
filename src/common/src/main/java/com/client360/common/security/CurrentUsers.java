package com.client360.common.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Reads the caller from the security context.
 *
 * <p>Token claims (the contract v2's {@code POST /auth/login} will issue against):
 * {@code sub} = user id (UUID), {@code email}, {@code name}, {@code roles} (array).
 */
public final class CurrentUsers {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_ROLES = "roles";

    /**
     * RB-BR-05's authorization claims, written by client-service's issuer: {@code permissions} is
     * an object of permission code to scope name, {@code teamIds} and {@code grantIds} are arrays.
     *
     * <p>Declared here rather than in the issuer because they are a contract between two services:
     * client-service writes them, and a service with no RBAC tables of its own reads them. The
     * 15-minute token lifetime is what bounds how stale that copy can be — which is why nothing in
     * client-service itself authorizes from these, having the tables to hand.
     */
    public static final String CLAIM_PERMISSIONS = "permissions";

    public static final String CLAIM_TEAMS = "teamIds";
    public static final String CLAIM_GRANTS = "grantIds";

    private CurrentUsers() {}

    public static Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(fromJwt(token.getToken()));
        }
        return Optional.empty();
    }

    public static CurrentUser require() {
        return find().orElseThrow(() -> new IllegalStateException("No authenticated user on this thread"));
    }

    /**
     * The raw bearer token, for relaying the caller's identity on a service-to-service call so the
     * callee authorizes the same subject. Never log it.
     */
    public static Optional<String> bearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(token.getToken().getTokenValue());
        }
        return Optional.empty();
    }

    /**
     * When the caller's access token stops being accepted — {@code GET /me}'s
     * {@code sessionExpiresAt} (§9.3), so the SPA can refresh before a request fails rather than
     * after.
     */
    public static Optional<java.time.Instant> sessionExpiresAt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.ofNullable(token.getToken().getExpiresAt());
        }
        return Optional.empty();
    }

    static CurrentUser fromJwt(Jwt jwt) {
        List<String> roles = jwt.hasClaim(CLAIM_ROLES) ? jwt.getClaimAsStringList(CLAIM_ROLES) : List.of();
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(CLAIM_EMAIL),
                jwt.getClaimAsString(CLAIM_NAME),
                roles);
    }
}
