package io.github.rkaushikdev.gussetline.involuntary;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class Eu261Test {

    private static final Money PAID = Money.of("840.00", "EUR");

    private static Itinerary frankfurtNewYork() {
        return new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary newYorkFrankfurt(String operator) {
        return new Itinerary(List.of(segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")
                .withOperatingCarrier(operator)));
    }

    private static Itinerary frankfurtLondon() {
        return new Itinerary(List.of(segment("LH", "900", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40")));
    }

    private static Itinerary frankfurtIstanbul() {
        return new Itinerary(List.of(segment("TK", "1590", "FRA", "IST", "2025-03-04 12:00", "2025-03-04 16:15")));
    }

    private static Disruption cancelled(int notice, int hoursLate) {
        return Disruption.of(Cause.CANCELLED).withNotice(notice).withLateness(Duration.ofHours(hoursLate));
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void theBandIsReadOffTheGreatCircleInKilometres() {
        assertThat(Eu261.kilometres(frankfurtLondon())).isBetween(640, 670);
        assertThat(Eu261.kilometres(frankfurtIstanbul())).isBetween(1500, 3500);
        assertThat(Eu261.kilometres(frankfurtNewYork())).isGreaterThan(3500);
    }

    @Test
    void aLongHaulCancellationOnTheDayOwesTheFullAmount() {
        Compensation owed = Eu261.assess(frankfurtNewYork(), cancelled(0, 5));
        assertThat(owed.amount()).isEqualTo(eur("600"));
        assertThat(owed.owed()).isTrue();
        assertThat(owed.reasons().get(0)).startsWith("long haul");
    }

    @Test
    void eachBandHasItsOwnAmount() {
        assertThat(Eu261.assess(frankfurtLondon(), cancelled(0, 5)).amount()).isEqualTo(eur("250"));
        assertThat(Eu261.assess(frankfurtIstanbul(), cancelled(0, 5)).amount()).isEqualTo(eur("400"));
    }

    @Test
    void aReroutingThatArrivesWithinTheBandsHoursIsHalved() {
        // Three hours late: inside the four a long haul allows, outside the two a short haul does.
        assertThat(Eu261.assess(frankfurtNewYork(), cancelled(0, 3)).amount()).isEqualTo(eur("300"));
        assertThat(Eu261.assess(frankfurtIstanbul(), cancelled(0, 3)).amount()).isEqualTo(eur("200"));
        assertThat(Eu261.assess(frankfurtLondon(), cancelled(0, 3)).amount()).isEqualTo(eur("250"));
    }

    @Test
    void aFortnightsNoticeOwesNothingWhateverTheRerouting() {
        Compensation owed = Eu261.assess(frankfurtNewYork(), cancelled(14, 12));
        assertThat(owed.owed()).isFalse();
        assertThat(owed.reasons()).containsExactly("notified 14 days ahead");
    }

    @Test
    void aWeeksNoticeOwesNothingWhenReroutedWithinFourHours() {
        assertThat(Eu261.assess(frankfurtNewYork(), cancelled(10, 4)).owed()).isFalse();
        assertThat(Eu261.assess(frankfurtNewYork(), cancelled(10, 5)).amount()).isEqualTo(eur("600"));
    }

    @Test
    void shortNoticeOwesNothingOnlyWhenReroutedWithinTwoHours() {
        assertThat(Eu261.assess(frankfurtLondon(), cancelled(3, 2)).owed()).isFalse();
        assertThat(Eu261.assess(frankfurtLondon(), cancelled(3, 3)).amount()).isEqualTo(eur("250"));
    }

    @Test
    void aScheduleChangeIsReadAsACancellation() {
        Disruption changed = Disruption.of(Cause.SCHEDULE_CHANGE).withNotice(2).withLateness(Duration.ofHours(6));
        assertThat(Eu261.assess(frankfurtNewYork(), changed).amount()).isEqualTo(eur("600"));
        assertThat(Eu261.assess(frankfurtNewYork(), changed.withNotice(20)).owed())
                .isFalse();
    }

    @Test
    void anArrivalIntoEuropeIsCoveredOnlyOnACarrierLicensedThere() {
        assertThat(Eu261.covers(newYorkFrankfurt("LH"))).isTrue();
        assertThat(Eu261.covers(newYorkFrankfurt("UA"))).isFalse();
        Compensation united = Eu261.assess(newYorkFrankfurt("UA"), cancelled(0, 8));
        assertThat(united.owed()).isFalse();
        assertThat(united.reasons().get(0)).startsWith("outside the regulation");
        assertThat(Eu261.assess(newYorkFrankfurt("LH"), cancelled(0, 8)).amount())
                .isEqualTo(eur("600"));
    }

    @Test
    void aDepartureFromEuropeIsCoveredWhoeverFliesIt() {
        assertThat(Eu261.covers(frankfurtIstanbul())).isTrue();
    }

    @Test
    void extraordinaryCircumstancesExcuseACancellationButNeverADeniedBoarding() {
        Compensation storm = Eu261.assess(frankfurtNewYork(), cancelled(0, 8).withExtraordinary(true));
        assertThat(storm.reasons()).containsExactly("extraordinary circumstances");
        Disruption bumped = Disruption.of(Cause.DENIED_BOARDING)
                .withLateness(Duration.ofHours(8))
                .withExtraordinary(true);
        assertThat(Eu261.assess(frankfurtNewYork(), bumped).amount()).isEqualTo(eur("600"));
    }

    @Test
    void aMissedConnectionUnderThreeHoursLateOwesNothing() {
        Disruption missed = Disruption.of(Cause.MISCONNECTED).withLateness(Duration.ofMinutes(170));
        assertThat(Eu261.assess(frankfurtNewYork(), missed).reasons())
                .containsExactly("arrived less than three hours late");
    }

    @Test
    void aLongHaulMissedConnectionBetweenThreeAndFourHoursLateIsHalved() {
        Disruption missed = Disruption.of(Cause.MISCONNECTED).withLateness(Duration.ofMinutes(210));
        assertThat(Eu261.assess(frankfurtNewYork(), missed).amount()).isEqualTo(eur("300"));
        assertThat(Eu261.assess(frankfurtNewYork(), missed.withLateness(Duration.ofHours(5)))
                        .amount())
                .isEqualTo(eur("600"));
        assertThat(Eu261.assess(frankfurtIstanbul(), missed).amount()).isEqualTo(eur("400"));
    }

    @Test
    void aDowngradeIsNotCompensated() {
        Compensation owed = Eu261.assess(frankfurtNewYork(), Disruption.of(Cause.DOWNGRADED));
        assertThat(owed.owed()).isFalse();
        assertThat(owed.reasons().get(0)).contains("refunded in part");
    }

    @Test
    void anInvoluntaryReissueOnAKnownJourneyCarriesItsCompensation() {
        Involuntary made = Involuntary.assess(PAID, cancelled(0, 5).withJourney(frankfurtNewYork()));
        assertThat(made.compensation()).map(Compensation::amount).contains(eur("600"));
        assertThat(made.notes().get(made.notes().size() - 1)).startsWith("compensation of 600 EUR");
        assertThat(made.net()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void withoutAJourneyNoCompensationIsAssessedAtAll() {
        Involuntary made = Involuntary.assess(PAID, cancelled(0, 5));
        assertThat(made.compensation()).isEmpty();
        assertThat(made.notes()).hasSize(2);
    }

    @Test
    void aCompensationNothingIsOwedOnIsNotNoted() {
        Involuntary made = Involuntary.assess(PAID, cancelled(20, 5).withJourney(frankfurtNewYork()));
        assertThat(made.compensation()).map(Compensation::owed).contains(false);
        assertThat(made.notes()).hasSize(2);
    }

    private static Disruption downgraded(Itinerary journey) {
        return Disruption.of(Cause.DOWNGRADED)
                .withMove(Cabin.BUSINESS, Cabin.ECONOMY)
                .withJourney(journey);
    }

    @Test
    void aCoveredDowngradeRefundsTheRegulationsShareOfTheTicket() {
        assertThat(Eu261.downgradeRefund(frankfurtNewYork(), PAID)).contains(eur("630.00"));
        assertThat(Eu261.downgradeRefund(frankfurtIstanbul(), PAID)).contains(eur("420.00"));
        // Thirty percent of 333.33 is 99.999, and a refund never promises the fraction.
        assertThat(Eu261.downgradeRefund(frankfurtLondon(), eur("333.33"))).contains(eur("99.99"));
        assertThat(Eu261.downgradeRefund(newYorkFrankfurt("UA"), PAID)).isEmpty();
    }

    @Test
    void aCoveredDowngradeNeedsNothingPriced() {
        Involuntary made = Involuntary.assess(PAID, downgraded(frankfurtNewYork()));
        assertThat(made.downgradeRefund()).isEqualTo(eur("630.00"));
        assertThat(made.refund()).isEqualTo(eur("630.00"));
        assertThat(made.notes()).contains("downgraded from C to Y");
    }

    @Test
    void aPricedDifferenceLargerThanTheShareIsWhatComesBack() {
        Involuntary made =
                Involuntary.assess(PAID, downgraded(frankfurtNewYork()).withCabinDifference(eur("1200.00")));
        assertThat(made.downgradeRefund()).isEqualTo(eur("1200.00"));
        Involuntary smaller =
                Involuntary.assess(PAID, downgraded(frankfurtNewYork()).withCabinDifference(eur("100.00")));
        assertThat(smaller.downgradeRefund()).isEqualTo(eur("630.00"));
    }

    @Test
    void anUncoveredDowngradeStillNeedsTheDifferencePriced() {
        assertThatThrownBy(() -> Involuntary.assess(PAID, downgraded(newYorkFrankfurt("UA"))))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("difference between the two cabins");
        Involuntary priced =
                Involuntary.assess(PAID, downgraded(newYorkFrankfurt("UA")).withCabinDifference(eur("250.00")));
        assertThat(priced.downgradeRefund()).isEqualTo(eur("250.00"));
    }

    @Test
    void aScheduleChangeIntoALowerCabinOwesNoShareEitherOnACoveredJourney() {
        Disruption lower = Disruption.of(Cause.SCHEDULE_CHANGE)
                .withMove(Cabin.BUSINESS, Cabin.ECONOMY)
                .withJourney(frankfurtNewYork())
                .withNotice(30);
        Involuntary made = Involuntary.assess(PAID, lower);
        assertThat(made.downgradeRefund()).isEqualTo(Money.zero("EUR"));
        assertThat(made.notes()).anyMatch(note -> note.contains("without a downgrade owed"));
    }

    @Test
    void negativeLatenessOrNoticeIsRefused() {
        assertThatThrownBy(() -> Disruption.of(Cause.CANCELLED).withLateness(Duration.ofMinutes(-1)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Disruption.of(Cause.CANCELLED).withNotice(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void negativeCompensationIsRefusedAndAnAmountReadsBack() {
        assertThatThrownBy(() -> new Compensation(eur("-1"), 100, List.of())).isInstanceOf(ValidationError.class);
        assertThat(new Compensation(eur("600"), 6197, List.of("long haul, 6197 km")))
                .hasToString("600 EUR over 6197 km: long haul, 6197 km");
    }
}
