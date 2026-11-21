package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.desk.Session.SUPERSEDE_MARGIN;
import static io.github.rkaushikdev.gussetline.desk.Session.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DeskSessionTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 24);
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 7, 4);
    private static final List<String> SEEN = List.of("700.00", "700.00");

    private static Ticket ticket() {
        return ticket(CouponStatus.OPEN, DEPARTURE, "220-1234567890");
    }

    private static Ticket ticket(CouponStatus status, LocalDate departure, String number) {
        return new Ticket(
                number,
                "ABC123",
                "MUELLER/ANNA",
                List.of(new Coupon(0, "LH", "400", "FRA", "JFK", departure.atTime(10, 25), "K", "KLWAP7RT", status)),
                LocalDate.of(2025, 2, 14),
                "LH",
                eur("840.00"),
                eur("1090.83"));
    }

    private static Watch watch() {
        return watch(SEEN);
    }

    private static Watch watch(List<String> seen) {
        return watch(ticket(), "JFK", DEPARTURE, seen);
    }

    /**
     * A watch from Frankfurt with a hysteresis of two that has recorded one reading a day from 20 June,
     * each fingerprinted by its amount.
     */
    private static Watch watch(Ticket ticket, String destination, LocalDate departure, List<String> seen) {
        Watch row = Watch.builder()
                .ticket(ticket)
                .origin("FRA")
                .destination(destination)
                .departure(departure)
                .threshold(eur("50.00"))
                .hysteresis(2)
                .build();
        for (int index = 0; index < seen.size(); index++) {
            String amount = seen.get(index);
            row.record(new Observation(LocalDate.of(2025, 6, 20 + index), eur(amount), amount + "-fingerprint"));
        }
        return row;
    }

    private static Context context() {
        Segment leg = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05");
        return new Context(new Itinerary(List.of(leg)), new FareComponent(List.of(leg)), LocalDate.of(2025, 2, 14))
                .withToday(TODAY)
                .withReissue(true);
    }

    /** A change permitted at the current tariff with the residual refunded. */
    private static VoluntaryChange.Builder change() {
        return VoluntaryChange.builder()
                .permitted(true)
                .basis(RepricingBasis.CURRENT)
                .residual(Residual.REFUNDABLE);
    }

    private static Function<Watch, Terms> rulesOf() {
        return rulesOf(change());
    }

    /** Every watch on the same change rule and a fifty euro change fee. */
    private static Function<Watch, Terms> rulesOf(VoluntaryChange.Builder change) {
        VoluntaryChange rule = change.build();
        List<Penalty> penalties = List.of(new Penalty(Action.CHANGE, eur("50.00")));
        return watch -> new Terms(rule, penalties, context());
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    private static long openIn(List<Recommendation> book) {
        return book.stream().filter(made -> made.state() == State.OPEN).count();
    }

    @Test
    void aNightWithNothingToLookAtDoesNothing() {
        Night night = run(List.of(), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.lookedAt()).isZero();
        assertThat(night.raised()).isEmpty();
        assertThat(night.benefit()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aDropThatHoldsBecomesARecommendation() {
        List<Recommendation> book = new ArrayList<>();
        Night night = run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(1);
        assertThat(night.raised().get(0).benefit()).isEqualTo(eur("90.00"));
        assertThat(night.benefit()).isEqualTo(eur("90.00"));
        assertThat(book).isEqualTo(night.raised());
    }

    @Test
    void theStaleOnesAreClosedBeforeAnythingElseHappens() {
        // This is the ordering that matters. The old one has to be off the book
        // before the new one is raised or the ticket has two open numbers on it.
        Recommendation old = new Recommendation(
                "220-1234567890",
                "FRA-NYC-2025-07-04-Y",
                LocalDate.of(2025, 6, 1),
                eur("840.00"),
                eur("800.00"),
                eur("50.00"),
                eur("60.00"),
                Residual.REFUNDABLE);
        List<Recommendation> book = new ArrayList<>(List.of(old));
        Night night = run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        assertThat(night.expired()).isEqualTo(1);
        assertThat(old.state()).isSameAs(State.EXPIRED);
        assertThat(night.raised()).hasSize(1);
        assertThat(openIn(book)).isEqualTo(1);
    }

    @Test
    void aBetterFareWithdrawsTheOpenOneAndRaisesANewOne() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        Recommendation first = book.get(0);

        Night night = run(List.of(watch(List.of("600.00", "600.00"))), book, rulesOf(), TODAY, "EUR");
        assertThat(first.state()).isSameAs(State.SUPERSEDED);
        assertThat(first.replacedBy()).isEqualTo("600.00-fingerprint");
        assertThat(night.withdrawn()).containsExactly(first);
        assertThat(night.raised()).hasSize(1);
        assertThat(night.raised().get(0).benefit()).isEqualTo(eur("190.00"));
    }

    @Test
    void aBarelyBetterFareIsHeldBackRatherThanChurningTheQueue() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        Recommendation first = book.get(0);

        Night night = run(List.of(watch(List.of("695.00", "695.00"))), book, rulesOf(), TODAY, "EUR");
        assertThat(night.heldBack()).isEqualTo(1);
        assertThat(night.raised()).isEmpty();
        assertThat(first.state()).isSameAs(State.OPEN);
        assertThat(book).hasSize(1);
    }

    @Test
    void theMarginIsTheLineBetweenThoseTwo() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        String better = new BigDecimal("700.00").subtract(SUPERSEDE_MARGIN).toString();
        Night night = run(List.of(watch(List.of(better, better))), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(1);
    }

    @Test
    void aWorseFareNeverReplacesAStandingRecommendation() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch(List.of("600.00", "600.00"))), book, rulesOf(), TODAY, "EUR");
        Night night = run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        assertThat(night.heldBack()).isEqualTo(1);
        assertThat(book.get(0).state()).isSameAs(State.OPEN);
    }

    @Test
    void aWatchWhoseDepartureHasGoneIsRetiredWithoutBeingPriced() {
        LocalDate gone = LocalDate.of(2025, 6, 1);
        Watch flown = watch(ticket(CouponStatus.OPEN, gone, "220-1234567890"), "JFK", gone, SEEN);
        Night night = run(List.of(flown), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.retired()).containsExactly(new RetiredSeries(flown.seriesKey(), Retired.FLOWN));
        assertThat(night.lookedAt()).isZero();
        assertThat(night.decisions()).isEmpty();
        assertThat(flown.isActive()).isFalse();
    }

    @Test
    void aTicketWithNothingOpenIsRetiredWithoutBeingPriced() {
        Watch used = watch(ticket(CouponStatus.USED, DEPARTURE, "220-1234567890"), "JFK", DEPARTURE, SEEN);
        Night night = run(List.of(used), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.retired()).containsExactly(new RetiredSeries(used.seriesKey(), Retired.TICKET_FINISHED));
        assertThat(night.lookedAt()).isZero();
    }

    @Test
    void aWatchThatIsAlreadyRetiredIsSkippedAndCounted() {
        Watch done = watch();
        done.retire(Retired.FLOWN);
        Night night = run(List.of(done), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.skippedInactive()).isEqualTo(1);
        assertThat(night.lookedAt()).isZero();
        assertThat(night.retired()).isEmpty();
    }

    @Test
    void aFareThatCannotBeChangedRetiresTheWatchAfterOneLook() {
        Watch row = watch();
        Night night = run(List.of(row), new ArrayList<>(), rulesOf(change().permitted(false)), TODAY, "EUR");
        assertThat(night.lookedAt()).isEqualTo(1);
        assertThat(night.retired()).containsExactly(new RetiredSeries(row.seriesKey(), Retired.NOT_CHANGEABLE));
        assertThat(night.raised()).isEmpty();
        assertThat(row.isActive()).isFalse();
    }

    @Test
    void aFareThatRepricesHistoricallyRetiresTheWatchToo() {
        Watch row = watch();
        Night night =
                run(List.of(row), new ArrayList<>(), rulesOf(change().basis(RepricingBasis.HISTORICAL)), TODAY, "EUR");
        assertThat(night.retired()).containsExactly(new RetiredSeries(row.seriesKey(), Retired.HISTORICAL));
    }

    @Test
    void aDropTooSmallToBeWorthItLeavesTheWatchAlone() {
        Watch row = watch(List.of("820.00", "820.00"));
        Night night = run(List.of(row), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).isEmpty();
        assertThat(night.retired()).isEmpty();
        assertThat(row.isActive()).isTrue();
        assertThat(night.whyNot()).isEqualTo(Map.of("not_worth_it", 1));
    }

    @Test
    void theVerdictsThatWentNowhereAreCountedForTheMorning() {
        Night night = run(
                List.of(watch(), watch(List.of("820.00", "820.00")), watch(List.of("830.00", "830.00"))),
                new ArrayList<>(),
                rulesOf(),
                TODAY,
                "EUR");
        assertThat(night.whyNot()).isEqualTo(Map.of("not_worth_it", 2));
        assertThat(night.actionable()).hasSize(1);
    }

    @Test
    void everyWatchGetsItsOwnDecisionRecorded() {
        Night night =
                run(List.of(watch(), watch(List.of("600.00", "600.00"))), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night.decisions()).hasSize(2);
        assertThat(night.lookedAt()).isEqualTo(2);
    }

    @Test
    void twoWatchesOnOneSeriesLeaveExactlyOneOpenRecommendation() {
        // The second is raised inside the same run, so it has to see the first.
        List<Recommendation> book = new ArrayList<>();
        Night night = run(List.of(watch(), watch(List.of("600.00", "600.00"))), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(2);
        assertThat(night.withdrawn()).hasSize(1);
        List<Recommendation> openNow =
                book.stream().filter(made -> made.state() == State.OPEN).toList();
        assertThat(openNow).hasSize(1);
        assertThat(openNow.get(0).benefit()).isEqualTo(eur("190.00"));
    }

    @Test
    void aSecondLookAtOneSeriesInsideARunCanBeHeldBackToo() {
        List<Recommendation> book = new ArrayList<>();
        Night night = run(List.of(watch(), watch(List.of("695.00", "695.00"))), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(1);
        assertThat(night.heldBack()).isEqualTo(1);
        assertThat(openIn(book)).isEqualTo(1);
    }

    @Test
    void aSecondSeriesGetsItsOwnRecommendation() {
        List<Recommendation> book = new ArrayList<>();
        Watch other = watch(ticket(CouponStatus.OPEN, DEPARTURE, "220-9999999999"), "ORD", DEPARTURE, SEEN);
        Night night = run(List.of(watch(), other), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(2);
        assertThat(night.benefit()).isEqualTo(eur("180.00"));
    }

    @Test
    void aSettledRecommendationDoesNotBlockANewOne() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        book.get(0).take(TODAY, "anna");
        Night night = run(List.of(watch(List.of("690.00", "690.00"))), book, rulesOf(), TODAY, "EUR");
        assertThat(night.raised()).hasSize(1);
        assertThat(night.heldBack()).isZero();
    }

    @Test
    void theLifetimeIsCarriedIntoWhatIsRaised() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR", 7, SUPERSEDE_MARGIN);
        assertThat(book.get(0).lifetimeDays()).isEqualTo(7);
        assertThat(book.get(0).expiresOn()).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void theMarginCanBeWidenedForADeskThatHatesChurn() {
        List<Recommendation> book = new ArrayList<>();
        run(List.of(watch()), book, rulesOf(), TODAY, "EUR");
        Night night = run(
                List.of(watch(List.of("600.00", "600.00"))),
                book,
                rulesOf(),
                TODAY,
                "EUR",
                Recommendation.DEFAULT_LIFETIME_DAYS,
                new BigDecimal("500.00"));
        assertThat(night.heldBack()).isEqualTo(1);
        assertThat(night.raised()).isEmpty();
    }

    @Test
    void aNightReadsBackInOneLine() {
        Night night = run(List.of(watch()), new ArrayList<>(), rulesOf(), TODAY, "EUR");
        assertThat(night).hasToString("2025-06-24: looked at 1, raised 1 worth 90.00 EUR, retired 0, expired 0");
    }
}
