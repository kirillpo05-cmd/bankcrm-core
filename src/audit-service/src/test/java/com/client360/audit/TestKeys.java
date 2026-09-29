package com.client360.audit;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * An RSA key for the tests, generated once per JVM.
 *
 * <p>{@code SecurityConfig} refuses to start without a verification key, which is the behaviour
 * anyone would want — a service that accepts unverified tokens because nobody configured one is
 * worse than a service that will not start. These tests do not exercise token verification, so the
 * key only has to exist and be well formed.
 */
public final class TestKeys {

    private static final KeyPair PAIR = generate();

    private TestKeys() {}

    public static String publicKeyPem() {
        return Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA is required by every JVM", e);
        }
    }
}
