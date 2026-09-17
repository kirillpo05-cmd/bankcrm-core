package com.client360.client.api;

/**
 * Error codes owned by client-service (SPEC.md §5.3). Shared codes live in
 * {@code common}'s {@code ErrorCodes}; these are the entity-specific ones.
 *
 * <p>A code is part of the API contract — the frontend switches on it, never on the message — so
 * renaming one is a breaking change.
 */
public final class ClientErrorCodes {

    public static final String CLIENT_NOT_FOUND = "CLIENT_NOT_FOUND";
    public static final String CLIENT_MERGED = "CLIENT_MERGED";
    public static final String CLIENT_DUPLICATE_EXTERNAL_REF = "CLIENT_DUPLICATE_EXTERNAL_REF";
    public static final String CLIENT_DUPLICATE_EMAIL = "CLIENT_DUPLICATE_EMAIL";
    public static final String CLIENT_DUPLICATE_TAX_ID = "CLIENT_DUPLICATE_TAX_ID";
    public static final String PRODUCT_DUPLICATE_EXTERNAL_ID = "PRODUCT_DUPLICATE_EXTERNAL_ID";
    public static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    /**
     * Not a code but a {@code details[].reason}: CP-BR-05 denies KYC approval on a client the
     * caller owns, and the UI needs to tell that apart from a plain missing permission.
     */
    public static final String SELF_APPROVAL_FORBIDDEN = "SELF_APPROVAL_FORBIDDEN";

    private ClientErrorCodes() {}
}
