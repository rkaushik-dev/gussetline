package io.github.rkaushikdev.gussetline.fcl;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleOneWay;
import static io.github.rkaushikdev.gussetline.construction.Construction.assembleRoundTrip;
import static io.github.rkaushikdev.gussetline.construction.Construction.priceComponent;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.addsUp;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.compare;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.componentsIn;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.parse;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.render;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.renderComponent;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.roeIn;
import static io.github.rkaushikdev.gussetline.fcl.Fcl.totalIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.construction.Construction;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.exchange.RateTable;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FclTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);
    private static final BigDecimal ROE = new BigDecimal("0.951");

    // These tests read the rate off the rendered line, so one to one would
    // hide a conversion that never happened.
    private final Converter converter = converter();

    private static Converter converter() {
        RateTable table = new RateTable("roe");
        table.add("EUR", LocalDate.of(2025, 1, 1), "0.951");
        return new Converter(table);
    }

    private FareComponent out() {
        return priceComponent(
                new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                fare(),
                converter,
                TICKETING);
    }

    private FareComponent home() {
        return priceComponent(
                new FareComponent(List.of(segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"))),
                fare("JFK", "FRA"),
                converter,
                TICKETING);
    }

    private FareComponent viaLondon() {
        return priceComponent(
                new FareComponent(List.of(
                        segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                        segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"))),
                fare(),
                converter,
                TICKETING);
    }

    private Construction build() {
        PricingUnit unit = assembleRoundTrip(out(), home());
        return new Construction(List.of(unit), "EUR", TICKETING, new Trace());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    @Test
    void anUnpricedComponentHasNoLine() {
        FareComponent unpriced =
                new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
        assertThatThrownBy(() -> renderComponent(unpriced)).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aDirectComponentIsBoardCarrierOffAmount() {
        assertThat(renderComponent(out())).isEqualTo("FRA LH NYC 441.64");
    }

    @Test
    void anIntermediatePointIsWrittenPlainWhenItIsAStopover() {
        assertThat(renderComponent(viaLondon())).isEqualTo("FRA LH LON LH NYC 441.64");
    }

    @Test
    void anIntermediatePointIsMarkedWhenItIsAConnection() {
        assertThat(renderComponent(viaLondon(), List.of("LHR"))).isEqualTo("FRA LH X/LON LH NYC 441.64");
    }

    @Test
    void theConnectionMarkTakesAirportsOrCities() {
        assertThat(renderComponent(viaLondon(), List.of("LON"))).isEqualTo("FRA LH X/LON LH NYC 441.64");
    }

    @Test
    void theFinalPointIsNeverMarkedEvenIfItIsNamed() {
        assertThat(renderComponent(out(), List.of("JFK"))).isEqualTo("FRA LH NYC 441.64");
    }

    @Test
    void aSurfaceSectorIsWrittenWithSlashes() {
        FareComponent component = priceComponent(
                new FareComponent(List.of(
                        segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        surface("JFK", "BOS"))),
                fare("FRA", "BOS"),
                converter,
                TICKETING);
        assertThat(renderComponent(component)).isEqualTo("FRA LH NYC // BOS 441.64");
    }

    @Test
    void aMileageBandIsShownAndFoldedIntoTheAmount() {
        FareComponent component = out().withSurcharge(new BigDecimal("22.08"), "5M");
        assertThat(renderComponent(component)).isEqualTo("FRA LH NYC 5M 463.72");
    }

    @Test
    void aQSurchargeIsWrittenInFrontAndLeftOutOfTheAmount() {
        FareComponent component = out().withSurcharge(new BigDecimal("30.00"), "Q");
        assertThat(renderComponent(component)).isEqualTo("FRA LH NYC Q30.00 441.64");
    }

    @Test
    void aHigherIntermediatePointIsFoldedInWithoutAToken() {
        FareComponent component = out().withSurcharge(new BigDecimal("120.00"), "HIP");
        assertThat(renderComponent(component)).isEqualTo("FRA LH NYC 561.64");
    }

    @Test
    void bothKindsOfChargeLandInTheirOwnPlaces() {
        FareComponent component =
                out().withSurcharge(new BigDecimal("30.00"), "Q").withSurcharge(new BigDecimal("22.08"), "5M");
        assertThat(renderComponent(component)).isEqualTo("FRA LH NYC Q30.00 5M 463.72");
    }

    @Test
    void theWholeLineJoinsTheComponentsAndEndsWithTheRate() {
        assertThat(render(build(), ROE)).isEqualTo("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000");
    }

    @Test
    void theSecondComponentDoesNotRepeatThePointItStartsAt() {
        String line = render(build(), ROE);
        assertThat(occurrences(line, "FRA")).isEqualTo(2);
    }

    @Test
    void aOneWayLineCarriesOneAmount() {
        PricingUnit unit = assembleOneWay(priceComponent(
                new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                fare().withOwrt(OwRt.ONE_WAY_ONLY),
                converter,
                TICKETING));
        String line = render(new Construction(List.of(unit), "EUR", TICKETING), ROE);
        assertThat(line).isEqualTo("FRA LH NYC 441.64 NUC441.64END ROE0.951000");
        assertThat(componentsIn(line)).isEqualTo(1);
    }

    @Test
    void aMinimumCheckShowsInTheTotalAndNotInAComponent() {
        Construction raised = build().withMinimum(new BigDecimal("40.00"), "circle trip minimum");
        String line = render(raised, ROE);
        assertThat(line).contains("NUC923.28END");
        assertThat(line).contains(" 441.64 ");
    }

    @Test
    void theRateIsWrittenToSixDecimalsWhateverItIs() {
        assertThat(render(build(), new BigDecimal("1"))).contains("ROE1.000000");
        assertThat(render(build(), new BigDecimal("152.44"))).contains("ROE152.440000");
    }

    @Test
    void aRateOfZeroOrLessIsRefused() {
        Construction construction = build();
        assertThatThrownBy(() -> render(construction, new BigDecimal("0"))).isInstanceOf(ConstructionError.class);
        assertThatThrownBy(() -> render(construction, new BigDecimal("-1"))).isInstanceOf(ConstructionError.class);
    }

    @Test
    void theTotalAndTheRateReadBackOffTheLine() {
        String line = render(build(), ROE);
        assertThat(totalIn(line)).isEqualByComparingTo("883.28");
        assertThat(roeIn(line)).isEqualByComparingTo("0.951000");
    }

    @Test
    void aLineWithNoTotalIsRefused() {
        assertThatThrownBy(() -> totalIn("FRA LH NYC 441.64")).isInstanceOf(ConstructionError.class);
        assertThatThrownBy(() -> roeIn("FRA LH NYC 441.64 NUC441.64END")).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aLineWhosePartsAddUpSaysSo() {
        assertThat(addsUp(render(build(), ROE))).isTrue();
    }

    @Test
    void aQSurchargeCountsTowardTheTotal() {
        FareComponent component = out().withSurcharge(new BigDecimal("30.00"), "Q");
        PricingUnit unit = assembleRoundTrip(component, home());
        String line = render(new Construction(List.of(unit), "EUR", TICKETING), ROE);
        assertThat(line).contains("Q30.00");
        assertThat(line).contains("NUC913.28END");
        assertThat(addsUp(line)).isTrue();
    }

    @Test
    void aLineWhoseTotalWasNotRebuiltIsCaught() {
        assertThat(addsUp("FRA LH NYC 441.64 LH FRA 441.64 NUC900.00END ROE0.951000"))
                .isFalse();
    }

    @Test
    void countingComponentsIgnoresTheTotalsAndTheSurcharge() {
        String line = "FRA LH NYC Q30.00 441.64 LH FRA 441.64 NUC913.28END ROE0.951000";
        assertThat(componentsIn(line)).isEqualTo(2);
    }

    @Test
    void aLineComesApartIntoComponents() {
        ParsedLine read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000");
        assertThat(read.components()).hasSize(2);
        assertThat(read.components().get(0).points()).containsExactly("FRA", "NYC");
        assertThat(read.components().get(0).carriers()).containsExactly("LH");
        assertThat(read.components().get(0).amount()).isEqualByComparingTo("441.64");
        assertThat(read.total()).isEqualByComparingTo("883.28");
        assertThat(read.roe()).isEqualByComparingTo("0.951000");
    }

    @Test
    void theSecondComponentStartsWhereTheFirstEnded() {
        ParsedLine read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000");
        assertThat(read.components().get(1).points()).containsExactly("NYC", "FRA");
        assertThat(read.components().get(1).board()).isEqualTo("NYC");
        assertThat(read.components().get(1).off()).isEqualTo("FRA");
    }

    @Test
    void aMarkedConnectionIsReadBackAsOne() {
        ParsedLine read = parse("FRA LH X/LON LH NYC 441.64 NUC441.64END ROE0.951000");
        ParsedComponent component = read.components().get(0);
        assertThat(component.points()).containsExactly("FRA", "LON", "NYC");
        assertThat(component.connections()).containsExactly("LON");
    }

    @Test
    void anUnmarkedIntermediatePointIsNotAConnection() {
        ParsedLine read = parse("FRA LH LON LH NYC 441.64 NUC441.64END ROE0.951000");
        assertThat(read.components().get(0).connections()).isEmpty();
    }

    @Test
    void aQSurchargeAndABandAreReadOffTheComponent() {
        ParsedLine read = parse("FRA LH NYC Q30.00 5M 463.72 NUC493.72END ROE0.951000");
        ParsedComponent component = read.components().get(0);
        assertThat(component.qSurcharge()).isEqualByComparingTo("30.00");
        assertThat(component.band()).isEqualTo("5M");
        assertThat(component.amount()).isEqualByComparingTo("463.72");
    }

    @Test
    void aSurfaceSectorSurvivesTheRoundTrip() {
        ParsedLine read = parse("FRA LH NYC // BOS 441.64 NUC441.64END ROE0.951000");
        assertThat(read.components().get(0).points()).containsExactly("FRA", "NYC", "BOS");
        assertThat(read.components().get(0).carriers()).contains("//");
    }

    @Test
    void aParsedLineKnowsWhetherItAddsUp() {
        assertThat(parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000")
                        .addsUp())
                .isTrue();
        assertThat(parse("FRA LH NYC 441.64 LH FRA 441.64 NUC900.00END ROE0.951000")
                        .addsUp())
                .isFalse();
    }

    @Test
    void theGapAMinimumCheckLeavesIsReportedAsUnexplained() {
        ParsedLine read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC923.28END ROE0.951000");
        assertThat(read.unexplained()).isEqualByComparingTo("40.00");
    }

    @Test
    void parsingRefusesOnlyTheTwoThingsItCannotGuess() {
        assertThatThrownBy(() -> parse("")).isInstanceOf(ConstructionError.class);
        assertThatThrownBy(() -> parse("FRA LH NYC 441.64 ROE0.951000")).isInstanceOf(ConstructionError.class);
        assertThatThrownBy(() -> parse("FRA LH NYC 441.64 NUC441.64END")).isInstanceOf(ConstructionError.class);
    }

    @Test
    void whatWeRenderIsWhatWeReadBack() {
        String line = render(build(), ROE);
        ParsedLine read = parse(line);
        assertThat(read.raw()).isEqualTo(line);
        assertThat(read.components()).hasSize(2);
        assertThat(read.total()).isEqualByComparingTo(totalIn(line));
        assertThat(read.roe()).isEqualByComparingTo(roeIn(line));
    }

    @Test
    void twoIdenticalLinesHaveNothingToSay() {
        String line = "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000";
        assertThat(compare(line, line)).isEmpty();
    }

    @Test
    void aDifferentTotalIsReported() {
        List<String> found = compare(
                "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000",
                "FRA LH NYC 441.64 LH FRA 441.64 NUC903.28END ROE0.951000");
        assertThat(found).containsExactly("neutral total 883.28 against 903.28");
    }

    @Test
    void aDifferentRateIsReported() {
        List<String> found =
                compare("FRA LH NYC 441.64 NUC441.64END ROE0.951000", "FRA LH NYC 441.64 NUC441.64END ROE0.924000");
        assertThat(found).containsExactly("rate of exchange 0.951000 against 0.924000");
    }

    @Test
    void aDifferentComponentCountStopsThere() {
        List<String> found = compare(
                "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000",
                "FRA LH NYC 883.28 NUC883.28END ROE0.951000");
        assertThat(found).containsExactly("2 components against 1");
    }

    @Test
    void aDifferentRouteOnOneComponentIsNamed() {
        List<String> found = compare(
                "FRA LH LON LH NYC 441.64 NUC441.64END ROE0.951000",
                "FRA LH AMS LH NYC 441.64 NUC441.64END ROE0.951000");
        assertThat(found).containsExactly("component 1 routes FRA-LON-NYC against FRA-AMS-NYC");
    }

    @Test
    void aDifferentAmountAndBandAreBothNamed() {
        List<String> found =
                compare("FRA LH NYC 5M 463.72 NUC463.72END ROE0.951000", "FRA LH NYC 441.64 NUC441.64END ROE0.951000");
        assertThat(found).contains("component 1 is 463.72 against 441.64");
        assertThat(found).contains("component 1 band 5M against none");
    }

    @Test
    void aQSurchargeOnlyOneSideChargedIsNamed() {
        List<String> found = compare(
                "FRA LH NYC Q30.00 441.64 NUC471.64END ROE0.951000", "FRA LH NYC 441.64 NUC441.64END ROE0.951000");
        assertThat(found).contains("component 1 q surcharge 30.00 against 0");
    }
}
