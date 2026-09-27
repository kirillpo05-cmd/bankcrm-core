package com.client360.interaction.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * IL-BR-07 in isolation. The SLA deadline is the number a breach is judged against and an
 * escalation fires on, so the arithmetic under it is worth testing without a database in the way.
 *
 * <p>The week of 7 September 2026 is used throughout: Monday the 7th through Friday the 11th, with
 * the 12th and 13th the weekend.
 */
class BusinessHoursTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static Instant warsaw(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(WARSAW).toInstant();
    }

    @Nested
    @DisplayName("plus — deriving the deadline")
    class Plus {

        @Test
        void staysInsideOneWorkingDay_IL_BR_07() {
            assertThat(BusinessHours.plus(warsaw("2026-09-07T10:00"), Duration.ofHours(4), WARSAW))
                    .isEqualTo(warsaw("2026-09-07T14:00"));
        }

        @Test
        void spillsIntoTheNextMorningRatherThanTheEvening() {
            // 16:00 + 4h: two hours left today, the rest resumes at tomorrow's opening.
            assertThat(BusinessHours.plus(warsaw("2026-09-07T16:00"), Duration.ofHours(4), WARSAW))
                    .isEqualTo(warsaw("2026-09-08T11:00"));
        }

        @Test
        void aDeadlineMayLandExactlyAtClosing() {
            assertThat(BusinessHours.plus(warsaw("2026-09-07T09:00"), Duration.ofHours(9), WARSAW))
                    .isEqualTo(warsaw("2026-09-07T18:00"));
        }

        /** A ticket raised before the office opens gets its full SLA, not a head start. */
        @Test
        void startsCountingAtOpeningWhenRaisedEarly() {
            assertThat(BusinessHours.plus(warsaw("2026-09-07T06:30"), Duration.ofHours(1), WARSAW))
                    .isEqualTo(warsaw("2026-09-07T10:00"));
        }

        @Test
        void startsTheNextMorningWhenRaisedAfterClosing() {
            assertThat(BusinessHours.plus(warsaw("2026-09-07T19:00"), Duration.ofHours(1), WARSAW))
                    .isEqualTo(warsaw("2026-09-08T10:00"));
        }

        /** The weekend is not open time, so nothing is consumed across it. */
        @Test
        void skipsTheWeekendEntirely() {
            assertThat(BusinessHours.plus(warsaw("2026-09-12T12:00"), Duration.ofHours(1), WARSAW))
                    .isEqualTo(warsaw("2026-09-14T10:00"));
        }

        @Test
        void carriesFridayEveningOverToMonday() {
            // One hour left on Friday, one more from Monday's opening.
            assertThat(BusinessHours.plus(warsaw("2026-09-11T17:00"), Duration.ofHours(2), WARSAW))
                    .isEqualTo(warsaw("2026-09-14T10:00"));
        }

        /** HIGH is 24 business hours — nine on Monday, nine on Tuesday, six on Wednesday. */
        @Test
        void twentyFourBusinessHoursIsNotOneDay_IL_BR_07() {
            assertThat(BusinessHours.plus(warsaw("2026-09-07T09:00"), Duration.ofHours(24), WARSAW))
                    .isEqualTo(warsaw("2026-09-09T15:00"));
        }

        /**
         * TR-BR-14: the team's zone, not the server's. The same instant is 09:00 in Warsaw and
         * 08:00 in London, so the London team's clock has not started yet.
         */
        @Test
        void theTeamsTimezoneDecides_TR_BR_14() {
            Instant raised = warsaw("2026-09-07T09:00");
            assertThat(BusinessHours.plus(raised, Duration.ofHours(1), WARSAW)).isEqualTo(warsaw("2026-09-07T10:00"));
            assertThat(BusinessHours.plus(raised, Duration.ofHours(1), LONDON)).isEqualTo(warsaw("2026-09-07T11:00"));
        }

        @Test
        void zeroLeavesTheInstantAlone() {
            Instant raised = warsaw("2026-09-07T10:00");
            assertThat(BusinessHours.plus(raised, Duration.ZERO, WARSAW)).isEqualTo(raised);
        }
    }

    @Nested
    @DisplayName("between — how much a pause actually cost")
    class Between {

        @Test
        void countsOnlyOpenHours() {
            assertThat(BusinessHours.between(warsaw("2026-09-07T10:00"), warsaw("2026-09-07T12:00"), WARSAW))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        void spansSeveralDays() {
            // Monday 10:00–18:00 = 8, Tuesday = 9, Wednesday 09:00–10:00 = 1.
            assertThat(BusinessHours.between(warsaw("2026-09-07T10:00"), warsaw("2026-09-09T10:00"), WARSAW))
                    .isEqualTo(Duration.ofHours(18));
        }

        /** IL-BR-08: a ticket parked over a weekend gives back nothing it was not owed. */
        @Test
        void aWeekendPauseCostsNothing_IL_BR_08() {
            assertThat(BusinessHours.between(warsaw("2026-09-12T10:00"), warsaw("2026-09-13T10:00"), WARSAW))
                    .isZero();
        }

        @Test
        void countsTheOpenHoursAroundAWeekend() {
            // Friday 17:00–18:00 = 1, then Monday 09:00–10:00 = 1.
            assertThat(BusinessHours.between(warsaw("2026-09-11T17:00"), warsaw("2026-09-14T10:00"), WARSAW))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        void ignoresTimeOutsideOpeningHours() {
            assertThat(BusinessHours.between(warsaw("2026-09-07T18:30"), warsaw("2026-09-07T23:00"), WARSAW))
                    .isZero();
        }

        @Test
        void anEndBeforeTheStartIsZeroRatherThanNegative() {
            assertThat(BusinessHours.between(warsaw("2026-09-08T10:00"), warsaw("2026-09-07T10:00"), WARSAW))
                    .isZero();
        }
    }

    /**
     * IL-EC-09: raising a five-day-old LOW ticket to CRITICAL recomputes from the original
     * {@code occurredAt}, so the deadline lands in the past and the ticket is breached on the spot.
     * That is the intended behaviour — escalation reveals lateness rather than resetting the clock.
     */
    @Test
    void raisingPriorityOnAnOldTicketProducesAPastDeadline_IL_EC_09() {
        Instant raised = warsaw("2026-09-07T09:00");
        Instant now = warsaw("2026-09-14T09:00");

        Instant asLow = BusinessHours.plus(raised, Duration.ofHours(168), WARSAW);
        Instant asCritical = BusinessHours.plus(raised, Duration.ofHours(4), WARSAW);

        assertThat(asLow).isAfter(now);
        assertThat(asCritical).isBefore(now).isEqualTo(warsaw("2026-09-07T13:00"));
    }
}
