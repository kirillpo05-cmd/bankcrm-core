package com.client360.interaction.api;

/**
 * Error codes for Task / Reminder (SPEC.md §7.3). Shared codes live in {@code common}'s
 * {@code ErrorCodes}; these are the ones this module owns.
 *
 * <p>A code is part of the API contract — the frontend switches on it, never on the message — so
 * renaming one is a breaking change.
 */
public final class TaskErrorCodes {

    public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";

    /**
     * TR-BR-06, and its own code rather than a generic rule violation: the UI's answer here is
     * "escalate to a supervisor", which is a different prompt from "fix your input".
     */
    public static final String TASK_SNOOZE_LIMIT_REACHED = "TASK_SNOOZE_LIMIT_REACHED";

    private TaskErrorCodes() {}
}
