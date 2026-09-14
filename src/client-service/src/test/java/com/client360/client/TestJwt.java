package com.client360.client;

import com.client360.common.security.CurrentUsers;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints the access tokens the filter chain expects (SPEC.md §10.4: RS256, {@code sub} = user id).
 *
 * <p>The keypair is generated per JVM, so no key material is checked in and the tests verify the
 * real {@code NimbusJwtDecoder} path rather than a mocked security context.
 */
public final class TestJwt {

    private static final RSAKey KEY = generateKey();

    private TestJwt() {}

    /** PEM for {@code client360.security.jwt.public-key}. */
    public static String publicKeyPem() {
        try {
            byte[] der = KEY.toRSAPublicKey().getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                    + "\n-----END PUBLIC KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Cannot render the test public key", e);
        }
    }

    public static String bearerFor(UUID userId, String email, String fullName, List<String> roles) {
        return "Bearer " + tokenFor(userId, email, fullName, roles);
    }

    public static String tokenFor(UUID userId, String email, String fullName, List<String> roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim(CurrentUsers.CLAIM_EMAIL, email)
                .claim(CurrentUsers.CLAIM_NAME, fullName)
                .claim(CurrentUsers.CLAIM_ROLES, roles)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(KEY.getKeyID())
                        .build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate the test signing key", e);
        }
    }
}
