package com.client360.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Application-level AES-256-GCM for sensitive columns (SPEC.md §4.7). Stored layout:
 * {@code 96-bit nonce ‖ ciphertext ‖ 128-bit tag}.
 *
 * <p>The {@code aad} (for example {@code "client.clients.email"}) is authenticated but not stored:
 * a ciphertext copied from one column into another fails to decrypt instead of silently
 * surfacing under the wrong label.
 *
 * <p>Encryption happens here, never in SQL: {@code pgcrypto} would put keys into query logs and
 * {@code pg_stat_statements}.
 */
@Component
public class FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(KeyProvider keys) {
        this.keys = keys;
    }

    /** The version to store alongside ciphertext produced by {@link #encrypt}. */
    public short currentKeyVersion() {
        return keys.currentVersion();
    }

    /** Encrypts under the current key version; {@code null} stays {@code null}. */
    public byte[] encrypt(String plaintext, String aad) {
        if (plaintext == null) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE, keys.dataKey(keys.currentVersion()), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(byte[] stored, short keyVersion, String aad) {
        if (stored == null) {
            return null;
        }
        if (stored.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Ciphertext too short for " + aad);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keys.dataKey(keyVersion),
                    new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // Wrong key, wrong column, or a modified row. Never fall back to partial data.
            throw new IllegalStateException("Ciphertext failed authentication for " + aad, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}
