package com.client360.common.security;

import java.util.List;

/**
 * Paths a service serves without a bearer token, contributed as a bean.
 *
 * <p>The filter chain lives in {@code common} and ends in {@code anyRequest().authenticated()}.
 * Exactly one service needs an exception to that — client-service, which issues the tokens the
 * others verify — and the exception belongs beside the endpoints it covers rather than in a shared
 * list every service silently inherits. A service that contributes no bean has no public endpoint
 * at all, which is the right default for the two that only consume tokens.
 */
public interface PublicEndpoints {

    /** Ant patterns, e.g. {@code /api/v1/auth/login}. */
    List<String> paths();
}
