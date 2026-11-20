package io.github.rkaushikdev.gussetline.combinability;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.CombinabilityError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class CombinabilityTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();

    /**
     * A component priced with a fare at its neutral amount on the ticketing date, which is all the
     * pricing these checks need.
     */
    private static FareComponent priceComponent(
            FareComponent component, Fare fare, Converter converter, LocalDate ticketing) {
        return component.pricedWith(fare, converter.toNuc(fare.amount(), ticketing));
    }

    private static PricingUnit assembleOneWay(FareComponent component) {
        return new PricingUnit(List.of(component), JourneyType.ONE_WAY);
    }

    private static PricingUnit assembleRoundTrip(FareComponent outbound, FareComponent inbound) {
        return new PricingUnit(List.of(outbound, inbound), JourneyType.ROUND_TRIP);
    }

    private FareComponent out() {
        return out(UnaryOperator.identity());
    }

    private FareComponent out(UnaryOperator<Fare> over) {
        return priceComponent(
                new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                over.apply(fare()),
                flatConverter,
                TICKETING);
    }

    private FareComponent home() {
        return home(UnaryOperator.identity());
    }

    private FareComponent home(UnaryOperator<Fare> over) {
        return priceComponent(
                new FareComponent(List.of(segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"))),
                over.apply(fare("JFK", "FRA")),
                flatConverter,
                TICKETING);
    }

    private static Combinability.Builder record() {
        return record("KLWAP7RT");
    }

    private static Combinability.Builder record(String basis) {
        return record("LH", basis);
    }

    private static Combinability.Builder record(String carrier, String basis) {
        return Combinability.builder(carrier, basis).roundTrip(true);
    }

    private static Fare oneWayOnly(Fare fare) {
        return fare.withOwrt(OwRt.ONE_WAY_ONLY);
    }

    @Test
    void everythingDefaultsToRefused() {
        Combinability plain = new Combinability("LH", "YRT");
        assertThat(plain.roundTrip()).isFalse();
        assertThat(plain.circleTrip()).isFalse();
        assertThat(plain.endOnEnd()).isFalse();
        assertThat(plain.sameCarrierOnly()).isTrue();
    }

    @Test
    void aOneWayIsAlwaysPermittedByTheRecord() {
        assertThat(new Combinability("LH", "YRT").permits(JourneyType.ONE_WAY)).isTrue();
    }

    @Test
    void permitsReadsTheFlagForEachShape() {
        assertThat(record().roundTrip(true).build().permits(JourneyType.ROUND_TRIP))
                .isTrue();
        assertThat(record().roundTrip(true).build().permits(JourneyType.CIRCLE_TRIP))
                .isFalse();
        assertThat(record().circleTrip(true).build().permits(JourneyType.CIRCLE_TRIP))
                .isTrue();
        assertThat(record().openJaw(true).build().permits(JourneyType.OPEN_JAW)).isTrue();
    }

    @Test
    void aRecordReadsBackAsWhatItPermits() {
        assertThat(record().roundTrip(true).endOnEnd(true).build()).hasToString("LH KLWAP7RT RT/EOE");
        assertThat(new Combinability("LH", "YRT")).hasToString("LH YRT nothing");
    }

    @Test
    void theStoreFindsARecordByCarrierAndBasis() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().build());
        assertThat(store.get("lh", "klwap7rt")).isPresent();
        assertThat(store.get("BA", "KLWAP7RT")).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aOneWayUnitPassesWithoutALookup() {
        PricingUnit unit = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        Trace trace = new Trace();
        assertThat(Combinability.checkUnit(unit, new CombinabilityStore(), trace))
                .isEmpty();
        assertThat(trace.failed()).isFalse();
    }

    @Test
    void aRoundTripOfTwoPermittedFaresPasses() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().build());
        PricingUnit unit = assembleRoundTrip(out(), home());
        Trace trace = new Trace();
        assertThat(Combinability.checkUnit(unit, store, trace)).isEmpty();
        assertThat(trace.forStage(Stage.COMBINABILITY)).hasSize(1);
    }

    @Test
    void aFareWithNothingFiledCombinesWithNothing() {
        PricingUnit unit = assembleRoundTrip(out(), home());
        List<String> refusals = Combinability.checkUnit(unit, new CombinabilityStore());
        assertThat(refusals).hasSize(2);
        assertThat(refusals.get(0)).contains("has no combinability filed");
    }

    @Test
    void aFareThatDoesNotPermitTheShapeRefuses() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().roundTrip(false).circleTrip(true).build());
        PricingUnit unit = assembleRoundTrip(out(), home());
        Trace trace = new Trace();
        List<String> refusals = Combinability.checkUnit(unit, store, trace);
        assertThat(refusals).containsExactly("LH KLWAP7RT does not permit RT", "LH KLWAP7RT does not permit RT");
        assertThat(trace.stageFailed(Stage.COMBINABILITY)).isTrue();
    }

    @Test
    void aSameCarrierFilingRefusesAMixedCarrierUnit() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().sameCarrierOnly(true).build());
        store.add(record("UA", "KLWAP7RT").sameCarrierOnly(false).build());
        PricingUnit unit = assembleRoundTrip(out(), home(f -> f.withCarrier("UA")));
        List<String> refusals = Combinability.checkUnit(unit, store);
        assertThat(refusals).anyMatch(r -> r.contains("same carrier only"));
    }

    @Test
    void aMixedCarrierUnitPassesWhenNeitherSideInsists() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().sameCarrierOnly(false).build());
        store.add(record("UA", "KLWAP7RT").sameCarrierOnly(false).build());
        PricingUnit unit = assembleRoundTrip(out(), home(f -> f.withCarrier("UA")));
        assertThat(Combinability.checkUnit(unit, store)).isEmpty();
    }

    @Test
    void aSameRuleFilingRefusesTwoDifferentBases() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().sameCarrierOnly(false).sameRuleOnly(true).build());
        store.add(record("QLWAP14RT").sameCarrierOnly(false).build());
        PricingUnit unit = assembleRoundTrip(out(), home(f -> f.withFareBasis("QLWAP14RT")));
        List<String> refusals = Combinability.checkUnit(unit, store);
        assertThat(refusals).anyMatch(r -> r.contains("same rule only"));
    }

    @Test
    void combinabilityRunsAfterTheUnitIsPriced() {
        PricingUnit unit = assembleRoundTrip(out(), home());
        PricingUnit unpriced = unit.replace(
                0,
                new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))));
        assertThatThrownBy(() -> Combinability.checkUnit(unpriced, new CombinabilityStore()))
                .isInstanceOf(CombinabilityError.class);
    }

    @Test
    void onePricingUnitNeedsNoEndOnEndPermission() {
        PricingUnit unit = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        Trace trace = new Trace();
        assertThat(Combinability.checkEndOnEnd(List.of(unit), new CombinabilityStore(), trace))
                .isEmpty();
        assertThat(trace.failed()).isFalse();
    }

    @Test
    void twoUnitsBothPermittingEndOnEndPass() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().endOnEnd(true).build());
        PricingUnit one = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        PricingUnit two = assembleOneWay(home(CombinabilityTest::oneWayOnly));
        assertThat(Combinability.checkEndOnEnd(List.of(one, two), store)).isEmpty();
    }

    @Test
    void oneFareRefusingEndOnEndStopsTheWholeTicket() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(record().endOnEnd(true).build());
        store.add(record("QLWAP14RT").endOnEnd(false).build());
        PricingUnit one = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        PricingUnit two = assembleOneWay(home(f -> oneWayOnly(f.withFareBasis("QLWAP14RT"))));
        Trace trace = new Trace();
        List<String> refusals = Combinability.checkEndOnEnd(List.of(one, two), store, trace);
        assertThat(refusals).containsExactly("LH QLWAP14RT does not permit end on end");
        assertThat(trace.stageFailed(Stage.COMBINABILITY)).isTrue();
    }

    @Test
    void anUnfiledFareRefusesEndOnEndToo() {
        PricingUnit one = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        PricingUnit two = assembleOneWay(home(CombinabilityTest::oneWayOnly));
        List<String> refusals = Combinability.checkEndOnEnd(List.of(one, two), new CombinabilityStore());
        assertThat(refusals).hasSize(2);
    }

    @Test
    void endOnEndRunsAfterPricing() {
        PricingUnit unpriced = new PricingUnit(
                List.of(new FareComponent(
                        List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")))),
                JourneyType.ONE_WAY);
        PricingUnit one = assembleOneWay(out(CombinabilityTest::oneWayOnly));
        assertThatThrownBy(() -> Combinability.checkEndOnEnd(List.of(one, unpriced), new CombinabilityStore()))
                .isInstanceOf(CombinabilityError.class);
    }
}
