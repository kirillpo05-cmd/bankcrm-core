package com.client360.common.crypto;

import javax.crypto.SecretKey;

/**
 * Source of the field-encryption data keys and the lookup-hash pepper (SPEC.md §4.7). Production
 * wires this to the KMS/vault; locally {@link StaticKeyProvider} reads them from configuration.
 *
 * <p>Every method may throw {@link KeyUnavailableException}. Callers must let it propagate: a read
 * that cannot decrypt fails with {@code 503} rather than serving a half-decrypted record
 * (CP-EC-13).
 */
public interface KeyProvider {

    /** The key version new ciphertext is written under; stored in each row's {@code key_version}. */
    short currentVersion();

    SecretKey dataKey(short version);

    /** HMAC key for the {@code <field>_hash} lookup columns — separate from every data key. */
    byte[] pepper();
}
