package com.client360.interaction.domain;

import java.util.Set;

/**
 * {@code interaction.task_status} and the legal transitions between them (TR-BR-03).
 *
 * <p>The table lives here rather than in the service so the illegal-transition answer can name the
 * legal targets, and so the UI's disabled buttons and the service's refusal come from one list. The
 * UI disabling a target is a convenience; this is the enforcement.
 */
public enum TaskStatus {
    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED;

    /**
     * TR-BR-03. {@code CANCELLED} is terminal; {@code DONE} reopens to {@code OPEN} and nothing
     * else, because reopening is an admission that the work was not finished rather than a way to
     * move a finished task sideways.
     */
    public Set<TaskStatus> legalTargets() {
        return switch (this) {
            case OPEN -> Set.of(IN_PROGRESS, DONE, CANCELLED);
            case IN_PROGRESS -> Set.of(OPEN, DONE, CANCELLED);
            // Supervisor only, and it needs a reason — see TaskService.
            case DONE -> Set.of(OPEN);
            case CANCELLED -> Set.of();
        };
    }

    /** Whether work is still owed. The half of the status space TR-BR-02 calls live. */
    public boolean isLive() {
        return this == OPEN || this == IN_PROGRESS;
    }

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}
