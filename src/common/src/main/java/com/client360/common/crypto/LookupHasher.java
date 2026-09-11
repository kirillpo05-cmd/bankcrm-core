package com.client360.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 of an already-normalized value under the pepper — the {@code <field>_hash} column
 * (SPEC.md §4.7). Supports exact-match lookup and uniqueness without decrypting the table, and
 * deliberately nothing else: there is no prefix or substring search on these columns.
 *
 * <p>Normalization (lowercased email, E.164 phone) is the caller's job and must be identical on the
 * write and the lookup path, or the same person hashes to two different values.
 */
@Component
public class LookupHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final KeyProvider keys;

    public LookupHasher(KeyProvider keys) {
        this.keys = keys;
    }

    public byte[] hash(String normalized) {
        if (normalized == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keys.pepper(), ALGORITHM));
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
