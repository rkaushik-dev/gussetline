package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.reports.Reliability.build;
import static io.github.rkaushikdev.gussetline.reports.Reliability.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.reports.Reliability.Grade;
import io.github.rkaushikdev.gussetline.reports.Reliability.Health;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportsReliabilityTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2025, 7, 2, 6, 0);
    private static final Market FRA_JFK = new Market("FRA", "JFK");

    private static Task task(String supplier) {
        return new Task(supplier, FRA_JFK, LocalDate.of(2025, 8, 4), 33);
    }

    /** A result as a test describes it: one call to gds that answered, unless told otherwise. */
    private static Result.Builder call(Outcome outcome, String supplier, int attempts) {
        return Result.builder(task(supplier), outcome).attempts(attempts);
    }

    private static Result.Builder call() {
        return call(Outcome.OK, "gds", 1);
    }

    private static Result result() {
        return call().build();
    }

    private static Result result(Outcome outcome) {
        return call(outcome, "gds", 1).build();
    }

    private static Result result(String supplier) {
        return call(Outcome.OK, supplier, 1).build();
    }

    private static Result result(String supplier, Outcome outcome) {
        return call(outcome, supplier, 1).build();
    }

    /** A task that never sent a call. */
    private static Result skipped(Outcome outcome) {
        return call(outcome, "gds", 0).build();
    }

    private static List<Result> times(int count, Result each) {
        return Collections.nCopies(count, each);
    }

    private static RunReport run(Result... results) {
        return run(STARTED, results);
    }

    private static RunReport run(LocalDateTime started, Result... results) {
        return new RunReport(started, List.of(results));
    }

    private static RunReport run(List<Result> results) {
        return new RunReport(STARTED, results);
    }

    /** The reading for a supplier, which has to be there. */
    private static Health health(Reliability report, String name) {
        Optional<Health> found = report.get(name);
        assertThat(found).isPresent();
        return found.get();
    }

    @Test
    void nothingAtAllReadsAsNothing() {
        Reliability report = build(List.of());
        assertThat(report.runs()).isZero();
        assertThat(report.size()).isZero();
        assertThat(report.worst()).isEmpty();
        assertThat(report.first()).isEmpty();
    }

    @Test
    void theWindowRunsFromTheFirstRunToTheLast() {
        LocalDateTime later = LocalDateTime.of(2025, 7, 4, 6, 0);
        Reliability report = build(List.of(run(result()), run(later, result())));
        assertThat(report.first()).contains(STARTED);
        assertThat(report.last()).contains(later);
        assertThat(report.runs()).isEqualTo(2);
    }

    @Test
    void aSupplierThatAnswersEverythingIsSound() {
        Reliability report = build(List.of(run(result(), result(), result())));
        Health health = health(report, "gds");
        assertThat(health.answerRate()).isEqualByComparingTo("1.00");
        assertThat(health.grade()).isSameAs(Grade.SOUND);
    }

    @Test
    void theRateIsMeasuredAgainstTheCallsThatWentOut() {
        // Two calls, both answered. Three tasks skipped on an open breaker, which
        // say nothing about the far end and must not drag the rate down.
        List<Result> results = new ArrayList<>(List.of(result(), result()));
        results.addAll(times(3, skipped(Outcome.BLOCKED)));
        Reliability report = build(List.of(run(results)));
        Health health = health(report, "gds");
        assertThat(health.attempted()).isEqualTo(2);
        assertThat(health.answerRate()).isEqualByComparingTo("1.00");
        assertThat(health.skipped()).isEqualTo(3);
    }

    @Test
    void howMuchOfThePlanGotOutOfTheDoorIsReportedSeparately() {
        Reliability report = build(List.of(run(result(), skipped(Outcome.BUDGET))));
        Health health = health(report, "gds");
        assertThat(health.reachRate()).isEqualByComparingTo("0.50");
        assertThat(health.budget()).isEqualTo(1);
    }

    @Test
    void aSupplierThatWasNeverCalledHasNoReading() {
        Reliability report = build(List.of(run(skipped(Outcome.BLOCKED))));
        Health health = health(report, "gds");
        assertThat(health.hasEvidence()).isFalse();
        assertThat(health.answerRate()).isEqualByComparingTo("0");
        assertThat(health.reachRate()).isEqualByComparingTo("0");
    }

    @Test
    void throttlingIsOursAndDoesNotCountAgainstTheSupplier() {
        Reliability report = build(List.of(run(result(), skipped(Outcome.THROTTLED))));
        Health health = health(report, "gds");
        assertThat(health.throttled()).isEqualTo(1);
        assertThat(health.answerRate()).isEqualByComparingTo("1.00");
        assertThat(health.grade()).isSameAs(Grade.SOUND);
    }

    @Test
    void aBlockedSupplierIsUnfitEvenIfTheRestAnswered() {
        Reliability report = build(List.of(run(result(), result(), result(Outcome.BLOCKED))));
        Health health = health(report, "gds");
        assertThat(health.grade()).isSameAs(Grade.UNFIT);
        assertThat(health.why()).isEqualTo("blocked");
    }

    @Test
    void malformedPayloadsGradeASupplierDownThoughTheyAreTwoHundreds() {
        // The whole point. These calls answered, so every layer below counts them
        // as fine, and the desk gets nothing out of them.
        Reliability report = build(List.of(run(result(), result(), result(), result(Outcome.MALFORMED))));
        Health health = health(report, "gds");
        assertThat(health.grade()).isSameAs(Grade.WATCH);
        assertThat(health.why()).contains("did not parse");
    }

    @Test
    void mostlyMalformedIsUnfitAndNotMerelyWatched() {
        Reliability report = build(List.of(run(result(), result(Outcome.MALFORMED), result(Outcome.MALFORMED))));
        Health health = health(report, "gds");
        assertThat(health.grade()).isSameAs(Grade.UNFIT);
    }

    @Test
    void aHalfAnsweringSupplierIsUnfit() {
        List<Result> results = new ArrayList<>(List.of(result()));
        results.addAll(times(3, result(Outcome.TRANSIENT)));
        Reliability report = build(List.of(run(results)));
        Health health = health(report, "gds");
        assertThat(health.answerRate()).isEqualByComparingTo("0.25");
        assertThat(health.grade()).isSameAs(Grade.UNFIT);
    }

    @Test
    void aMostlyAnsweringSupplierIsWatched() {
        List<Result> results = new ArrayList<>(times(8, result()));
        results.addAll(List.of(result(Outcome.TRANSIENT), result(Outcome.TRANSIENT)));
        Reliability report = build(List.of(run(results)));
        Health health = health(report, "gds");
        assertThat(health.answerRate()).isEqualByComparingTo("0.80");
        assertThat(health.grade()).isSameAs(Grade.WATCH);
        assertThat(health.why()).isEqualTo("answered 8 of 10");
    }

    @Test
    void theSameBytesComingBackIsNoticedThoughNothingFailed() {
        List<Result> stale = times(4, call().fresh(false).build());
        Reliability report = build(List.of(run(stale)));
        Health health = health(report, "gds");
        assertThat(health.repeats()).isEqualTo(4);
        assertThat(health.repeatRate()).isEqualByComparingTo("1.00");
        assertThat(health.grade()).isSameAs(Grade.WATCH);
        assertThat(health.why()).isEqualTo("serving the same bytes back");
    }

    @Test
    void aCoupleOfRepeatsIsNotEnoughToCallIt() {
        Reliability report = build(
                List.of(run(call().fresh(false).build(), call().fresh(false).build())));
        Health health = health(report, "gds");
        assertThat(health.grade()).isSameAs(Grade.SOUND);
    }

    @Test
    void onlyAnsweredCallsCanRepeat() {
        Reliability report =
                build(List.of(run(call(Outcome.TRANSIENT, "gds", 1).fresh(false).build())));
        Health health = health(report, "gds");
        assertThat(health.repeats()).isZero();
    }

    @Test
    void theSuppliersComeBackInASettledOrder() {
        Reliability report = build(List.of(run(result("ndc"), result("calendar"), result())));
        assertThat(report.suppliers().stream().map(Health::name).toList()).containsExactly("calendar", "gds", "ndc");
    }

    @Test
    void aSupplierCountsARunOnceHoweverManyTasksItHad() {
        Reliability report = build(List.of(run(result(), result(), result()), run(result())));
        Health health = health(report, "gds");
        assertThat(health.runs()).isEqualTo(2);
        assertThat(health.planned()).isEqualTo(4);
    }

    @Test
    void aRunASupplierWasNotInIsNotOneOfItsRuns() {
        Reliability report = build(List.of(run(result()), run(result("ndc"))));
        Health gds = health(report, "gds");
        assertThat(gds.runs()).isEqualTo(1);
        assertThat(report.runs()).isEqualTo(2);
    }

    @Test
    void theOffersAndTheProblemsAreCarriedThrough() {
        Reliability report = build(List.of(
                run(call().offers(12).problems(2).build(), call().offers(8).build())));
        Health health = health(report, "gds");
        assertThat(health.offers()).isEqualTo(20);
        assertThat(health.problems()).isEqualTo(2);
    }

    @Test
    void theOnesWantingAHumanAreTheOnesThatAreNotSound() {
        Reliability report =
                build(List.of(run(result(), result("ndc", Outcome.BLOCKED), result("calendar", Outcome.MALFORMED))));
        assertThat(report.needingAHuman().stream().map(Health::name).toList()).containsExactly("calendar", "ndc");
    }

    @Test
    void theWorstOneIsTheOneToLookAtFirst() {
        Reliability report = build(List.of(run(result(), result("ndc", Outcome.BLOCKED))));
        Optional<Health> worst = report.worst();
        assertThat(worst).isPresent();
        assertThat(worst.get().name()).isEqualTo("ndc");
    }

    @Test
    void aWatchedOneIsWorseThanASoundOneButBetterThanAnUnfitOne() {
        Health watched = Health.builder("a").attempted(10).answered(8).build();
        Health unfit = Health.builder("b").attempted(10).answered(1).build();
        Health sound = Health.builder("c").attempted(10).answered(10).build();
        assertThat(watched.grade()).isSameAs(Grade.WATCH);
        assertThat(unfit.grade()).isSameAs(Grade.UNFIT);
        assertThat(sound.grade()).isSameAs(Grade.SOUND);
    }

    @Test
    void aGradeSaysWhetherAnybodyNeedsTelling() {
        assertThat(Grade.SOUND.wantsAHuman()).isFalse();
        assertThat(Grade.WATCH.wantsAHuman()).isTrue();
        assertThat(Grade.UNFIT.wantsAHuman()).isTrue();
    }

    @Test
    void anUnknownSupplierIsSimplyNotThere() {
        assertThat(build(List.of(run(result()))).get("nobody")).isEmpty();
    }

    @Test
    void aReadingReadsBackInOneLine() {
        Reliability report = build(List.of(run(result())));
        Health health = health(report, "gds");
        assertThat(health.toString()).isEqualTo("gds        sound  1.00 answering");
    }

    @Test
    void theReportReadsBackWithTheCountThatMatters() {
        Reliability report = build(List.of(run(result(), result("ndc", Outcome.BLOCKED))));
        assertThat(report.toString()).isEqualTo("1 runs, 2 suppliers, 1 wanting a look");
    }

    @Test
    void theRenderedReportNamesTheWindowAndTheCounts() {
        String out = render(build(List.of(run(result(), skipped(Outcome.THROTTLED)))));
        assertThat(out).contains("reliability over 1 runs");
        assertThat(out).contains("2025-07-02 06:00");
        assertThat(out).contains("planned 2 called 1 answered 1");
        assertThat(out).contains("our pacing and not theirs");
    }

    @Test
    void theRenderedReportSaysSoWhenNothingRan() {
        assertThat(render(build(List.of()))).contains("nothing ran");
    }

    @Test
    void theRenderedReportMentionsThePayloadProblems() {
        String out = render(build(List.of(run(call().offers(4).problems(3).build()))));
        assertThat(out).contains("3 offers in the payloads did not parse");
    }

    @Test
    void theRenderedReportMentionsTheBudgetSkips() {
        String out = render(build(List.of(run(result(), skipped(Outcome.BUDGET)))));
        assertThat(out).contains("1 skipped on the budget");
    }
}
