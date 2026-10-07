package com.client360.interaction.domain;

/**
 * {@code interaction.reminder_status} (SPEC.md §7.2.3).
 *
 * <p>{@link #CANCELLED} is also how a reminder whose time had already passed at creation is
 * recorded (TR-BR-07): it is not scheduled, and firing it instantly would be a notification about
 * a deadline the user already knows they missed.
 */
public enum ReminderStatus {
    SCHEDULED,
    SENT,
    FAILED,
    CANCELLED
}
