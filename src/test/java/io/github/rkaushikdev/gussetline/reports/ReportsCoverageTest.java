package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.reports.Coverage.build;
import static io.github.rkaushikdev.gussetline.reports.Coverage.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Registry;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.reports.Coverage.Cover;
import io.github.rkaushikdev.gussetline.reports.Coverage.Watched;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportsCoverageTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 7, 15);
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 20);
    private static final Market FRA_JFK = new Market("FRA", "JFK");
    private static final Market MUC_ORD = new Market("MUC", "ORD");

    private Registry registry;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        registry = Registry.defaultRegistry();
        schedule = new Schedule();
        schedule.follow(FRA_JFK, AUGUST);
    }

    private static Task task(Market market, LocalDate departure, String supplier) {
        return new Task(supplier, market, departure, Math.toIntExact(ChronoUnit.DAYS.between(TODAY, departure)));
    }

    private static RunReport run(Result... results) {
        return run(TODAY, results);
    }

    private static RunReport run(LocalDate on, Result... results) {
        return new RunReport(on.atTime(6, 0), List.of(results));
    }

    private static Result answered() {
        return answered(FRA_JFK, AUGUST, "gds");
    }

    private static Result answered(String supplier) {
        return answered(FRA_JFK, AUGUST, supplier);
    }

    private static Result answered(Market market, LocalDate departure) {
        return answered(market, departure, "gds");
    }

    private static Result answered(Market market, LocalDate departure, String supplier) {
        return Result.builder(task(market, departure, supplier), Outcome.OK)
                .offers(6)
                .attempts(1)
                .build();
    }

    private static Result failed() {
        return Result.builder(task(FRA_JFK, AUGUST, "gds"), Outcome.TRANSIENT)
                .attempts(1)
                .build();
    }

    /** The series under a key, which has to be there. */
    private static Watched row(Coverage report, String key) {
        Optional<Watched> found = report.get(key);
        assertThat(found).isPresent();
        return found.get();
    }

    /** How many times a piece of text occurs in another, without overlapping. */
    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count += 1;
        }
        return count;
    }

    @Test
    void anEmptyScheduleCoversNothingAndSaysSo() {
        Coverage report = build(new Schedule(), List.of(), registry, TODAY);
        assertThat(report.size()).isZero();
        assertThat(report.rate()).isEqualByComparingTo("0");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void aSeriesNobodyPolledIsDark() {
        Coverage report = build(schedule, List.of(), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.cover()).isSameAs(Cover.DARK);
        assertThat(row.polls()).isZero();
    }

    @Test
    void aSeriesPolledWithNothingComingBackIsStillDark() {
        Coverage report = build(schedule, List.of(run(failed(), failed())), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.cover()).isSameAs(Cover.DARK);
        assertThat(row.polls()).isEqualTo(2);
        assertThat(row.answers()).isZero();
        assertThat(row.why()).isEqualTo("2 polls and nothing answered");
    }

    @Test
    void aSeriesAPricingSupplierAnsweredForTodayIsPriced() {
        Coverage report = build(schedule, List.of(run(answered())), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.cover()).isSameAs(Cover.PRICED);
        assertThat(row.age()).contains(0);
        assertThat(row.pricedAnswers()).isEqualTo(1);
    }

    @Test
    void onlyTheCalendarAnsweringLeavesTheSeriesQuoted() {
        // The calendar covers markets the other two do not, and none of its
        // answers can be repriced, so this is not coverage the desk can use.
        Coverage report = build(schedule, List.of(run(answered("calendar"))), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.cover()).isSameAs(Cover.QUOTED);
        assertThat(row.answers()).isEqualTo(1);
        assertThat(row.pricedAnswers()).isZero();
        assertThat(row.why()).isEqualTo("quotes only, nothing that can be repriced");
    }

    @Test
    void aQuoteDoesNotKeepAPricedSeriesFresh() {
        RunReport old = run(LocalDate.of(2025, 6, 1), answered());
        RunReport recent = run(TODAY, answered("calendar"));
        Coverage report = build(schedule, List.of(old, recent), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.cover()).isSameAs(Cover.LATE);
    }

    @Test
    void anAnswerInsideTheAllowanceIsNotLate() {
        // August 20 is 36 days out, which the schedule polls every 4 days, so an
        // answer 6 days old is well inside twice that.
        Coverage report = build(schedule, List.of(run(LocalDate.of(2025, 7, 9), answered())), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.interval()).isEqualTo(4);
        assertThat(row.allowance()).isEqualTo(8);
        assertThat(row.age()).contains(6);
        assertThat(row.cover()).isSameAs(Cover.PRICED);
    }

    @Test
    void theAllowanceFollowsTheSeriesAndNotAGlobalNumber() {
        // Same age of answer, two departures. The near one is late and the far
        // one is not, which is the entire reason the allowance is per series.
        Schedule schedule = new Schedule();
        schedule.follow(FRA_JFK, LocalDate.of(2025, 7, 17));
        schedule.follow(MUC_ORD, LocalDate.of(2026, 2, 1));
        List<RunReport> reports = List.of(run(
                LocalDate.of(2025, 7, 8),
                answered(FRA_JFK, LocalDate.of(2025, 7, 17)),
                answered(MUC_ORD, LocalDate.of(2026, 2, 1))));
        Coverage report = build(schedule, reports, registry, TODAY);
        Watched near = row(report, "FRA-NYC 2025-07-17");
        Watched far = row(report, "MUC-CHI 2026-02-01");
        assertThat(near.allowance()).isEqualTo(2);
        assertThat(far.allowance()).isEqualTo(28);
        assertThat(near.cover()).isSameAs(Cover.LATE);
        assertThat(far.cover()).isSameAs(Cover.PRICED);
    }

    @Test
    void aLateSeriesSaysHowLateAgainstWhat() {
        Coverage report = build(schedule, List.of(run(LocalDate.of(2025, 6, 1), answered())), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.why()).isEqualTo("last priced 44 days ago against an allowance of 8");
    }

    @Test
    void aDepartureThatHasGoneIsFinishedAndNotAGap() {
        Schedule schedule = new Schedule();
        schedule.follow(FRA_JFK, LocalDate.of(2025, 6, 1));
        Coverage report = build(schedule, List.of(), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-06-01");
        assertThat(row.cover()).isSameAs(Cover.FLOWN);
        assertThat(row.why()).isEqualTo("flown");
        assertThat(report.gaps()).isEmpty();
        assertThat(report.live()).isEmpty();
    }

    @Test
    void theRateIsMeasuredAgainstTheLiveOnesOnly() {
        Schedule schedule = new Schedule();
        schedule.follow(FRA_JFK, AUGUST);
        schedule.follow(MUC_ORD, AUGUST);
        schedule.follow(FRA_JFK, LocalDate.of(2025, 5, 1));
        Coverage report = build(schedule, List.of(run(answered())), registry, TODAY);
        assertThat(report.size()).isEqualTo(3);
        assertThat(report.live()).hasSize(2);
        assertThat(report.rate()).isEqualByComparingTo("0.50");
    }

    @Test
    void nothingLiveGivesNoRateRatherThanADivision() {
        Schedule schedule = new Schedule();
        schedule.follow(FRA_JFK, LocalDate.of(2025, 5, 1));
        assertThat(build(schedule, List.of(), registry, TODAY).rate()).isEqualByComparingTo("0");
    }

    @Test
    void theLatestAnswerWinsWhateverOrderTheRunsArriveIn() {
        RunReport late = run(LocalDate.of(2025, 7, 14), answered());
        RunReport early = run(LocalDate.of(2025, 6, 1), answered());
        Coverage report = build(schedule, List.of(late, early), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.age()).contains(1);
    }

    @Test
    void aResultForASeriesNobodyFollowsIsDropped() {
        Coverage report = build(schedule, List.of(run(answered(MUC_ORD, AUGUST))), registry, TODAY);
        assertThat(report.size()).isEqualTo(1);
        assertThat(report.get("MUC-CHI 2025-08-20")).isEmpty();
    }

    @Test
    void anAnswerFromASupplierWeDoNotKnowCountsAsAQuote() {
        Coverage report = build(schedule, List.of(run(answered("somebody"))), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.answers()).isEqualTo(1);
        assertThat(row.cover()).isSameAs(Cover.QUOTED);
    }

    @Test
    void theGapsComeBackDarkFirstThenByHowCloseTheDepartureIs() {
        Schedule schedule = new Schedule();
        schedule.follow(FRA_JFK, LocalDate.of(2025, 9, 1));
        schedule.follow(MUC_ORD, LocalDate.of(2025, 7, 20));
        schedule.follow(FRA_JFK, LocalDate.of(2025, 7, 25));
        List<RunReport> reports = List.of(run(LocalDate.of(2025, 6, 1), answered(FRA_JFK, LocalDate.of(2025, 7, 25))));
        Coverage report = build(schedule, reports, registry, TODAY);
        assertThat(report.gaps().stream().map(Watched::cover).toList())
                .containsExactly(Cover.DARK, Cover.DARK, Cover.LATE);
        assertThat(report.gaps().stream().map(Watched::departure).toList().subList(0, 2))
                .containsExactly(LocalDate.of(2025, 7, 20), LocalDate.of(2025, 9, 1));
    }

    @Test
    void onlyLateAndDarkCountAsGaps() {
        assertThat(Cover.DARK.isAGap()).isTrue();
        assertThat(Cover.LATE.isAGap()).isTrue();
        assertThat(Cover.PRICED.isAGap()).isFalse();
        assertThat(Cover.QUOTED.isAGap()).isFalse();
        assertThat(Cover.FLOWN.isAGap()).isFalse();
    }

    @Test
    void aRowReadsBackInOneLine() {
        Coverage report = build(schedule, List.of(run(answered())), registry, TODAY);
        Watched row = row(report, "FRA-NYC 2025-08-20");
        assertThat(row.toString()).isEqualTo("FRA-NYC 2025-08-20 priced priced 0 days ago");
    }

    @Test
    void theReportReadsBackWithTheThreeNumbers() {
        Coverage report = build(schedule, List.of(), registry, TODAY);
        assertThat(report.toString()).isEqualTo("1 live series, 0.00 priced, 1 gaps");
    }

    @Test
    void theRenderedReportCountsEveryGrade() {
        String out = render(build(schedule, List.of(run(answered())), registry, TODAY));
        assertThat(out).contains("coverage on 2025-07-15");
        assertThat(out).contains("followed  1");
        assertThat(out).contains("priced    1");
        assertThat(out).contains("rate      1.00");
    }

    @Test
    void theRenderedReportLeavesTheGapListOutWhenThereAreNone() {
        String out = render(build(schedule, List.of(run(answered())), registry, TODAY));
        assertThat(out).doesNotContain("gaps, worst first");
    }

    @Test
    void theRenderedReportListsTheGapsAndSaysHowManyItShowed() {
        Schedule schedule = new Schedule();
        for (int day = 1; day < 6; day++) {
            schedule.follow(FRA_JFK, LocalDate.of(2025, 9, day));
        }
        String out = render(build(schedule, List.of(), registry, TODAY), 2);
        assertThat(out).contains("showing 2 of 5");
        assertThat(occurrences(out, "2025-09-0")).isEqualTo(2);
    }
}
