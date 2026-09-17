package com.client360.interaction.api;

/**
 * Error codes owned by interaction-service (SPEC.md §6.3). Shared codes live in {@code common}'s
 * {@code ErrorCodes}; {@code CLIENT_NOT_FOUND} is repeated from client-service on purpose, because
 * ER-01 requires this service to pass that answer through unchanged.
 */
public final class InteractionErrorCodes {

    public static final String CLIENT_NOT_FOUND = "CLIENT_NOT_FOUND";
    public static final String INTERACTION_NOT_FOUND = "INTERACTION_NOT_FOUND";
    public static final String EDIT_WINDOW_EXPIRED = "EDIT_WINDOW_EXPIRED";

    private InteractionErrorCodes() {}
}
