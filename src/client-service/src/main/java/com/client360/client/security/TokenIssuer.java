package com.client360.client.security;

import com.client360.common.security.CurrentUsers;
import com.client360.common.time.DatabaseClock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Signs the access tokens every service verifies (SPEC.md §9.3, §10.4: RS256).
 *
 * <p>This is the one place in the system that holds a signing key, and the reason
 * {@code scripts/dev-jwt.sh token} existed at all: until now nothing issued tokens, so local
 * development minted them by hand. From here the only issuer is {@code POST /auth/login}.
 *
 * <p>The claims are RB-BR-05's, and the interesting one is {@code permissions}: a snapshot of what
 * the user may do, with scopes. interaction-service and audit-service hold no RBAC tables — by
 * design, since one authorization authority is the point — so the token is how a decision reaches
 * them without a call per request. The 15-minute lifetime is what bounds the staleness, and the
 * service that <em>does</em> own the tables never reads the claim back.
 */
@Component
@EnableConfigurationProperties(IssuerProperties.class)
public class TokenIssuer {

    private final IssuerProperties properties;
    private final RSASSASigner signer;
    private final DatabaseClock clock;

    public TokenIssuer(IssuerProperties properties, ResourceLoader resourceLoader, DatabaseClock clock) {
        this.properties = properties;
        this.signer = new RSASSASigner(loadPrivateKey(properties, resourceLoader));
        this.clock = clock;
    }

    /**
     * @return the serialized token and the instant it stops being accepted, which is also
     *     {@code /me}'s {@code sessionExpiresAt}
     */
    public IssuedToken issue(TokenSubject subject) {
        // From PostgreSQL, not the JVM (rule 7): the refresh token's expires_at is computed as
        // now() + ttl in SQL, and two clocks would let an access token outlive the session that
        // could renew it — or expire before it.
        Instant issuedAt = clock.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        Map<String, String> permissions = new LinkedHashMap<>();
        subject.permissions().forEach((code, scope) -> permissions.put(code, scope.name()));

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject.id().toString())
                .claim(CurrentUsers.CLAIM_EMAIL, subject.email())
                .claim(CurrentUsers.CLAIM_NAME, subject.fullName())
                .claim(CurrentUsers.CLAIM_ROLES, subject.roles())
                .claim(CurrentUsers.CLAIM_PERMISSIONS, permissions)
                .claim(CurrentUsers.CLAIM_TEAMS, ids(subject.teamIds()))
                .claim(CurrentUsers.CLAIM_GRANTS, ids(subject.grantIds()))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString());
        if (properties.issuer() != null && !properties.issuer().isBlank()) {
            claims.issuer(properties.issuer());
        }

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            // Not a client error: the key is misconfigured or unusable, and no retry fixes it.
            throw new IllegalStateException("Cannot sign an access token", e);
        }
        return new IssuedToken(
                jwt.serialize(), expiresAt, properties.accessTokenTtl().toSeconds());
    }

    private static List<String> ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {

        /** Never let a token reach a log line through a generated toString. */
        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }

    private static RSAPrivateKey loadPrivateKey(IssuerProperties properties, ResourceLoader resourceLoader) {
        String pem = properties.privateKey();
        if ((pem == null || pem.isBlank()) && properties.privateKeyLocation() != null) {
            try (InputStream in =
                    resourceLoader.getResource(properties.privateKeyLocation()).getInputStream()) {
                pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot read client360.security.issuer.private-key-location=" + properties.privateKeyLocation()
                                + " (locally: run scripts/dev-jwt.sh keys)",
                        e);
            }
        }
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("No JWT signing key: set client360.security.issuer.private-key or "
                    + "private-key-location. Without one this service cannot issue a session.");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            // The message must not echo the value: it is the signing key.
            throw new IllegalStateException("client360.security.issuer private key is not a PKCS#8 RSA key", e);
        }
    }
}
