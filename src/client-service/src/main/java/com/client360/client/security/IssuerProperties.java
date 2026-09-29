package com.client360.client.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token issuance (SPEC.md §9.3, RB-BR-05). Only client-service binds these: it is the
 * authorization authority, so it is the only service that holds a signing key.
 *
 * <p>Deliberately a different prefix from {@code client360.security.jwt}, which every service binds
 * for the <em>public</em> key. A private key that could be named under the shared prefix is a
 * private key that can be handed to the wrong service by a copied environment file.
 *
 * @param privateKey PEM ({@code -----BEGIN PRIVATE KEY-----}) or bare base64 of a PKCS#8
 *     {@code PrivateKeyInfo}; takes precedence over {@code privateKeyLocation}
 * @param privateKeyLocation a Spring resource location of the PEM, e.g.
 *     {@code file:/run/secrets/jwt-private.pem}
 * @param issuer the {@code iss} claim to stamp; must match what the services verify
 * @param accessTokenTtl RB-BR-05's 15 minutes — the window a revocation can lag by
 * @param refreshTokenTtl §9.3's {@code refreshExpiresIn}, 8 hours: one working day, so a session
 *     does not outlive the shift it was opened in
 */
@ConfigurationProperties("client360.security.issuer")
public record IssuerProperties(
        String privateKey,
        String privateKeyLocation,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    /** RB-BR-05 states the bound as a fact about the system, so it is enforced rather than documented. */
    private static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    public IssuerProperties {
        accessTokenTtl = accessTokenTtl == null ? MAX_ACCESS_TOKEN_TTL : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofHours(8) : refreshTokenTtl;
        if (accessTokenTtl.isNegative()
                || accessTokenTtl.isZero()
                || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("client360.security.issuer.access-token-ttl must be in (0, 15m]: "
                    + "a longer access token widens the window a revoked user keeps working (RB-BR-05)");
        }
        if (refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalArgumentException(
                    "client360.security.issuer.refresh-token-ttl must exceed access-token-ttl, or a session "
                            + "cannot outlive one access token and refresh is pointless");
        }
    }
}
