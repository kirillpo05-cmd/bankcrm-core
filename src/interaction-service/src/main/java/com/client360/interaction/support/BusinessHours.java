package com.client360.interaction.support;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Business-day arithmetic for the ticket SLA: Mon–Fri 09:00–18:00 in the owning team's timezone
 * (IL-BR-07, TR-BR-14).
 *
 * <p>The timezone is an argument and never a default, because the rule is the <em>team's</em> zone —
 * not the server's and not the viewer's. A Warsaw team and a London team raising the same
 * {@code CRITICAL} ticket at the same instant get different deadlines, and that is correct.
 *
 * <p><strong>Holidays are not modelled.</strong> Only weekends are excluded. SPEC.md Q-03 is still
 * open on where a real calendar comes from and proposes a static per-team table seeded annually;
 * until that exists, a ticket raised the day before a public holiday gets a deadline that falls on
 * it. That is a known overestimate of available time, it is visible here rather than buried, and it
 * is the one thing to fix when Q-03 closes. Nothing else in this class needs to change.
 */
public final class BusinessHours {

    public static final LocalTime OPEN = LocalTime.of(9, 0);
    public static final LocalTime CLOSE = LocalTime.of(18, 0);

    private BusinessHours() {}

    /**
     * {@code start} plus {@code businessTime} of open hours.
     *
     * <p>A start outside opening hours is moved forward to the next opening before anything is
     * consumed, so a ticket raised at 22:00 on Friday has its whole SLA from Monday 09:00 — it does
     * not quietly burn the weekend.
     */
    public static Instant plus(Instant start, Duration businessTime, ZoneId zone) {
        if (businessTime.isNegative() || businessTime.isZero()) {
            return start;
        }
        ZonedDateTime cursor = atOrAfterOpening(start.atZone(zone));
        Duration remaining = businessTime;
        while (true) {
            ZonedDateTime closesAt = cursor.with(CLOSE);
            Duration availableToday = Duration.between(cursor, closesAt);
            if (remaining.compareTo(availableToday) <= 0) {
                return cursor.plus(remaining).toInstant();
            }
            remaining = remaining.minus(availableToday);
            cursor = nextOpening(cursor);
        }
    }

    /**
     * Open hours elapsed between two instants — how much of the SLA a pause actually consumed.
     *
     * <p>This is what makes {@code WAITING_CLIENT} honest in both directions (IL-BR-08). A ticket
     * parked over a weekend has paused for two calendar days and zero business hours, so leaving
     * {@code WAITING_CLIENT} on Monday morning gives back nothing it was not owed.
     */
    public static Duration between(Instant from, Instant to, ZoneId zone) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        ZonedDateTime cursor = atOrAfterOpening(from.atZone(zone));
        ZonedDateTime end = to.atZone(zone);
        Duration total = Duration.ZERO;
        while (cursor.isBefore(end)) {
            ZonedDateTime closesAt = cursor.with(CLOSE);
            ZonedDateTime segmentEnd = end.isBefore(closesAt) ? end : closesAt;
            if (segmentEnd.isAfter(cursor)) {
                total = total.plus(Duration.between(cursor, segmentEnd));
            }
            cursor = nextOpening(cursor);
        }
        return total;
    }

    /** The same moment if the office is open, otherwise the next moment it is. */
    private static ZonedDateTime atOrAfterOpening(ZonedDateTime moment) {
        if (isWeekend(moment) || !moment.toLocalTime().isBefore(CLOSE)) {
            return nextOpening(moment);
        }
        return moment.toLocalTime().isBefore(OPEN) ? moment.with(OPEN) : moment;
    }

    /**
     * 09:00 on the next weekday.
     *
     * <p>{@code with(OPEN)} rather than arithmetic on the instant: across a DST boundary the open
     * hour stays 09:00 local, which is what "business hours in the team's timezone" means. The
     * working day is also nowhere near the usual 02:00–03:00 transition, so the gap and overlap
     * resolution {@link ZonedDateTime} applies never has to be reasoned about here.
     */
    private static ZonedDateTime nextOpening(ZonedDateTime moment) {
        ZonedDateTime next = moment.plusDays(1).with(OPEN);
        while (isWeekend(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static boolean isWeekend(ZonedDateTime moment) {
        DayOfWeek day = moment.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
