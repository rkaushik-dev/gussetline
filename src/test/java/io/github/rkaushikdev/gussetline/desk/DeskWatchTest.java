package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static io.github.rkaushikdev.gussetline.desk.Watch.defaultMargin;
import static io.github.rkaushikdev.gussetline.desk.Watch.seriesKeyFor;
import static io.github.rkaushikdev.gussetline.desk.Watch.thresholdFor;
import static io.github.rkaushikdev.gussetline.desk.Watch.totalDropped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeskWatchTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 20);
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 7, 4);

    private static Coupon coupon(int position) {
        return new Coupon(position, "LH", "400", "FRA", "JFK", at("2025-07-04 10:25"), "K", "KLWAP7RT");
    }

    private static Ticket ticket() {
        return ticket(List.of(coupon(0)));
    }

    private static Ticket ticket(List<Coupon> coupons) {
        return new Ticket(
                "220-1234567890",
                "ABC123",
                "MUELLER/ANNA",
                coupons,
                LocalDate.of(2025, 2, 14),
                "LH",
                Money.of("840.00", "EUR"),
                Money.of("1090.83", "EUR"));
    }

    /** A watch on Frankfurt to New York with a fifty euro threshold, ready for a test to change. */
    private static Watch.Builder watch() {
        return watch("50.00");
    }

    private static Watch.Builder watch(String threshold) {
        return Watch.builder()
                .ticket(ticket())
                .origin("FRA")
                .destination("JFK")
                .departure(DEPARTURE)
                .threshold(Money.of(threshold, "EUR"));
    }

    private static Observation seen(String amount) {
        return seen(amount, TODAY);
    }

    private static Observation seen(String amount, LocalDate day) {
        return new Observation(day, Money.of(amount, "EUR"));
    }

    @Test
    void aWatchFoldsItsMarketToCities() {
        assertThat(watch().origin("LHR").destination("EWR").build().seriesKey()).startsWith("LON-NYC");
    }

    @Test
    void aThresholdIsNotNegative() {
        assertThatThrownBy(() -> watch("-1").build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aDropHasToHoldAtLeastOnce() {
        assertThatThrownBy(() -> watch().hysteresis(0).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void theSeriesKeyIsBuiltTheOneWay() {
        assertThat(watch().build().seriesKey()).isEqualTo(seriesKeyFor("FRA", "JFK", DEPARTURE, Cabin.ECONOMY));
    }

    @Test
    void aFreshWatchIsLiveAndHasSeenNothing() {
        Watch row = watch().build();
        assertThat(row.isActive()).isTrue();
        assertThat(row.checks()).isZero();
        assertThat(row).hasToString("FRA-NYC-2025-07-04-Y live after 0 checks");
    }

    @Test
    void recordingAPollCountsItWhetherOrNotItWasInteresting() {
        Watch row = watch().build();
        row.record(seen("840.00"));
        row.record(seen("840.00"));
        assertThat(row.checks()).isEqualTo(2);
        assertThat(row.history()).hasSize(2);
    }

    @Test
    void aDropIsWhatIsBelowWhatWasPaid() {
        Watch row = watch().build();
        assertThat(row.dropAgainst(seen("790.00"))).isEqualTo(Money.of("50.00", "EUR"));
    }

    @Test
    void aPriceAboveWhatWasPaidIsNotANegativeDrop() {
        Watch row = watch().build();
        assertThat(row.dropAgainst(seen("900.00"))).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aReadingInAnotherCurrencyIsRefused() {
        Watch row = watch().build();
        assertThatThrownBy(() -> row.dropAgainst(new Observation(TODAY, Money.of("790.00", "USD"))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aDropOnTheThresholdClearsIt() {
        Watch row = watch("50.00").build();
        assertThat(row.clearsThreshold(seen("790.00"))).isTrue();
        assertThat(row.clearsThreshold(seen("791.00"))).isFalse();
    }

    @Test
    void oneReadingIsNotADropWorthReporting() {
        Watch row = watch().hysteresis(2).build();
        row.record(seen("790.00"));
        assertThat(row.heldDrop()).isEmpty();
    }

    @Test
    void twoReadingsInARowAre() {
        Watch row = watch().hysteresis(2).build();
        row.record(seen("790.00", LocalDate.of(2025, 6, 18)));
        row.record(seen("780.00", TODAY));
        Optional<Observation> held = row.heldDrop();
        assertThat(held).isPresent();
        assertThat(held.get().amount()).isEqualTo(Money.of("790.00", "EUR"));
    }

    @Test
    void theDearerOfTheRunIsReportedAndNotTheCheaper() {
        // The cheapest of a run is the one most likely to have been the outlier, and quoting a number
        // the customer then cannot book is worse.
        Watch row = watch().hysteresis(2).build();
        row.record(seen("700.00", LocalDate.of(2025, 6, 18)));
        row.record(seen("780.00", TODAY));
        Optional<Observation> held = row.heldDrop();
        assertThat(held).isPresent();
        assertThat(held.get().amount()).isEqualTo(Money.of("780.00", "EUR"));
    }

    @Test
    void aDropThatWentAwayIsNotReported() {
        Watch row = watch().hysteresis(2).build();
        row.record(seen("790.00", LocalDate.of(2025, 6, 18)));
        row.record(seen("845.00", TODAY));
        assertThat(row.heldDrop()).isEmpty();
    }

    @Test
    void aDropThatCameBackIsReportedAgain() {
        Watch row = watch().hysteresis(2).build();
        row.record(seen("790.00", LocalDate.of(2025, 6, 16)));
        row.record(seen("845.00", LocalDate.of(2025, 6, 18)));
        row.record(seen("789.00", LocalDate.of(2025, 6, 19)));
        assertThat(row.heldDrop()).isEmpty();
        row.record(seen("788.00", TODAY));
        assertThat(row.heldDrop()).isPresent();
    }

    @Test
    void aLongerHysteresisNeedsALongerRun() {
        Watch row = watch().hysteresis(3).build();
        for (int day : new int[] {16, 18}) {
            row.record(seen("790.00", LocalDate.of(2025, 6, day)));
        }
        assertThat(row.heldDrop()).isEmpty();
        row.record(seen("790.00", TODAY));
        assertThat(row.heldDrop()).isPresent();
    }

    @Test
    void aRetiredWatchReportsNothingHoweverGoodTheDrop() {
        Watch row = watch().hysteresis(1).build();
        row.record(seen("400.00"));
        row.retire(Retired.TAKEN);
        assertThat(row.heldDrop()).isEmpty();
        assertThat(row).hasToString("FRA-NYC-2025-07-04-Y retired, a reissue was made");
    }

    @Test
    void aDepartureThatHasGoneShouldBeRetired() {
        Watch row = watch().build();
        assertThat(row.shouldRetire(TODAY)).isEmpty();
        assertThat(row.shouldRetire(LocalDate.of(2025, 7, 5))).containsSame(Retired.FLOWN);
    }

    @Test
    void aFinishedTicketShouldBeRetiredEvenBeforeTheDeparture() {
        Watch row = watch().ticket(ticket(List.of(coupon(0).withStatus(CouponStatus.USED))))
                .build();
        assertThat(row.shouldRetire(TODAY)).containsSame(Retired.TICKET_FINISHED);
    }

    @Test
    void theBestReadingSinceADateIsFound() {
        Watch row = watch().build();
        row.record(seen("840.00", LocalDate.of(2025, 6, 10)));
        row.record(seen("700.00", LocalDate.of(2025, 6, 12)));
        row.record(seen("790.00", LocalDate.of(2025, 6, 18)));
        Optional<Observation> best = row.bestSince(LocalDate.of(2025, 6, 15));
        assertThat(best).isPresent();
        assertThat(best.get().amount()).isEqualTo(Money.of("790.00", "EUR"));
    }

    @Test
    void aWindowWithNothingInItHasNoBest() {
        assertThat(watch().build().bestSince(TODAY)).isEmpty();
    }

    @Test
    void aQuietRunCountsRepeatsOfTheLatestAmount() {
        Watch row = watch().build();
        row.record(seen("840.00", LocalDate.of(2025, 6, 10)));
        row.record(seen("840.00", LocalDate.of(2025, 6, 12)));
        row.record(seen("840.00", LocalDate.of(2025, 6, 14)));
        assertThat(row.quietRun()).isEqualTo(2);
        row.record(seen("790.00", TODAY));
        assertThat(row.quietRun()).isZero();
    }

    @Test
    void aWatchWithOneReadingHasNoQuietRun() {
        Watch row = watch().build();
        row.record(seen("840.00"));
        assertThat(row.quietRun()).isZero();
    }

    @Test
    void aThresholdIsTheFeePlusTheMargin() {
        assertThat(thresholdFor(Money.of("50.00", "EUR"), Money.of("25.00", "EUR")))
                .isEqualTo(Money.of("75.00", "EUR"));
    }

    @Test
    void aThresholdAcrossTwoCurrenciesIsRefused() {
        assertThatThrownBy(() -> thresholdFor(Money.of("50.00", "EUR"), Money.of("25.00", "USD")))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theDefaultMarginIsTheSameEverywhere() {
        assertThat(defaultMargin("EUR")).isEqualTo(Money.of("25", "EUR"));
        assertThat(defaultMargin("USD")).isEqualTo(Money.of("25", "USD"));
    }

    @Test
    void theHeldDropsAcrossASetOfWatchesAddUp() {
        Watch one = watch().hysteresis(1).build();
        one.record(seen("790.00"));
        Watch two = watch().hysteresis(1).build();
        two.record(seen("760.00"));
        Watch quiet = watch().hysteresis(1).build();
        quiet.record(seen("841.00"));
        assertThat(totalDropped(List.of(one, two, quiet), "EUR")).isEqualTo(Money.of("130.00", "EUR"));
    }

    @Test
    void nothingDroppingTotalsToNothing() {
        assertThat(totalDropped(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void thePaidAmountComesOffTheTicket() {
        assertThat(watch().build().paid()).isEqualTo(Money.of("840.00", "EUR"));
    }

    @Test
    void aBusinessCabinWatchHasItsOwnSeries() {
        assertThat(watch().cabin(Cabin.BUSINESS).build().seriesKey()).endsWith("-C");
    }
}
