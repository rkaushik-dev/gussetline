package io.github.rkaushikdev.gussetline.timelimit;

import static io.github.rkaushikdev.gussetline.timelimit.TimeLimit.fallingBy;
import static io.github.rkaushikdev.gussetline.timelimit.TimeLimit.limitFor;
import static io.github.rkaushikdev.gussetline.timelimit.TimeLimit.soonest;
import static io.github.rkaushikdev.gussetline.timelimit.TimeLimit.survived;
import static io.github.rkaushikdev.gussetline.timelimit.TimeLimit.urgent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimelimitTest {

    private static final LocalDate BOOKED = LocalDate.of(2025, 11, 10);
    private static final LocalDate DEPARTURE = LocalDate.of(2026, 3, 10);

    @Test
    void aHoldSetsTheDeadlineWhenItIsTheEarliest() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit.on()).isEqualTo(LocalDate.of(2025, 11, 17));
        assertThat(limit.clock()).isSameAs(Clock.HOLD);
    }

    @Test
    void theAdvancePurchaseBackstopWinsWhenItFallsFirst() {
        // A booking four months out with a seven day hold and a twenty one day backstop is
        // ticketed within a week, not three months later.
        TimeLimit limit = limitFor(LocalDate.of(2026, 2, 1), DEPARTURE, 30, 21);
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 2, 17));
        assertThat(limit.clock()).isSameAs(Clock.ADVANCE_PURCHASE);
    }

    @Test
    void theHoldWinsWhenItIsTheShorterOfTheTwo() {
        TimeLimit limit = limitFor(LocalDate.of(2026, 2, 1), DEPARTURE, 3, 21);
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 2, 4));
        assertThat(limit.clock()).isSameAs(Clock.HOLD);
    }

    @Test
    void theDepartureCatchesABookingNobodyFiledARuleFor() {
        // The clock nobody files because nobody imagines needing it.
        TimeLimit limit = limitFor(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10));
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(limit.clock()).isSameAs(Clock.DEPARTURE);
    }

    @Test
    void aHoldLongerThanTheJourneyStillEndsAtTheDeparture() {
        TimeLimit limit = limitFor(BOOKED, LocalDate.of(2025, 11, 12), 30);
        assertThat(limit.on()).isEqualTo(LocalDate.of(2025, 11, 12));
        assertThat(limit.clock()).isSameAs(Clock.DEPARTURE);
    }

    @Test
    void aBackstopAlreadyBehindUsDoesNotFallBeforeTheBooking() {
        TimeLimit limit = limitFor(
                LocalDate.of(2026, 3, 1), DEPARTURE, TicketingTerms.none().withBackstop(21));
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void instantPurchaseBeatsEverything() {
        // A booking that was never held has no deadline to miss.
        TimeLimit limit =
                limitFor(BOOKED, DEPARTURE, TicketingTerms.none().withHold(7).withInstant(true));
        assertThat(limit.on()).isEqualTo(BOOKED);
        assertThat(limit.clock()).isSameAs(Clock.IMMEDIATE);
    }

    @Test
    void aLateBookingIsHeldOnlyAsLongAsTheLateRuleSays() {
        // Booked five days out on a fare with a seven day hold: the late booking rule gives one day.
        LocalDate fiveOut = LocalDate.of(2026, 3, 5);
        TimeLimit limit =
                limitFor(fiveOut, DEPARTURE, TicketingTerms.none().withHold(7).withLateBooking(7, 1));
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 3, 6));
        assertThat(limit.clock()).isSameAs(Clock.LATE_BOOKING);
        assertThat(limit).hasToString("2026-03-06 23:00 by the late booking rule");
    }

    @Test
    void aBookingOutsideTheLateWindowNeverReadsTheLateRule() {
        TimeLimit limit =
                limitFor(BOOKED, DEPARTURE, TicketingTerms.none().withHold(7).withLateBooking(7, 0));
        assertThat(limit.on()).isEqualTo(BOOKED.plusDays(7));
        assertThat(limit.clock()).isSameAs(Clock.HOLD);
    }

    @Test
    void theLateWindowCountsTheDayItEndsOn() {
        LocalDate sevenOut = LocalDate.of(2026, 3, 3);
        TimeLimit limit = limitFor(sevenOut, DEPARTURE, TicketingTerms.none().withLateBooking(7, 0));
        assertThat(limit.on()).isEqualTo(sevenOut);
        assertThat(limit.clock()).isSameAs(Clock.LATE_BOOKING);
        LocalDate eightOut = LocalDate.of(2026, 3, 2);
        assertThat(limitFor(eightOut, DEPARTURE, TicketingTerms.none().withLateBooking(7, 0))
                        .clock())
                .isSameAs(Clock.DEPARTURE);
    }

    @Test
    void aHoldOnTheSameDayAsTheLateRuleIsNamedFirst() {
        LocalDate fiveOut = LocalDate.of(2026, 3, 5);
        TimeLimit limit =
                limitFor(fiveOut, DEPARTURE, TicketingTerms.none().withHold(1).withLateBooking(7, 1));
        assertThat(limit.clock()).isSameAs(Clock.HOLD);
    }

    @Test
    void instantPurchaseBeatsTheLateRuleToo() {
        TimeLimit limit = limitFor(
                LocalDate.of(2026, 3, 5),
                DEPARTURE,
                TicketingTerms.none().withLateBooking(7, 1).withInstant(true));
        assertThat(limit.clock()).isSameAs(Clock.IMMEDIATE);
    }

    @Test
    void aNegativeLateBookingCountIsRefused() {
        assertThatThrownBy(() -> TicketingTerms.none().withLateBooking(-1, 0))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("late booking window");
        assertThatThrownBy(() -> TicketingTerms.none().withLateBooking(7, -1))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("late booking hold");
    }

    @Test
    void theLateRuleIsAFiledClockAndReadsBackByName() {
        assertThat(Clock.LATE_BOOKING.isFiled()).isTrue();
        assertThat(Clock.of("the late booking rule")).isSameAs(Clock.LATE_BOOKING);
    }

    @Test
    void aTieGoesToTheRuleThatSaysSo() {
        // An agent asking why a booking is due today wants the rule, not the calendar fact that
        // the flight is also today.
        TimeLimit limit = limitFor(LocalDate.of(2026, 3, 3), DEPARTURE, 7);
        assertThat(limit.on()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(limit.clock()).isSameAs(Clock.HOLD);
    }

    @Test
    void aDeadlineFallsLateEnoughToLeaveAWorkingDay() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit.at().getHour()).isEqualTo(23);
    }

    @Test
    void theHourCanBeNamedByACarrierThatSaysSo() {
        assertThat(limitFor(BOOKED, DEPARTURE, TicketingTerms.none().withHold(7).at(12))
                        .at()
                        .getHour())
                .isEqualTo(12);
    }

    @Test
    void anHourNobodyHasIsRefused() {
        assertThatThrownBy(() -> limitFor(
                        BOOKED, DEPARTURE, TicketingTerms.none().withHold(7).at(25)))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("hour of the day");
    }

    @Test
    void aBookingMadeAfterTheFlightIsRefused() {
        assertThatThrownBy(() -> limitFor(LocalDate.of(2026, 4, 1), DEPARTURE))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("before it flies");
    }

    @Test
    void aNegativeHoldIsRefused() {
        assertThatThrownBy(() -> limitFor(BOOKED, DEPARTURE, -1))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("hold is not negative");
    }

    @Test
    void aNegativeBackstopIsRefused() {
        assertThatThrownBy(
                        () -> limitFor(BOOKED, DEPARTURE, TicketingTerms.none().withBackstop(-1)))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("backstop is not negative");
    }

    @Test
    void aHoldOfNothingMeansToday() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 0);
        assertThat(limit.on()).isEqualTo(BOOKED);
    }

    @Test
    void aLimitReadsBackWithTheReasonAndNotOnlyTheDate() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit).hasToString("2025-11-17 23:00 by the hold on the booking");
    }

    @Test
    void aLimitCountsTheDaysLeft() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit.daysLeft(BOOKED)).isEqualTo(7);
        assertThat(limit.daysLeft(LocalDate.of(2025, 11, 17))).isZero();
        assertThat(limit.daysLeft(LocalDate.of(2025, 11, 20))).isEqualTo(-3);
    }

    @Test
    void aLimitKnowsWhenItHasGone() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit.expiredOn(LocalDate.of(2025, 11, 17))).isFalse();
        assertThat(limit.expiredOn(LocalDate.of(2025, 11, 18))).isTrue();
    }

    @Test
    void aLimitKnowsWhenItIsToday() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(limit.isToday(LocalDate.of(2025, 11, 17))).isTrue();
        assertThat(limit.isToday(LocalDate.of(2025, 11, 16))).isFalse();
    }

    @Test
    void theDepartureClockIsTheOnlyOneNobodyFiled() {
        assertThat(Clock.DEPARTURE.isFiled()).isFalse();
        assertThat(Clock.HOLD.isFiled()).isTrue();
        assertThat(Clock.ADVANCE_PURCHASE.isFiled()).isTrue();
        assertThat(Clock.IMMEDIATE.isFiled()).isTrue();
    }

    @Test
    void somethingDueTomorrowIsUrgent() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(urgent(limit, LocalDate.of(2025, 11, 16))).isTrue();
        assertThat(urgent(limit, LocalDate.of(2025, 11, 15))).isFalse();
    }

    @Test
    void theUrgencyWindowCanBeWidened() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(urgent(limit, LocalDate.of(2025, 11, 14), 3)).isTrue();
    }

    @Test
    void aBookingWithThreeCouponsIsWorkedToTheEarliest() {
        // Reporting them all is how an agent pays for the wrong one.
        List<TimeLimit> limits = List.of(
                limitFor(BOOKED, DEPARTURE, 30), limitFor(BOOKED, DEPARTURE, 7), limitFor(BOOKED, DEPARTURE, 14));
        Optional<TimeLimit> first = soonest(limits);
        assertThat(first).isPresent();
        assertThat(first.orElseThrow().on()).isEqualTo(LocalDate.of(2025, 11, 17));
    }

    @Test
    void nothingAtAllHasNoSoonest() {
        assertThat(soonest(List.of())).isEmpty();
    }

    @Test
    void theOnesFallingSoonComeBackSoonestFirst() {
        List<TimeLimit> limits = List.of(
                limitFor(BOOKED, DEPARTURE, 14), limitFor(BOOKED, DEPARTURE, 1), limitFor(BOOKED, DEPARTURE, 2));
        List<TimeLimit> due = fallingBy(limits, LocalDate.of(2025, 11, 10), 2);
        assertThat(due)
                .extracting(TimeLimit::on)
                .containsExactly(LocalDate.of(2025, 11, 11), LocalDate.of(2025, 11, 12));
    }

    @Test
    void nothingFallingSoonIsAnEmptyList() {
        List<TimeLimit> limits = List.of(limitFor(BOOKED, DEPARTURE, 30));
        assertThat(fallingBy(limits, BOOKED)).isEmpty();
    }

    @Test
    void aBookingTicketedInTimeSurvived() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(survived(limit, Optional.of(LocalDateTime.of(2025, 11, 17, 10, 0))))
                .isTrue();
    }

    @Test
    void aBookingTicketedAMinuteLateDidNot() {
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(survived(limit, Optional.of(LocalDateTime.of(2025, 11, 17, 23, 1))))
                .isFalse();
    }

    @Test
    void aBookingWithNoTicketAtAllDidNotSurvive() {
        // Whatever the desk's own records still say, the airline has cancelled it.
        TimeLimit limit = limitFor(BOOKED, DEPARTURE, 7);
        assertThat(survived(limit, Optional.empty())).isFalse();
    }
}
