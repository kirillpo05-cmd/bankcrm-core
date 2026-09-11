package com.client360.common.crypto;

/** The KMS could not supply a key. Surfaces as {@code 503 DEPENDENCY_UNAVAILABLE} (CP-EC-13). */
public class KeyUnavailableException extends RuntimeException {

    public KeyUnavailableException(String message) {
        super(message);
    }

    public KeyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
