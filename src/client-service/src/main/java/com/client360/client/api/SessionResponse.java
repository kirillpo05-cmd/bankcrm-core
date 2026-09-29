package com.client360.client.api;

import com.client360.client.persistence.TeamRepository;
import com.client360.client.service.AuthService;
import java.util.List;
import java.util.UUID;

/**
 * The body of {@code POST /auth/login} and {@code POST /auth/refresh} (SPEC.md §9.3).
 *
 * <p>Both tokens are returned in the body rather than in a {@code Set-Cookie}: the client is a SPA
 * on a different origin from the API, and a body keeps the contract the same for the mobile client
 * §11.2 lists as out of scope but foreseeable. It also means neither token is ever attached to a
 * request automatically, which is what makes the API immune to CSRF without a token dance.
 *
 * @param expiresIn seconds of life left in the access token, so a client can schedule a refresh
 *     rather than wait for a {@code 401}
 */
public record SessionResponse(
        String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn, User user) {

    private static final String BEARER = "Bearer";

    public static SessionResponse of(AuthService.Session session) {
        return new SessionResponse(
                session.accessToken(),
                BEARER,
                session.expiresInSeconds(),
                session.refreshToken(),
                session.refreshExpiresInSeconds(),
                new User(
                        session.userId(),
                        session.fullName(),
                        session.email(),
                        session.roles(),
                        Team.of(session.primaryTeam()),
                        session.mustChangePassword()));
    }

    /**
     * Enough to render the header bar without a second call. Not the caller's permissions —
     * {@code GET /me} answers that, and it is read from the tables rather than from the token, so a
     * role change that landed a minute ago is already reflected.
     */
    public record User(
            UUID id, String fullName, String email, List<String> roles, Team primaryTeam, boolean mustChangePassword) {}

    public record Team(UUID id, String name) {

        static Team of(TeamRepository.TeamRef team) {
            return team == null ? null : new Team(team.id(), team.name());
        }
    }

    /** Never let a token reach a log line through a generated toString. */
    @Override
    public String toString() {
        return "SessionResponse[user=" + (user == null ? null : user.id()) + "]";
    }
}
