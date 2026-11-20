package io.github.rkaushikdev.gussetline.mileage;

import static io.github.rkaushikdev.gussetline.mileage.MileagePrinciple.MAX_SURCHARGE;
import static io.github.rkaushikdev.gussetline.mileage.MileagePrinciple.bandFor;
import static io.github.rkaushikdev.gussetline.mileage.MileagePrinciple.check;
import static io.github.rkaushikdev.gussetline.mileage.MileagePrinciple.maximumPermitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.MileageUnavailable;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MileageTest {

    private static MileageTable table() {
        MileageTable t = new MileageTable();
        t.add("FRA", "JFK", 4000);
        t.add("FRA", "LON", 400);
        t.add("LON", "JFK", 3500);
        t.add("FRA", "DXB", 3000);
        t.add("DXB", "JFK", 6800);
        return t;
    }

    @ParameterizedTest
    @CsvSource({"80, 0", "100, 0", "101, 5", "105, 5", "106, 10", "110, 10", "115, 15", "120, 20", "125, 25"})
    void theLadderHasSixStepsAndTheyAreInclusiveAtTheTop(int percentage, int surcharge) {
        assertThat(bandFor(percentage)).contains(surcharge);
    }

    @Test
    void pastTheTopOfTheLadderThereIsNoBand() {
        assertThat(bandFor(126)).isEmpty();
        assertThat(bandFor(300)).isEmpty();
    }

    @Test
    void theMaximumIsTwentyPercentAboveTheShortestMileage() {
        assertThat(maximumPermitted("FRA", "JFK", table())).isEqualTo(4800);
    }

    @Test
    void aDirectSectorIsWellInsideTheMaximum() {
        MileageResult result = check("FRA", "JFK", List.of(), table());
        assertThat(result.flown()).isEqualTo(4000);
        assertThat(result.maximum()).isEqualTo(4800);
        assertThat(result.percentage()).isEqualTo(84);
        assertThat(result.surchargePercent()).isZero();
        assertThat(result.permitted()).isTrue();
        assertThat(result.band()).isEqualTo("M");
    }

    @Test
    void aDetourAddsUpTheSectorsAndNotTheEndToEnd() {
        MileageResult result = check("FRA", "JFK", List.of("LON"), table());
        assertThat(result.flown()).isEqualTo(3900);
        assertThat(result.percentage()).isEqualTo(82);
    }

    @Test
    void aLongDetourLandsOnASurchargeBand() {
        // Frankfurt to Dubai to New York is 9800 against a 4800 maximum.
        MileageResult result = check("FRA", "JFK", List.of("DXB"), table());
        assertThat(result.flown()).isEqualTo(9800);
        assertThat(result.permitted()).isFalse();
        assertThat(result.surchargePercent()).isEqualTo(MAX_SURCHARGE);
    }

    @Test
    void aJourneyJustOverTheMaximumTakesTheFirstStep() {
        MileageTable t = new MileageTable();
        t.add("FRA", "JFK", 4000);
        t.add("FRA", "LON", 500);
        t.add("LON", "JFK", 4400);
        MileageResult result = check("FRA", "JFK", List.of("LON"), t);
        assertThat(result.flown()).isEqualTo(4900);
        assertThat(result.percentage()).isEqualTo(103);
        assertThat(result.surchargePercent()).isEqualTo(5);
        assertThat(result.band()).isEqualTo("5M");
    }

    @Test
    void thePercentageRoundsUpSoAFractionDoesNotUndercharge() {
        MileageResult result = new MileageResult(4801, 4800, 0, 0, true);
        assertThat(result.percentage()).isEqualTo(101);
    }

    @Test
    void aJourneyExactlyOnTheMaximumOwesNothing() {
        MileageResult result = new MileageResult(4800, 4800, 0, 0, true);
        assertThat(result.percentage()).isEqualTo(100);
        assertThat(bandFor(result.percentage())).contains(0);
    }

    @Test
    void theMultiplierAndTheSurchargeAmountAgree() {
        MileageResult result = new MileageResult(5000, 4800, 0, 10, true);
        assertThat(result.multiplier()).isEqualByComparingTo("1.10");
        assertThat(result.surchargeOn(new BigDecimal("441.64"))).isEqualByComparingTo("44.16");
    }

    @Test
    void noSurchargeMeansNoAmount() {
        MileageResult result = new MileageResult(4000, 4800, 0, 0, true);
        assertThat(result.surchargeOn(new BigDecimal("441.64"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aMaximumOfZeroDoesNotDivideByIt() {
        MileageResult result = new MileageResult(100, 0, 0, 0, true);
        assertThat(result.percentage()).isZero();
    }

    @Test
    void anAllowanceComesOffTheFlownFigureAndCanClearABand() {
        ExtraMileageAllowance allowances = new ExtraMileageAllowance();
        allowances.addPair("FRA", "JFK", 1200);
        MileageResult plain = check("FRA", "JFK", List.of("DXB"), table());
        MileageResult allowed = check(
                "FRA", "JFK", List.of("DXB"), table(), Optional.empty(), Optional.of(allowances), Optional.empty());
        assertThat(plain.chargeable()).isEqualTo(9800);
        assertThat(allowed.chargeable()).isEqualTo(8600);
        assertThat(allowed.deduction()).isEqualTo(1200);
    }

    @Test
    void aPairAllowanceReadsEitherWayRound() {
        ExtraMileageAllowance allowances = new ExtraMileageAllowance();
        allowances.addPair("FRA", "JFK", 500);
        assertThat(allowances.deductionFor("JFK", "FRA", List.of())).isEqualTo(500);
    }

    @Test
    void aPointDeductionOnlyAppliesWhenThePointIsFlown() {
        ExtraMileageAllowance allowances = new ExtraMileageAllowance();
        allowances.addPoint("FRA", "JFK", "LON", 300);
        assertThat(allowances.deductionFor("FRA", "JFK", List.of("LON"))).isEqualTo(300);
        assertThat(allowances.deductionFor("FRA", "JFK", List.of("AMS"))).isZero();
        assertThat(allowances.deductionFor("FRA", "JFK", List.of())).isZero();
    }

    @Test
    void aPairAllowanceAndAPointDeductionStack() {
        ExtraMileageAllowance allowances = new ExtraMileageAllowance();
        allowances.addPair("FRA", "JFK", 400);
        allowances.addPoint("FRA", "JFK", "LON", 300);
        assertThat(allowances.deductionFor("FRA", "JFK", List.of("LON"))).isEqualTo(700);
    }

    @Test
    void aDeductionCannotPushTheChargeableBelowZero() {
        MileageResult result = new MileageResult(100, 4800, 500, 0, true);
        assertThat(result.chargeable()).isZero();
    }

    @Test
    void aCheckNeedsTwoPoints() {
        MileageTable table = table();
        assertThatThrownBy(() -> check("FRA", "", List.of(), table)).isInstanceOf(MileageUnavailable.class);
    }

    @Test
    void aPassIsRecordedWithBothFigures() {
        Trace trace = new Trace();
        check("FRA", "JFK", List.of(), table(), Optional.empty(), Optional.empty(), Optional.of(trace));
        Step step = trace.forStage(Stage.MILEAGE).get(0);
        assertThat(step.failed()).isFalse();
        assertThat(step.details()).isEqualTo(Map.of("flown", 4000, "maximum", 4800));
    }

    @Test
    void goingOffTheLadderIsRecordedAsAFailure() {
        Trace trace = new Trace();
        MileageResult result =
                check("FRA", "JFK", List.of("DXB"), table(), Optional.empty(), Optional.empty(), Optional.of(trace));
        assertThat(result.permitted()).isFalse();
        assertThat(trace.stageFailed(Stage.MILEAGE)).isTrue();
        assertThat(trace.reasons().get(0)).contains("over the mileage ladder");
    }

    @Test
    void aFallbackMileageIsNotedButDoesNotFail() {
        Trace trace = new Trace();
        MileageResult result =
                check("FRA", "BKK", List.of(), table(), Optional.empty(), Optional.empty(), Optional.of(trace));
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(trace.failed()).isFalse();
        assertThat(trace.forStage(Stage.MILEAGE))
                .anyMatch(step -> step.message().contains("great circle"));
    }

    @Test
    void theIndicatorReachesTheTable() {
        MileageTable t = new MileageTable();
        t.add("FRA", "TYO", 5928, GlobalIndicator.TS);
        t.add("FRA", "TYO", 7169, GlobalIndicator.PA);
        MileageResult east =
                check("FRA", "NRT", List.of(), t, Optional.of(GlobalIndicator.TS), Optional.empty(), Optional.empty());
        MileageResult west =
                check("FRA", "NRT", List.of(), t, Optional.of(GlobalIndicator.PA), Optional.empty(), Optional.empty());
        assertThat(east.maximum()).isLessThan(west.maximum());
        assertThat(east.fallbackUsed()).isFalse();
    }

    @Test
    void aResultReadsBackWithItsBand() {
        assertThat(check("FRA", "JFK", List.of(), table())).hasToString("84 percent of maximum, M");
        assertThat(check("FRA", "JFK", List.of("DXB"), table())).hasToString("205 percent of maximum, over the ladder");
    }
}
