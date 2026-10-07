package com.client360.interaction.domain;

/**
 * {@code interaction.task_type} (SPEC.md §7.2.1).
 *
 * <p>{@link #KYC_REFRESH} is the one a manager cannot cancel: those tasks are created by the KYC
 * expiry job (CP-BR-06) and cancelling one silently drops a compliance obligation, so only a
 * supervisor may, and only with a reason (TR-BR-10).
 */
public enum TaskType {
    CALLBACK,
    DOCUMENT_REQUEST,
    KYC_REFRESH,
    FOLLOW_UP,
    MEETING_PREP,
    COMPLIANCE,
    OTHER
}
