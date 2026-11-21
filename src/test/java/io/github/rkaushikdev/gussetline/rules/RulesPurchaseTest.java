package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulesPurchaseTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2025, 3, 4);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Context context(LocalDate ticketing) {
        return new Context(
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                out(),
                ticketing);
    }

    private static AdvancePurchase.Builder filing() {
        return AdvancePurchase.builder();
    }

    @Test
    void aFilingWithNothingInItDoesNotApply() {
        Outcome outcome = new AdvancePurchase().evaluate(context(LocalDate.of(2025, 3, 3)));
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.category()).isSameAs(Category.ADVANCE_RESERVATION);
    }

    @Test
    void aNegativePeriodIsRefused() {
        assertThatThrownBy(() -> filing().reservationDays(-1).build()).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> filing().ticketingDaysBeforeDeparture(-1).build())
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new InstantPurchase(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theLatestReservationCountsBackFromDeparture() {
        assertThat(filing().reservationDays(7).build().latestReservation(DEPARTURE))
                .contains(LocalDate.of(2025, 2, 25));
        assertThat(new AdvancePurchase().latestReservation(DEPARTURE)).isEmpty();
    }

    @Test
    void aBookingInsideTheWindowPasses() {
        AdvancePurchase rule = filing().reservationDays(7).build();
        assertThat(rule.evaluate(context(LocalDate.of(2025, 2, 20))).answer().value())
                .isEqualTo("passes");
    }

    @Test
    void aBookingOnTheDeadlinePasses() {
        AdvancePurchase rule = filing().reservationDays(7).build();
        assertThat(rule.evaluate(context(LocalDate.of(2025, 2, 25))).failed()).isFalse();
    }

    @Test
    void aBookingAfterTheDeadlineFailsAndSaysByHowMuch() {
        AdvancePurchase rule = filing().reservationDays(7).build();
        Outcome outcome = rule.evaluate(context(LocalDate.of(2025, 3, 1)));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("needed", 7, "had", 3));
    }

    @Test
    void theTicketingDeadlineTakesTheEarlierOfTheTwo() {
        AdvancePurchase rule = filing().ticketingDaysAfterReservation(3)
                .ticketingDaysBeforeDeparture(7)
                .build();
        // Booked six months out, so the three day hold is the binding one.
        assertThat(rule.ticketingDeadline(DEPARTURE, LocalDate.of(2024, 9, 1))).contains(LocalDate.of(2024, 9, 4));
        // Booked eight days out, so the seven days before departure binds instead.
        assertThat(rule.ticketingDeadline(DEPARTURE, LocalDate.of(2025, 2, 24))).contains(LocalDate.of(2025, 2, 25));
    }

    @Test
    void oneDeadlineFiledAloneIsTheDeadline() {
        AdvancePurchase after = filing().ticketingDaysAfterReservation(3).build();
        assertThat(after.ticketingDeadline(DEPARTURE, LocalDate.of(2025, 1, 1))).contains(LocalDate.of(2025, 1, 4));
        AdvancePurchase before = filing().ticketingDaysBeforeDeparture(7).build();
        assertThat(before.ticketingDeadline(DEPARTURE, LocalDate.of(2025, 1, 1)))
                .contains(LocalDate.of(2025, 2, 25));
    }

    @Test
    void aHoldDeadlineWithNoBookingDateIsIgnored() {
        AdvancePurchase rule = filing().ticketingDaysAfterReservation(3).build();
        assertThat(rule.ticketingDeadline(DEPARTURE, Optional.empty())).isEmpty();
    }

    @Test
    void noTicketingDeadlineFiledGivesNone() {
        assertThat(filing().reservationDays(7).build().ticketingDeadline(DEPARTURE, DEPARTURE))
                .isEmpty();
    }

    @Test
    void aLateTicketFailsOnTheBindingDeadline() {
        AdvancePurchase rule = filing().ticketingDaysAfterReservation(3)
                .ticketingDaysBeforeDeparture(7)
                .bookedOn(LocalDate.of(2025, 2, 24))
                .build();
        Outcome outcome = rule.evaluate(context(LocalDate.of(2025, 2, 27)));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("deadline", "2025-02-25");
    }

    @Test
    void theSameTicketPassesWhenItWasBookedEarlyEnough() {
        AdvancePurchase rule = filing().ticketingDaysAfterReservation(3)
                .ticketingDaysBeforeDeparture(7)
                .bookedOn(LocalDate.of(2025, 2, 20))
                .build();
        assertThat(rule.evaluate(context(LocalDate.of(2025, 2, 22))).failed()).isFalse();
    }

    @Test
    void theBookingDateFallsBackToTheTicketingDate() {
        AdvancePurchase rule = filing().reservationDays(7).build();
        assertThat(rule.evaluate(context(LocalDate.of(2025, 3, 1))).failed()).isTrue();
        AdvancePurchase held =
                filing().reservationDays(7).bookedOn(LocalDate.of(2025, 2, 1)).build();
        assertThat(held.evaluate(context(LocalDate.of(2025, 3, 1))).failed()).isFalse();
    }

    @Test
    void aPassingOutcomeCarriesTheDeadlineItUsed() {
        AdvancePurchase rule = filing().ticketingDaysBeforeDeparture(7).build();
        Outcome outcome = rule.evaluate(context(LocalDate.of(2025, 2, 20)));
        assertThat(outcome.details()).containsEntry("deadline", "2025-02-25");
    }

    @Test
    void aPassingOutcomeWithNoDeadlineSaysNone() {
        Outcome outcome = filing().reservationDays(7).build().evaluate(context(LocalDate.of(2025, 2, 20)));
        assertThat(outcome.details()).containsEntry("deadline", "none");
    }

    @Test
    void instantPurchaseWithNoHoursDoesNotApply() {
        assertThat(new InstantPurchase()
                        .evaluate(context(LocalDate.of(2025, 3, 1)))
                        .applied())
                .isFalse();
    }

    @Test
    void instantPurchasePassesWhenTheTicketIsImmediate() {
        InstantPurchase rule = new InstantPurchase(24, LocalDate.of(2025, 3, 1));
        assertThat(rule.evaluate(context(LocalDate.of(2025, 3, 1))).failed()).isFalse();
    }

    @Test
    void instantPurchaseFailsWhenTheTicketIsDaysLater() {
        InstantPurchase rule = new InstantPurchase(24, LocalDate.of(2025, 3, 1));
        Outcome outcome = rule.evaluate(context(LocalDate.of(2025, 3, 3)));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("hours", 24);
    }

    @Test
    void theProvisionsRunTogetherAndBothAnswersAreKept() {
        Trace trace = new Trace();
        List<Outcome> outcomes = Rules.evaluate(
                List.of(filing().reservationDays(7).build(), new InstantPurchase(24)),
                context(LocalDate.of(2025, 3, 1)),
                trace);
        assertThat(outcomes).hasSize(2);
        assertThat(Rules.permitted(outcomes)).isFalse();
        assertThat(trace.stageFailed(Stage.RULE)).isTrue();
    }
}
