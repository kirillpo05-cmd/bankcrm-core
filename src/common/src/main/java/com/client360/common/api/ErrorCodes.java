package com.client360.common.api;

/**
 * Error codes shared by every service (SPEC.md §4.4). Entity-specific codes such as
 * {@code CLIENT_NOT_FOUND} live in the owning service.
 *
 * <p>A code is part of the API contract: the frontend switches on it, never on the message, so
 * renaming one is a breaking change.
 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_JSON = "MALFORMED_JSON";
    public static final String INVALID_CURSOR = "INVALID_CURSOR";

    public static final String TOKEN_MISSING = "TOKEN_MISSING";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String SCOPE_VIOLATION = "SCOPE_VIOLATION";
    public static final String ROLE_REQUIRED = "ROLE_REQUIRED";

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";

    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String ILLEGAL_STATE_TRANSITION = "ILLEGAL_STATE_TRANSITION";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    /**
     * Fallback for a unique violation no service registered a specific code for. Reaching it is a
     * bug — every unique constraint should map to an {@code <ENTITY>_DUPLICATE_<FIELD>} code.
     */
    public static final String RESOURCE_DUPLICATE = "RESOURCE_DUPLICATE";

    public static final String PRECONDITION_REQUIRED = "PRECONDITION_REQUIRED";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String BUSINESS_RULE_VIOLATED = "BUSINESS_RULE_VIOLATED";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    private ErrorCodes() {}
}
