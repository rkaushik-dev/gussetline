package io.github.rkaushikdev.gussetline.minimums;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.transatlanticMiles;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleCircleTrip;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleRoundTrip;
import static io.github.rkaushikdev.gussetline.construction.Construction.priceComponent;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.applyHip;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.applyMinimum;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.backhaulCheck;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.circleTripMinimum;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.highestOf;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.hipCheck;
import static io.github.rkaushikdev.gussetline.minimums.Minimums.openJawMinimum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinimumsTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();
    private final MileageTable transatlanticMiles = transatlanticMiles();

    private static FareComponent componentViaChicago() {
        return new FareComponent(List.of(
                segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
                segment("UA", "200", "ORD", "LAX", "2025-03-06 08:00", "2025-03-06 10:30")));
    }

    private static FareComponent straightThrough() {
        return new FareComponent(List.of(segment("LH", "456", "FRA", "LAX", "2025-03-04 10:00", "2025-03-04 13:20")));
    }

    /** Frankfurt to Chicago the long way, out past Los Angeles first. */
    private static FareComponent backtracking() {
        return new FareComponent(List.of(
                segment("LH", "456", "FRA", "LAX", "2025-03-04 10:00", "2025-03-04 13:20"),
                segment("UA", "300", "LAX", "ORD", "2025-03-06 08:00", "2025-03-06 14:00")));
    }

    private static FareComponent leg(
            String carrier, String number, String origin, String destination, String departure, String arrival) {
        return new FareComponent(List.of(segment(carrier, number, origin, destination, departure, arrival)));
    }

    private static TariffStore tariffOf(Fare... fares) {
        TariffStore store = new TariffStore();
        store.addMany(List.of(fares));
        return store;
    }

    private static MinimumResult result(String added, String reason) {
        return new MinimumResult(new BigDecimal(added), reason);
    }

    private FareComponent priced(FareComponent component, Fare fare) {
        return priceComponent(component, fare, flatConverter, TICKETING);
    }

    private FareComponent toLosAngeles(FareComponent component) {
        return priced(component, fare("FRA", "LAX", "500.00"));
    }

    /** Frankfurt to New York to Los Angeles and home, three components. */
    private PricingUnit circle() {
        return assembleCircleTrip(List.of(
                priced(
                        leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        fare("FRA", "JFK", "300.00")),
                priced(
                        leg("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
                        fare("JFK", "LAX", "150.00")),
                priced(
                        leg("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20"),
                        fare("LAX", "FRA", "350.00"))));
    }

    /** Out to New York, home to Munich instead of Frankfurt. */
    private PricingUnit openJaw() {
        return new PricingUnit(
                List.of(
                        priced(
                                leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                                fare("FRA", "JFK", "300.00")),
                        priced(
                                leg("LH", "417", "JFK", "MUC", "2025-03-18 18:30", "2025-03-19 08:05"),
                                fare("JFK", "MUC", "320.00"))),
                JourneyType.OPEN_JAW);
    }

    @Test
    void aResultWithNothingAddedReadsAsItsReason() {
        assertThat(result("0", "no higher intermediate point")).hasToString("no higher intermediate point");
        assertThat(result("0", "x").applied()).isFalse();
    }

    @Test
    void aResultThatAppliedNamesThePair() {
        MinimumResult result =
                new MinimumResult(new BigDecimal("40.00"), "higher intermediate point", new CityPair("FRA", "CHI"));
        assertThat(result.applied()).isTrue();
        assertThat(result).hasToString("higher intermediate point on FRA-CHI, up 40.00 NUC");
    }

    @Test
    void aMinimumCheckRunsAfterTheComponentIsPriced() {
        assertThatThrownBy(() ->
                        hipCheck(componentViaChicago(), new TariffStore(), flatConverter, TICKETING, List.of("ORD")))
                .isInstanceOf(NoFareFound.class);
    }

    @Test
    void aComponentWithNoStopoverHasNothingToCompare() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"));
        FareComponent priced = toLosAngeles(straightThrough());
        Trace trace = new Trace();
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of(), trace);
        assertThat(result.applied()).isFalse();
        assertThat(trace.forStage(Stage.MINIMUM).get(0).message()).contains("no stopover to compare");
    }

    @Test
    void aConnectionIsNotAStopoverAndDoesNotTriggerTheCheck() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "900.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        // Chicago is flown through but is not named as a stopover.
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of());
        assertThat(result.applied()).isFalse();
    }

    @Test
    void aDearerIntermediateFareRaisesTheComponent() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "620.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        Trace trace = new Trace();
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"), trace);
        assertThat(result.applied()).isTrue();
        assertThat(result.added()).isEqualByComparingTo("120.00");
        assertThat(result.pair()).contains(new CityPair("FRA", "CHI"));
        assertThat(trace.forStage(Stage.MINIMUM).get(0).details()).containsEntry("pair", "FRA-CHI");
    }

    @Test
    void aCheaperIntermediateFareChangesNothing() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "410.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"));
        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("no higher intermediate point");
    }

    @Test
    void theCheckLooksAtTheFarHalfAsWell() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("ORD", "LAX", "700.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"));
        assertThat(result.applied()).isTrue();
        assertThat(result.pair()).contains(new CityPair("CHI", "LAX"));
    }

    @Test
    void theHighestOfSeveralCandidatesWins() {
        TariffStore store =
                tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "620.00"), fare("ORD", "LAX", "740.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"));
        assertThat(result.added()).isEqualByComparingTo("240.00");
        assertThat(result.pair()).contains(new CityPair("CHI", "LAX"));
    }

    @Test
    void anIntermediateMarketWithNothingPublishedDoesNotConstrain() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        assertThat(hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"))
                        .applied())
                .isFalse();
    }

    @Test
    void twoStopoversAreComparedAgainstEachOtherAsWell() {
        FareComponent elements = new FareComponent(List.of(
                segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
                segment("UA", "200", "ORD", "DEN", "2025-03-06 08:00", "2025-03-06 09:30"),
                segment("UA", "300", "DEN", "LAX", "2025-03-09 08:00", "2025-03-09 09:40")));
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("ORD", "DEN", "900.00"));
        FareComponent priced = toLosAngeles(elements);
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD", "DEN"));
        assertThat(result.applied()).isTrue();
        assertThat(result.pair()).contains(new CityPair("CHI", "DEN"));
    }

    @Test
    void aStopoverNamedThatIsNotOnTheComponentIsIgnored() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "620.00"));
        FareComponent priced = toLosAngeles(straightThrough());
        assertThat(hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"))
                        .applied())
                .isFalse();
    }

    @Test
    void applyingAFindingAddsASurchargeAndKeepsTheFareBasis() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"), fare("FRA", "ORD", "620.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        MinimumResult result = hipCheck(priced, store, flatConverter, TICKETING, List.of("ORD"));
        FareComponent raised = applyHip(priced, result);
        assertThat(raised.totalNuc()).isEqualByComparingTo("620.00");
        assertThat(raised.notes()).containsExactly("HIP");
        assertThat(raised.fare())
                .hasValueSatisfying(fare -> assertThat(fare.fareBasis()).isEqualTo("KLWAP7RT"));
    }

    @Test
    void applyingNothingLeavesTheComponentAlone() {
        FareComponent priced = toLosAngeles(straightThrough());
        assertThat(applyHip(priced, result("0", "no"))).isSameAs(priced);
    }

    @Test
    void aComponentWithNothingBeyondItsDestinationHasNoBackhaul() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "500.00"));
        FareComponent priced = toLosAngeles(componentViaChicago());
        Trace trace = new Trace();
        MinimumResult result = backhaulCheck(priced, store, flatConverter, TICKETING, transatlanticMiles, trace);
        assertThat(result.applied()).isFalse();
        assertThat(trace.forStage(Stage.MINIMUM).get(0).message()).contains("nothing is beyond the destination");
    }

    @Test
    void aBacktrackToADearerPointIsCharged() {
        TariffStore store = tariffOf(fare("FRA", "ORD", "500.00"), fare("FRA", "LAX", "680.00"));
        FareComponent priced = priced(backtracking(), fare("FRA", "ORD", "500.00"));
        MinimumResult result = backhaulCheck(priced, store, flatConverter, TICKETING, transatlanticMiles);
        assertThat(result.applied()).isTrue();
        assertThat(result.added()).isEqualByComparingTo("180.00");
        assertThat(result.pair()).contains(new CityPair("FRA", "LAX"));
    }

    @Test
    void aBacktrackToACheaperPointCostsNothing() {
        TariffStore store = tariffOf(fare("FRA", "ORD", "500.00"), fare("FRA", "LAX", "420.00"));
        FareComponent priced = priced(backtracking(), fare("FRA", "ORD", "500.00"));
        Trace trace = new Trace();
        MinimumResult result = backhaulCheck(priced, store, flatConverter, TICKETING, transatlanticMiles, trace);
        assertThat(result.applied()).isFalse();
        assertThat(trace.forStage(Stage.MINIMUM).get(0).message()).contains("backtrack found but no dearer fare");
    }

    @Test
    void theBackhaulCheckLooksAtConnectionsToo() {
        // Los Angeles is flown through on a two hour connection and never named
        // as a stopover, and the check still fires, which is what separates it
        // from the higher intermediate point check.
        TariffStore store = tariffOf(fare("FRA", "ORD", "500.00"), fare("FRA", "LAX", "680.00"));
        FareComponent priced = priced(backtracking(), fare("FRA", "ORD", "500.00"));
        MinimumResult hip = hipCheck(priced, store, flatConverter, TICKETING, List.of());
        MinimumResult backhaul = backhaulCheck(priced, store, flatConverter, TICKETING, transatlanticMiles);
        assertThat(hip.applied()).isFalse();
        assertThat(backhaul.applied()).isTrue();
    }

    @Test
    void theBackhaulCheckRunsAfterPricing() {
        assertThatThrownBy(() ->
                        backhaulCheck(backtracking(), new TariffStore(), flatConverter, TICKETING, transatlanticMiles))
                .isInstanceOf(NoFareFound.class);
    }

    @Test
    void theTwoChecksDoNotStackAndTheDearerOneWins() {
        MinimumResult hip =
                new MinimumResult(new BigDecimal("120.00"), "higher intermediate point", new CityPair("FRA", "CHI"));
        MinimumResult backhaul = new MinimumResult(new BigDecimal("180.00"), "backhaul", new CityPair("FRA", "LAX"));
        assertThat(highestOf(hip, backhaul)).isSameAs(backhaul);
        assertThat(highestOf(backhaul, hip)).isSameAs(backhaul);
    }

    @Test
    void theHighestOfNothingIsNothing() {
        assertThat(highestOf().applied()).isFalse();
        assertThat(highestOf(result("0", "a"), result("0", "b")).applied()).isFalse();
    }

    @Test
    void aMinimumLandsUnderTheLabelItWasGiven() {
        FareComponent priced = toLosAngeles(straightThrough());
        FareComponent raised = applyMinimum(priced, result("40.00", "backhaul"), "BHC");
        assertThat(raised.notes()).containsExactly("BHC");
        assertThat(raised.totalNuc()).isEqualByComparingTo("540.00");
    }

    @Test
    void applyingAFindingThatDidNotApplyChangesNothing() {
        FareComponent priced = toLosAngeles(straightThrough());
        assertThat(applyMinimum(priced, result("0", "no"), "BHC")).isSameAs(priced);
    }

    @Test
    void aRoundTripUnitIsNotACircleTripAndTheCheckSaysSo() {
        PricingUnit unit = assembleRoundTrip(
                priced(
                        leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        fare("FRA", "JFK", "420.00")),
                priced(
                        leg("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
                        fare("JFK", "FRA", "420.00")));
        MinimumResult result = circleTripMinimum(unit, new TariffStore(), flatConverter, TICKETING);
        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("not a circle trip");
    }

    @Test
    void aCircleTripThatClearsEveryRoundTripIsLeftAlone() {
        TariffStore store = tariffOf(fare("FRA", "JFK", "300.00"), fare("FRA", "LAX", "350.00"));
        Trace trace = new Trace();
        MinimumResult result = circleTripMinimum(circle(), store, flatConverter, TICKETING, trace);
        assertThat(result.applied()).isFalse();
        assertThat(trace.forStage(Stage.MINIMUM).get(0).message()).contains("clears every round trip");
    }

    @Test
    void aCircleTripThatUndercutsARoundTripIsRaisedToIt() {
        // The three components add to 800. A Frankfurt to Los Angeles round trip
        // half is filed at 480, so the round trip is 960 and that is the floor.
        TariffStore store = tariffOf(fare("FRA", "JFK", "300.00"), fare("FRA", "LAX", "480.00"));
        MinimumResult result = circleTripMinimum(circle(), store, flatConverter, TICKETING);
        assertThat(result.applied()).isTrue();
        assertThat(result.added()).isEqualByComparingTo("160.00");
        assertThat(result.pair()).contains(new CityPair("FRA", "LAX"));
    }

    @Test
    void aOneWayFilingIsNotDoubledForTheComparison() {
        TariffStore store = tariffOf(fare("FRA", "LAX", "480.00").withOwrt(OwRt.ONE_WAY_ONLY));
        assertThat(circleTripMinimum(circle(), store, flatConverter, TICKETING).applied())
                .isFalse();
    }

    @Test
    void aMarketWithNothingPublishedDoesNotSetAFloor() {
        assertThat(circleTripMinimum(circle(), new TariffStore(), flatConverter, TICKETING)
                        .applied())
                .isFalse();
    }

    @Test
    void theCircleTripCheckRunsAfterTheUnitIsPriced() {
        PricingUnit unpriced =
                circle().replace(0, leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"));
        assertThatThrownBy(() -> circleTripMinimum(unpriced, new TariffStore(), flatConverter, TICKETING))
                .isInstanceOf(NoFareFound.class);
    }

    @Test
    void aRoundTripUnitIsNotAnOpenJaw() {
        MinimumResult result = openJawMinimum(circle(), new TariffStore(), flatConverter, TICKETING);
        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("not an open jaw");
    }

    @Test
    void anOpenJawThatClearsItsRoundTripIsLeftAlone() {
        TariffStore store = tariffOf(fare("FRA", "JFK", "300.00"));
        Trace trace = new Trace();
        MinimumResult result = openJawMinimum(openJaw(), store, flatConverter, TICKETING, trace);
        assertThat(result.applied()).isFalse();
        assertThat(trace.forStage(Stage.MINIMUM).get(0).message()).contains("clears the round trip it varies");
    }

    @Test
    void anOpenJawThatUndercutsItsRoundTripIsRaised() {
        // The two components add to 620. The Frankfurt to New York round trip is
        // 700, so the open jaw may not be sold for less than that.
        TariffStore store = tariffOf(fare("FRA", "JFK", "350.00"));
        MinimumResult result = openJawMinimum(openJaw(), store, flatConverter, TICKETING);
        assertThat(result.applied()).isTrue();
        assertThat(result.added()).isEqualByComparingTo("80.00");
        assertThat(result.pair()).contains(new CityPair("FRA", "NYC"));
    }

    @Test
    void anOpenJawWithNoRoundTripFiledHasNoFloor() {
        assertThat(openJawMinimum(openJaw(), new TariffStore(), flatConverter, TICKETING)
                        .applied())
                .isFalse();
    }
}
