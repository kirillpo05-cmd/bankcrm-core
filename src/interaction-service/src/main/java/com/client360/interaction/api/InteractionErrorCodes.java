package com.client360.interaction.api;

/**
 * Error codes owned by interaction-service (SPEC.md §6.3). Shared codes live in {@code common}'s
 * {@code ErrorCodes}; {@code CLIENT_NOT_FOUND} is repeated from client-service on purpose, because
 * ER-01 requires this service to pass that answer through unchanged.
 */
public final class InteractionErrorCodes {

    public static final String CLIENT_NOT_FOUND = "CLIENT_NOT_FOUND";
    public static final String INTERACTION_NOT_FOUND = "INTERACTION_NOT_FOUND";
    /** §6.3 {@code PATCH /interactions/{id}}: the author's 15-minute window is over (IL-BR-02). */
    public static final String INTERACTION_EDIT_WINDOW_CLOSED = "INTERACTION_EDIT_WINDOW_CLOSED";

    /**
     * §6.3 {@code PATCH /interactions/{id}/ticket}: the move is not in IL-BR-08's table. A
     * {@code 409} rather than a {@code 422} because the request is well formed and would have been
     * legal from another state — the conflict is with where the ticket is now.
     */
    public static final String ILLEGAL_STATE_TRANSITION = "ILLEGAL_STATE_TRANSITION";

    /** §6.3 attachments (IL-US-06). */
    public static final String ATTACHMENT_LIMIT_REACHED = "ATTACHMENT_LIMIT_REACHED";

    public static final String ATTACHMENT_TOO_LARGE = "ATTACHMENT_TOO_LARGE";
    public static final String ATTACHMENT_TYPE_UNSUPPORTED = "ATTACHMENT_TYPE_UNSUPPORTED";

    /**
     * Not an error in the caller's request: the file is there and simply has not been checked yet.
     * A {@code 409} says "try again shortly", which is the truth, where a {@code 404} would claim
     * it does not exist and a {@code 200} would serve unscanned bytes.
     */
    public static final String ATTACHMENT_SCAN_PENDING = "ATTACHMENT_SCAN_PENDING";

    public static final String ATTACHMENT_INFECTED = "ATTACHMENT_INFECTED";

    private InteractionErrorCodes() {}
}
