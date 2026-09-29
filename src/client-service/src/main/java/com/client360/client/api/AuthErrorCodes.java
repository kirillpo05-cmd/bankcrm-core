package com.client360.client.api;

/**
 * Error codes for authentication and RBAC (SPEC.md §9.3).
 *
 * <p>{@link #INVALID_CREDENTIALS} is one code for an unknown address and for a wrong password, on
 * purpose: two codes would make the endpoint an account-enumeration oracle, which is the whole
 * reason the login response is deliberately uninformative.
 */
public final class AuthErrorCodes {

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String ACCOUNT_DEACTIVATED = "ACCOUNT_DEACTIVATED";
    public static final String PASSWORD_EXPIRED = "PASSWORD_EXPIRED";

    public static final String REFRESH_TOKEN_INVALID = "REFRESH_TOKEN_INVALID";
    public static final String REFRESH_TOKEN_EXPIRED = "REFRESH_TOKEN_EXPIRED";

    /**
     * RB-BR-10. A distinct code because the client must react differently: not "log in again" but
     * "your session was ended because a token of yours was replayed", and the whole family is gone.
     */
    public static final String REFRESH_TOKEN_REUSED = "REFRESH_TOKEN_REUSED";

    private AuthErrorCodes() {}
}
