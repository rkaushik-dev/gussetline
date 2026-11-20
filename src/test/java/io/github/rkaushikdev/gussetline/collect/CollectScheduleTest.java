package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Schedule.MAX_INTERVAL_DAYS;
import static io.github.rkaushikdev.gussetline.collect.Schedule.baseInterval;
import static io.github.rkaushikdev.gussetline.collect.Schedule.intervalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectScheduleTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 5, 22);

    private static Market market() {
        return market("FRA", "JFK");
    }

    private static Market market(String origin, String destination) {
        return new Market(origin, destination);
    }

    @Test
    void aMarketFoldsToCitiesAndReadsBackAsAKey() {
        assertThat(market("LHR", "EWR").key()).isEqualTo("LON-NYC");
        assertThat(market()).hasToString("FRA-NYC");
    }

    @Test
    void aMarketGoesBetweenTwoPlaces() {
        assertThatThrownBy(() -> new Market("LHR", "LGW")).isInstanceOf(ValidationError.class);
    }

    @Test
    void theCadenceTightensAsTheDepartureApproaches() {
        assertThat(baseInterval(2)).isEqualTo(1);
        assertThat(baseInterval(3)).isEqualTo(1);
        assertThat(baseInterval(10)).isEqualTo(2);
        assertThat(baseInterval(45)).isEqualTo(4);
        assertThat(baseInterval(90)).isEqualTo(7);
        assertThat(baseInterval(200)).isEqualTo(14);
    }

    @Test
    void aDepartureFurtherOutThanTheLastBandTakesTheLastBand() {
        assertThat(baseInterval(900)).isEqualTo(14);
    }

    @Test
    void aQuietMarketIsPolledLessOften() {
        assertThat(intervalFor(90, 0)).isEqualTo(7);
        assertThat(intervalFor(90, 2)).isEqualTo(8);
        assertThat(intervalFor(90, 8)).isEqualTo(11);
    }

    @Test
    void theWideningIsCappedSoNothingFallsOff() {
        assertThat(intervalFor(200, 200)).isEqualTo(MAX_INTERVAL_DAYS);
    }

    @Test
    void aNegativeQuietCountIsRefused() {
        assertThatThrownBy(() -> intervalFor(90, -1)).isInstanceOf(ValidationError.class);
    }

    private static Series series() {
        return series(LocalDate.of(2025, 6, 4));
    }

    private static Series series(LocalDate departure) {
        return new Series(market(), departure);
    }

    private static Series polledOn(LocalDate day) {
        Series made = series();
        made.setLastPolled(day);
        return made;
    }

    @Test
    void aSeriesThatHasNeverBeenPolledIsDue() {
        assertThat(series().dueOn(TODAY)).isTrue();
    }

    @Test
    void aSeriesPolledTodayIsNotDueAgain() {
        assertThat(polledOn(TODAY).dueOn(TODAY)).isFalse();
    }

    @Test
    void aSeriesBecomesDueWhenItsIntervalHasPassed() {
        // Thirteen days out, so the interval is two days.
        Series s = polledOn(LocalDate.of(2025, 5, 21));
        assertThat(s.dueOn(TODAY)).isFalse();
        s.setLastPolled(LocalDate.of(2025, 5, 20));
        assertThat(s.dueOn(TODAY)).isTrue();
    }

    @Test
    void aDepartureThatHasGoneIsNeverDue() {
        assertThat(series(LocalDate.of(2025, 5, 21)).dueOn(TODAY)).isFalse();
    }

    @Test
    void aDepartureTodayIsStillDue() {
        assertThat(series(TODAY).dueOn(TODAY)).isTrue();
    }

    @Test
    void aPollThatFindsTheSamePriceCountsAsQuiet() {
        Series s = series();
        s.record(TODAY, "420.00");
        assertThat(s.quietPolls()).isZero();
        s.record(LocalDate.of(2025, 5, 24), "420.00");
        assertThat(s.quietPolls()).isEqualTo(1);
        s.record(LocalDate.of(2025, 5, 26), "420.00");
        assertThat(s.quietPolls()).isEqualTo(2);
    }

    @Test
    void aPollThatFindsAMoveResetsTheRun() {
        Series s = series();
        s.record(TODAY, "420.00");
        s.record(LocalDate.of(2025, 5, 24), "420.00");
        s.record(LocalDate.of(2025, 5, 26), "380.00");
        assertThat(s.quietPolls()).isZero();
        assertThat(s.lastAmount()).isEqualTo("380.00");
    }

    @Test
    void aPollThatFoundNothingAtAllKeepsTheLastPrice() {
        Series s = series();
        s.record(TODAY, "420.00");
        s.record(LocalDate.of(2025, 5, 24), "");
        assertThat(s.lastAmount()).isEqualTo("420.00");
        assertThat(s.quietPolls()).isZero();
    }

    @Test
    void aSeriesReadsBackWithItsQuietRun() {
        Series s = series();
        s.record(TODAY, "420.00");
        s.record(LocalDate.of(2025, 5, 24), "420.00");
        assertThat(s).hasToString("FRA-NYC 2025-06-04 quiet 1");
    }

    private static Schedule scheduleWith(LocalDate... departures) {
        Schedule schedule = new Schedule();
        for (LocalDate departure : departures) {
            schedule.follow(market(), departure);
        }
        return schedule;
    }

    @Test
    void aScheduleFollowsASeriesAndFindsItAgain() {
        Schedule schedule = new Schedule();
        schedule.follow(market(), LocalDate.of(2025, 6, 4));
        assertThat(schedule.size()).isEqualTo(1);
        assertThat(schedule.get(market(), LocalDate.of(2025, 6, 4))).isPresent();
        assertThat(schedule.get(market(), LocalDate.of(2025, 7, 4))).isEmpty();
    }

    @Test
    void everythingUnpolledIsDue() {
        Schedule schedule = scheduleWith(LocalDate.of(2025, 6, 4), LocalDate.of(2025, 7, 4));
        assertThat(schedule.due(TODAY)).hasSize(2);
    }

    @Test
    void departuresThatHaveGoneAreRetired() {
        Schedule schedule = scheduleWith(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 6, 4));
        assertThat(schedule.retireFlown(TODAY)).isEqualTo(1);
        assertThat(schedule.size()).isEqualTo(1);
    }

    @Test
    void retiringTwiceFindsNothingTheSecondTime() {
        Schedule schedule = scheduleWith(LocalDate.of(2025, 5, 1));
        assertThat(schedule.retireFlown(TODAY)).isEqualTo(1);
        assertThat(schedule.retireFlown(TODAY)).isZero();
    }

    @Test
    void aPlanTakesTheClosestDeparturesFirst() {
        Schedule schedule = new Schedule();
        schedule.follow(market(), LocalDate.of(2025, 8, 4));
        schedule.follow(market(), LocalDate.of(2025, 6, 4));
        schedule.follow(market(), LocalDate.of(2025, 7, 4));
        List<Task> plan = schedule.plan(TODAY, "gds", 10);
        assertThat(plan.stream().map(Task::departure))
                .containsExactly(LocalDate.of(2025, 6, 4), LocalDate.of(2025, 7, 4), LocalDate.of(2025, 8, 4));
    }

    @Test
    void aBudgetRunsOutOnTheFurthestDepartures() {
        Schedule schedule = new Schedule();
        schedule.follow(market(), LocalDate.of(2025, 8, 4));
        schedule.follow(market(), LocalDate.of(2025, 6, 4));
        List<Task> plan = schedule.plan(TODAY, "gds", 1);
        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).departure()).isEqualTo(LocalDate.of(2025, 6, 4));
    }

    @Test
    void aBudgetOfNothingPlansNothing() {
        Schedule schedule = scheduleWith(LocalDate.of(2025, 6, 4));
        assertThat(schedule.plan(TODAY, "gds", 0)).isEmpty();
    }

    @Test
    void aNegativeBudgetIsRefused() {
        assertThatThrownBy(() -> scheduleWith(LocalDate.of(2025, 6, 4)).plan(TODAY, "gds", -1))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aTaskCarriesTheDistanceItWasPlannedAt() {
        List<Task> plan = scheduleWith(LocalDate.of(2025, 6, 4)).plan(TODAY, "gds", 1);
        assertThat(plan.get(0).daysOut()).isEqualTo(13);
        assertThat(plan.get(0)).hasToString("gds FRA-NYC 2025-06-04");
    }

    @Test
    void aRunPlanSplitsBySupplierAndListsItsMarkets() {
        Schedule schedule = new Schedule();
        schedule.follow(market(), LocalDate.of(2025, 6, 4));
        schedule.follow(new Market("MUC", "ORD"), LocalDate.of(2025, 6, 4));
        List<Task> tasks = new ArrayList<>(schedule.plan(TODAY, "gds", 10));
        tasks.addAll(schedule.plan(TODAY, "ndc", 10));
        RunPlan plan = new RunPlan(tasks);
        assertThat(plan.size()).isEqualTo(4);
        assertThat(plan.forSupplier("gds")).hasSize(2);
        assertThat(plan.markets()).containsExactly("FRA-NYC", "MUC-CHI");
        assertThat(plan).hasToString("4 tasks over 2 markets");
    }
}
