package com.client360.common.crypto;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Key material from configuration. Fails at startup, not at the first request, when the material
 * is missing or malformed — a service that cannot decrypt must not report itself healthy.
 */
public class StaticKeyProvider implements KeyProvider {

    private final short version;
    private final SecretKey dataKey;
    private final byte[] pepper;

    public StaticKeyProvider(CryptoProperties properties) {
        this.version = properties.keyVersion();
        this.dataKey = new SecretKeySpec(decode("client360.crypto.data-key", properties.dataKey(), 32, 32), "AES");
        this.pepper = decode("client360.crypto.pepper", properties.pepper(), 16, 1024);
    }

    @Override
    public short currentVersion() {
        return version;
    }

    @Override
    public SecretKey dataKey(short requested) {
        if (requested != version) {
            // A row written under a retired key: the rotation job (§4.7) re-encrypts these.
            throw new KeyUnavailableException("No data key for key_version " + requested);
        }
        return dataKey;
    }

    @Override
    public byte[] pepper() {
        return pepper.clone();
    }

    private static byte[] decode(String property, String base64, int minBytes, int maxBytes) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(property + " is not set. Locally it comes from .env (see .env.example).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not valid base64");
        }
        if (bytes.length < minBytes || bytes.length > maxBytes) {
            throw new IllegalStateException(property + " must decode to "
                    + (minBytes == maxBytes ? minBytes : "at least " + minBytes) + " bytes");
        }
        return bytes;
    }
}
