package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.desk.Worklist.DEPARTURE_WEIGHT;
import static io.github.rkaushikdev.gussetline.desk.Worklist.EXPIRY_WEIGHT;
import static io.github.rkaushikdev.gussetline.desk.Worklist.build;
import static io.github.rkaushikdev.gussetline.desk.Worklist.hasMoved;
import static io.github.rkaushikdev.gussetline.desk.Worklist.render;
import static io.github.rkaushikdev.gussetline.desk.Worklist.score;
import static io.github.rkaushikdev.gussetline.desk.Worklist.urgency;
import static io.github.rkaushikdev.gussetline.desk.Worklist.urgent;
import static io.github.rkaushikdev.gussetline.desk.Worklist.worthOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Residual;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeskQueueTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 10, 1);
    private static final LocalDate FAR = LocalDate.of(2026, 3, 1);
    private static final LocalDate SOON = LocalDate.of(2025, 10, 3);
    private static final String BENEFIT = "90.00";
    private static final String TICKET = "220-1111111111";
    private static final String SERIES = "FRA-NYC-2026-03-01-Y";

    private static Recommendation made() {
        return made(BENEFIT, TICKET, SERIES, TODAY);
    }

    /** An open recommendation lasting three days, raised on the reading fingerprinted all {@code a}. */
    private static Recommendation made(String benefit, String ticket, String series, LocalDate raised) {
        return Recommendation.builder()
                .ticketNumber(ticket)
                .seriesKey(series)
                .raisedOn(raised)
                .paid(eur("840.00"))
                .newFare(eur("700.00"))
                .penalty(eur("50.00"))
                .benefit(eur(benefit))
                .residual(Residual.REFUNDABLE)
                .fingerprint("a".repeat(32))
                .lifetimeDays(3)
                .build();
    }

    private static Observation seen(String amount, LocalDate on) {
        return seen(amount, on, "a".repeat(32));
    }

    private static Observation seen(String amount, LocalDate on, String fingerprint) {
        return new Observation(on, eur(amount), fingerprint);
    }

    private static List<String> tickets(List<Place> places) {
        return places.stream()
                .map(place -> place.recommendation().ticketNumber())
                .toList();
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void aFarOffRecommendationWithDaysLeftHasNoHurryOnIt() {
        assertThat(urgency(3, 90)).isEqualByComparingTo("1");
    }

    @Test
    void aRecommendationOnItsLastDayCarriesTheExpiryWeight() {
        assertThat(urgency(0, 90)).isEqualByComparingTo(EXPIRY_WEIGHT);
    }

    @Test
    void aDepartureTodayCarriesTheDepartureWeight() {
        assertThat(urgency(3, 0)).isEqualByComparingTo(DEPARTURE_WEIGHT);
    }

    @Test
    void bothClocksRunningDownMultiplyRatherThanAdd() {
        // A recommendation nearly expired on a departure next week is a different
        // problem from being nearly out of time on one of them.
        assertThat(urgency(0, 0)).isEqualByComparingTo(EXPIRY_WEIGHT.multiply(DEPARTURE_WEIGHT));
    }

    @Test
    void theHurryClimbsAsTheClockRunsDown() {
        assertThat(urgency(2, 90)).isLessThan(urgency(1, 90));
        assertThat(urgency(1, 90)).isLessThan(urgency(0, 90));
    }

    @Test
    void aScoreIsTheBenefitWhenNothingIsUrgent() {
        assertThat(score(made(), TODAY, FAR)).isEqualByComparingTo("90.00");
    }

    @Test
    void aScoreClimbsAsTheRecommendationNearsExpiry() {
        Recommendation older = made(BENEFIT, TICKET, SERIES, LocalDate.of(2025, 9, 29));
        assertThat(score(older, TODAY, FAR)).isGreaterThan(score(made(), TODAY, FAR));
    }

    @Test
    void aSmallerBenefitAboutToExpireBeatsALargerOneThatCanWait() {
        // The whole reason the queue is not sorted by value.
        Recommendation urgentOne = made("90.00", "220-2222222222", SERIES, LocalDate.of(2025, 9, 28));
        Map<String, LocalDate> departures = Map.of(urgentOne.seriesKey(), SOON, "FRA-LON-2026-03-01-Y", FAR);
        Recommendation fatOne = made("200.00", TICKET, "FRA-LON-2026-03-01-Y", TODAY);
        List<Place> order = build(List.of(fatOne, urgentOne), TODAY, departures);
        assertThat(order.get(0).recommendation()).isSameAs(urgentOne);
    }

    @Test
    void aFareSeenDearerSinceIsDemotedAndNotDropped() {
        List<Observation> history =
                List.of(seen("700.00", TODAY), seen("760.00", LocalDate.of(2025, 10, 2), "b".repeat(32)));
        assertThat(hasMoved(TODAY, "a".repeat(32), history)).isTrue();
        BigDecimal quiet = score(made(), TODAY, FAR);
        BigDecimal moved = score(made(), TODAY, FAR, true);
        assertThat(moved).isLessThan(quiet);
        assertThat(moved).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aFareSeenCheaperSinceHasNotMovedAgainstUs() {
        List<Observation> history =
                List.of(seen("700.00", TODAY), seen("650.00", LocalDate.of(2025, 10, 2), "b".repeat(32)));
        assertThat(hasMoved(TODAY, "a".repeat(32), history)).isFalse();
    }

    @Test
    void aReadingOlderThanTheRecommendationSaysNothing() {
        List<Observation> history =
                List.of(seen("760.00", LocalDate.of(2025, 9, 20), "b".repeat(32)), seen("700.00", TODAY));
        assertThat(hasMoved(TODAY, "a".repeat(32), history)).isFalse();
    }

    @Test
    void aRecommendationWhoseOwnReadingHasRolledOffSaysNothing() {
        // The desk keeps a handful of readings and an old recommendation falls
        // off the end of them.
        List<Observation> history = List.of(seen("760.00", LocalDate.of(2025, 10, 2), "z".repeat(32)));
        assertThat(hasMoved(TODAY, "a".repeat(32), history)).isFalse();
    }

    @Test
    void anEmptyQueueIsEmpty() {
        assertThat(build(List.of(), TODAY, Map.of())).isEmpty();
        assertThat(render(List.of())).isEqualTo("nothing open");
    }

    @Test
    void aSettledRecommendationIsNotInTheQueue() {
        Recommendation row = made();
        row.take(TODAY, "anna");
        assertThat(build(List.of(row), TODAY, Map.of(row.seriesKey(), FAR))).isEmpty();
    }

    @Test
    void aRecommendationWithNoDepartureKnownIsLeftOut() {
        // Nothing can be said about how urgent it is, and guessing puts it in
        // the wrong place either way.
        assertThat(build(List.of(made()), TODAY, Map.of())).isEmpty();
    }

    @Test
    void theQueueIsOrderedByScoreAndNotByValue() {
        Map<String, LocalDate> departures = Map.of("a", SOON, "b", FAR);
        Recommendation small = made("90.00", "220-2222222222", "a", LocalDate.of(2025, 9, 29));
        Recommendation big = made("150.00", "220-1111111111", "b", TODAY);
        List<Place> order = build(List.of(big, small), TODAY, departures);
        assertThat(tickets(order)).containsExactly("220-2222222222", "220-1111111111");
    }

    @Test
    void equalScoresBreakOnTheTicketNumberAndNeverOnAnythingThatMoves() {
        // An agent halfway down a list that reorders under them loses their place.
        Map<String, LocalDate> departures = Map.of("a", FAR, "b", FAR);
        Recommendation first = made(BENEFIT, "220-1111111111", "a", TODAY);
        Recommendation second = made(BENEFIT, "220-2222222222", "b", TODAY);
        List<Place> once = build(List.of(second, first), TODAY, departures);
        List<Place> again = build(List.of(first, second), TODAY, departures);
        assertThat(tickets(once)).isEqualTo(tickets(again));
    }

    @Test
    void aPlaceKnowsHowLongItHasAndHowFarOutItIs() {
        Place place = build(List.of(made()), TODAY, Map.of(SERIES, FAR)).get(0);
        assertThat(place.daysLeft()).isEqualTo(3);
        assertThat(place.daysOut()).isEqualTo(151);
        assertThat(place.fareMoved()).isFalse();
    }

    @Test
    void theUrgentOnesAreTheOnesThatGoToday() {
        Map<String, LocalDate> departures = Map.of("a", FAR, "b", SOON);
        Recommendation lasting = made(BENEFIT, "220-1111111111", "a", TODAY);
        Recommendation leaving = made(BENEFIT, "220-2222222222", "b", TODAY);
        List<Place> places = build(List.of(lasting, leaving), TODAY, departures);
        assertThat(tickets(urgent(places))).containsExactly("220-2222222222");
    }

    @Test
    void aRecommendationExpiringTodayIsUrgentHoweverFarTheDeparture() {
        Recommendation row = made(BENEFIT, TICKET, SERIES, LocalDate.of(2025, 9, 28));
        Place place = build(List.of(row), TODAY, Map.of(row.seriesKey(), FAR)).get(0);
        assertThat(place.isUrgent()).isTrue();
        assertThat(place.why()).contains("expires today");
    }

    @Test
    void aPlaceSaysWhyItIsWhereItIs() {
        Recommendation row = made(BENEFIT, TICKET, SERIES, LocalDate.of(2025, 9, 30));
        Place place = build(List.of(row), TODAY, Map.of(row.seriesKey(), SOON)).get(0);
        assertThat(place.why()).contains("days left");
        assertThat(place.why()).contains("departs within three days");
    }

    @Test
    void aPlaceWithNoHurrySaysSo() {
        assertThat(build(List.of(made()), TODAY, Map.of(SERIES, FAR)).get(0).why())
                .isEqualTo("no hurry");
    }

    @Test
    void aPlaceThatLostItsFareSaysThatToo() {
        List<Observation> history =
                List.of(seen("700.00", TODAY), seen("760.00", LocalDate.of(2025, 10, 2), "b".repeat(32)));
        Place place = build(List.of(made()), TODAY, Map.of(SERIES, FAR), Map.of(SERIES, history))
                .get(0);
        assertThat(place.fareMoved()).isTrue();
        assertThat(place.why()).contains("dearer since");
    }

    @Test
    void aPlaceReadsBackInOneLine() {
        Place place = build(List.of(made()), TODAY, Map.of(SERIES, FAR)).get(0);
        assertThat(place).hasToString("220-1111111111 90.00 EUR score 90.00 (no hurry)");
    }

    @Test
    void theQueueIsWorthWhatItsRecommendationsAreWorth() {
        Map<String, LocalDate> departures = Map.of("a", FAR, "b", FAR);
        List<Place> places = build(
                List.of(made("90.00", TICKET, "a", TODAY), made("60.00", "220-2222222222", "b", TODAY)),
                TODAY,
                departures);
        assertThat(worthOf(places, "EUR")).isEqualTo(eur("150.00"));
    }

    @Test
    void anEmptyQueueIsWorthNothing() {
        assertThat(worthOf(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void theRenderedQueueSaysHowManyAndHowManyForToday() {
        Map<String, LocalDate> departures = Map.of("a", SOON, "b", FAR);
        List<Place> places = build(
                List.of(made(BENEFIT, TICKET, "a", TODAY), made(BENEFIT, "220-2222222222", "b", TODAY)),
                TODAY,
                departures);
        String out = render(places);
        assertThat(out).contains("2 open, 1 for today");
    }

    @Test
    void theRenderedQueueStopsAtTheLimitAndSaysSo() {
        Map<String, LocalDate> departures = IntStream.range(0, 5)
                .mapToObj(String::valueOf)
                .collect(Collectors.toMap(Function.identity(), index -> FAR));
        List<Place> places = build(
                IntStream.range(0, 5)
                        .mapToObj(index -> made(BENEFIT, "220-" + index, String.valueOf(index), TODAY))
                        .toList(),
                TODAY,
                departures);
        assertThat(render(places, 2)).contains("and 3 more");
    }

    @Test
    void aPlaceSaysWhatTheNewFareIncludes() {
        // An agent who finds out about a smaller allowance halfway through the
        // call has already quoted the saving.
        Recommendation row = made().withBaggage("1PC");
        Place place = build(List.of(row), TODAY, Map.of(row.seriesKey(), FAR)).get(0);
        assertThat(place.baggage()).isEqualTo("1PC");
        assertThat(place.why()).contains("includes 1PC");
    }

    @Test
    void aPlaceSaysNothingWhenTheSupplierSaidNothing() {
        Place place = build(List.of(made()), TODAY, Map.of(SERIES, FAR)).get(0);
        assertThat(place.baggage()).isEmpty();
        assertThat(place.why()).doesNotContain("includes");
    }
}
