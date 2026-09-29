package com.client360.client.security;

import com.client360.common.security.PublicEndpoints;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The three endpoints client-service serves without a bearer token (SPEC.md §9.3).
 *
 * <p>Enumerated rather than matched by prefix. {@code /api/v1/auth/**} would have made
 * {@code /auth/logout-all} public the moment it was added, and that one acts on every session the
 * caller has. A list that has to be edited to grow is the point.
 */
@Component
public class AuthPublicEndpoints implements PublicEndpoints {

    @Override
    public List<String> paths() {
        return List.of(
                // Issues a session; cannot require one.
                "/api/v1/auth/login",
                // Renews a session whose access token has usually already expired.
                "/api/v1/auth/refresh",
                // Ends one session, proved by the refresh token in the body rather than by a
                // header that has probably expired by the time the user clicks "sign out".
                "/api/v1/auth/logout");
    }
}
