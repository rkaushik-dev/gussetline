package io.github.rkaushikdev.gussetline.construction;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleCircleTrip;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleOneWay;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleRoundTrip;
import static io.github.rkaushikdev.gussetline.construction.Construction.fareToNuc;
import static io.github.rkaushikdev.gussetline.construction.Construction.priceComponent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.exchange.Exchange;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ConstructionTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    // The published quarterly rates, because these tests are about the conversion and a rate of one
    // would not exercise the truncation.
    private final Converter converter = new Converter(Exchange.defaultRoeTable());

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static FareComponent home() {
        return new FareComponent(List.of(segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private FareComponent priced(FareComponent component) {
        return priced(component, UnaryOperator.identity());
    }

    private FareComponent priced(FareComponent component, UnaryOperator<Fare> over) {
        return priceComponent(component, over.apply(fare()), converter, TICKETING);
    }

    private Construction build() {
        PricingUnit unit = assembleRoundTrip(priced(out()), priced(home()));
        return new Construction(List.of(unit), "EUR", TICKETING, new Trace("FRAJFK"));
    }

    private static FareComponent middle() {
        return new FareComponent(List.of(segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15")));
    }

    private static FareComponent back() {
        return new FareComponent(List.of(segment("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20")));
    }

    @Test
    void aFareConvertsIntoNeutralUnitsAndTruncates() {
        // 420.00 EUR at the first quarter rate of 0.951 is 441.640378...
        assertThat(fareToNuc(fare(), converter, TICKETING)).isEqualByComparingTo("441.64");
    }

    @Test
    void aRoundTripHalfConvertsAsFiledAndStaysAHalf() {
        BigDecimal half = fareToNuc(fare().withOwrt(OwRt.ROUND_TRIP_HALF), converter, TICKETING);
        BigDecimal whole = fareToNuc(fare().withOwrt(OwRt.ONE_WAY_ONLY), converter, TICKETING);
        assertThat(half).isEqualByComparingTo(whole);
    }

    @Test
    void theQuarterTheTicketWasSoldInDecidesTheRate() {
        BigDecimal first = fareToNuc(fare(), converter, LocalDate.of(2025, 2, 14));
        BigDecimal second = fareToNuc(fare(), converter, LocalDate.of(2025, 5, 14));
        assertThat(first).isNotEqualByComparingTo(second);
    }

    @Test
    void pricingAComponentAttachesTheFareAndRecordsIt() {
        Trace trace = new Trace();
        FareComponent priced = priceComponent(out(), fare(), converter, TICKETING, trace);
        assertThat(priced.fare()).isPresent();
        assertThat(priced.nuc().orElseThrow()).isEqualByComparingTo("441.64");
        assertThat(trace.forStage(Stage.CONVERSION)).hasSize(1);
        assertThat(trace.failed()).isFalse();
    }

    @Test
    void pricingWithoutATraceIsAllowed() {
        assertThat(priceComponent(out(), fare(), converter, TICKETING).nuc().orElseThrow())
                .isEqualByComparingTo("441.64");
    }

    @Test
    void aRoundTripUnitNeedsBothHalvesPriced() {
        assertThatThrownBy(() -> assembleRoundTrip(out(), priced(home()))).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aOneWayOnlyFareCannotBeHalfOfARoundTrip() {
        ConstructionError caught = catchThrowableOfType(
                ConstructionError.class,
                () -> assembleRoundTrip(priced(out(), f -> f.withOwrt(OwRt.ONE_WAY_ONLY)), priced(home())));
        assertThat(caught.details().get("outbound")).isEqualTo("KLWAP7RT");
    }

    @Test
    void aRoundTripUnitAssemblesAndRecords() {
        Trace trace = new Trace();
        PricingUnit unit = assembleRoundTrip(priced(out()), priced(home()), trace);
        assertThat(unit.size()).isEqualTo(2);
        assertThat(unit.totalNuc()).isEqualByComparingTo("883.28");
        assertThat(trace.forStage(Stage.SELECTION)).hasSize(1);
    }

    @Test
    void aRoundTripHalfCannotStandAsAOneWay() {
        assertThatThrownBy(() -> assembleOneWay(priced(out()))).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aOneWayOnlyFareStandsAlone() {
        PricingUnit unit = assembleOneWay(priced(out(), f -> f.withOwrt(OwRt.ONE_WAY_ONLY)));
        assertThat(unit.size()).isEqualTo(1);
    }

    @Test
    void aOneWayUnitNeedsItsComponentPriced() {
        assertThatThrownBy(() -> assembleOneWay(out())).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aCircleTripNeedsThreeComponents() {
        assertThatThrownBy(() -> assembleCircleTrip(List.of(priced(out()), priced(home()))))
                .isInstanceOf(ConstructionError.class);
    }

    @Test
    void aCircleTripAssemblesFromThree() {
        PricingUnit unit = assembleCircleTrip(List.of(
                priced(out()),
                priceComponent(middle(), fare("JFK", "LAX"), converter, TICKETING),
                priceComponent(back(), fare("LAX", "FRA"), converter, TICKETING)));
        assertThat(unit.size()).isEqualTo(3);
    }

    @Test
    void aCircleTripRefusesAnUnpricedComponent() {
        assertThatThrownBy(() -> assembleCircleTrip(List.of(priced(out()), middle(), back())))
                .isInstanceOf(ConstructionError.class);
    }

    @Test
    void aConstructionNeedsAUnit() {
        assertThatThrownBy(() -> new Construction(List.of(), "EUR", TICKETING)).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aConstructionRefusesAnUnpricedUnit() {
        PricingUnit unit = assembleRoundTrip(priced(out()), priced(home()));
        PricingUnit unpriced = unit.replace(0, out());
        assertThatThrownBy(() -> new Construction(List.of(unpriced), "EUR", TICKETING))
                .isInstanceOf(ConstructionError.class);
    }

    @Test
    void theBaseIsTheComponentsAddedUp() {
        assertThat(build().baseNuc()).isEqualByComparingTo("883.28");
        assertThat(build().totalNuc()).isEqualByComparingTo("883.28");
    }

    @Test
    void aMinimumCheckRaisesTheTotalAndLeavesTheBase() {
        Construction raised = build().withMinimum(new BigDecimal("40.00"), "circle trip minimum");
        assertThat(raised.baseNuc()).isEqualByComparingTo("883.28");
        assertThat(raised.totalNuc()).isEqualByComparingTo("923.28");
        assertThat(raised.trace().forStage(Stage.MINIMUM).get(0).details().get("added"))
                .isEqualTo("40.00");
    }

    @Test
    void minimumChecksAccumulate() {
        Construction raised =
                build().withMinimum(new BigDecimal("40.00"), "HIP").withMinimum(new BigDecimal("10.00"), "backhaul");
        assertThat(raised.totalNuc()).isEqualByComparingTo("933.28");
    }

    @Test
    void aMinimumCheckNeverReducesAFare() {
        Construction built = build();
        assertThatThrownBy(() -> built.withMinimum(new BigDecimal("-1"), "nonsense"))
                .isInstanceOf(ConstructionError.class);
    }

    @Test
    void theConversionIntoTheSellingCurrencyHappensOnce() {
        // 883.28 NUC at 0.951 is 840.00 EUR, and the euro fare unit is one, so it rounds up to 840.
        // Converting each half and adding would give 841.
        assertThat(build().baseFare(converter)).isEqualTo(Money.of("840", "EUR"));
    }

    @Test
    void theComponentsAreReachableAcrossTheUnits() {
        assertThat(build().components()).hasSize(2);
    }

    @Test
    void aConstructionReadsBackWithItsTargetCurrency() {
        assertThat(build()).hasToString("883.28 NUC into EUR");
    }
}
