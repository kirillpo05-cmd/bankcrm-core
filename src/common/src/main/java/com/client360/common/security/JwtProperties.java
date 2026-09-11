package com.client360.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token verification (SPEC.md §10.4: RS256). Services only ever hold the public key;
 * the signing key stays with the issuer.
 *
 * @param publicKey PEM ({@code -----BEGIN PUBLIC KEY-----}) or bare base64 of an X.509
 *     SubjectPublicKeyInfo; takes precedence over {@code publicKeyLocation}
 * @param publicKeyLocation a Spring resource location of the PEM, e.g.
 *     {@code file:/run/secrets/jwt-public.pem}
 * @param issuer expected {@code iss}; not checked when blank
 */
@ConfigurationProperties("client360.security.jwt")
public record JwtProperties(String publicKey, String publicKeyLocation, String issuer) {}
