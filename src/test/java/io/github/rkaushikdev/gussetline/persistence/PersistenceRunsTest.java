package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import io.github.rkaushikdev.gussetline.reports.Reliability;
import io.github.rkaushikdev.gussetline.reports.Reliability.Grade;
import io.github.rkaushikdev.gussetline.reports.Reliability.Health;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceRunsTest {

    private static final Market FRA_JFK = new Market("FRA", "JFK");
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 24);
    private static final LocalDateTime STARTED = LocalDateTime.of(2025, 7, 20, 6, 0);
    private static final LocalDateTime FINISHED = LocalDateTime.of(2025, 7, 20, 6, 41);

    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
    }

    private static Task task(int daysOut) {
        return new Task("gds", FRA_JFK, AUGUST, daysOut);
    }

    /** A result as a test describes it: one call with six offers in a payload, unless told otherwise. */
    private static Result.Builder result(Outcome outcome) {
        return result(outcome, task(35));
    }

    private static Result.Builder result(Outcome outcome, Task task) {
        return Result.builder(task, outcome).attempts(1).offers(6).digest("d".repeat(32));
    }

    private static Result result() {
        return result(Outcome.OK).build();
    }

    private static RunReport run(Result... results) {
        return run(STARTED, results);
    }

    private static RunReport run(LocalDateTime started, Result... results) {
        return new RunReport(started, List.of(results));
    }

    private static LocalDateTime july(int day) {
        return LocalDateTime.of(2025, 7, day, 6, 0);
    }

    @Test
    void aRunWithNothingInItStillRecords() {
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().record(run());
            work.commit();
            assertThat(row.getId()).isNotNull();
            assertThat(work.runs().count()).isEqualTo(1);
        }
    }

    @Test
    void aRunComesBackTheWayItWentIn() {
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs()
                    .record(run(
                            result(),
                            result(Outcome.TRANSIENT).attempts(2).offers(0).build()));
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            RunReport back = work.runs().get(identifier);
            assertThat(back.size()).isEqualTo(2);
            assertThat(back.started()).isEqualTo(LocalDateTime.of(2025, 7, 20, 6, 0));
            assertThat(back.offers()).isEqualTo(6);
            assertThat(back.results().stream().map(Result::outcome).toList())
                    .containsExactly(Outcome.OK, Outcome.TRANSIENT);
        }
    }

    @Test
    void theTaskComesBackWhole() {
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().record(run(result()));
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            Task back = work.runs().get(identifier).results().get(0).task();
            assertThat(back.supplier()).isEqualTo("gds");
            assertThat(back.market().key()).isEqualTo("FRA-NYC");
            assertThat(back.departure()).isEqualTo(AUGUST);
        }
    }

    @Test
    void daysOutIsWorkedOutAgainRatherThanStored() {
        // A stored copy can disagree with the departure next to it, and nothing
        // afterwards can say which of the two is wrong.
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().record(run(result(Outcome.OK, task(999)).build()));
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().get(identifier).results().get(0).task().daysOut())
                    .isEqualTo(35);
        }
    }

    @Test
    void theTwoReadingsNoStatusCodeShowsSurviveTheRoundTrip() {
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs()
                    .record(run(result(Outcome.OK).fresh(false).problems(3).build()));
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            Result back = work.runs().get(identifier).results().get(0);
            assertThat(back.fresh()).isFalse();
            assertThat(back.problems()).isEqualTo(3);
        }
    }

    @Test
    void theSkipsAreKeptBecauseTheyAreTheDenominator() {
        RunReport report = new RunReport(STARTED, List.of(result()), 4, 2);
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().record(report);
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            RunReport back = work.runs().get(identifier);
            assertThat(back.skippedBreaker()).isEqualTo(4);
            assertThat(back.skippedBudget()).isEqualTo(2);
        }
    }

    @Test
    void aLongReasonIsCutToFitRatherThanRefused() {
        try (UnitOfWork work = database.unit()) {
            work.runs()
                    .record(run(
                            result(Outcome.TRANSIENT).reason("x".repeat(400)).build()));
            work.commit();
            assertThat(work.runs().countResults()).isEqualTo(1);
        }
    }

    @Test
    void aRunThatIsNotThereIsANotFound() {
        try (UnitOfWork work = database.unit()) {
            assertThatThrownBy(() -> work.runs().get(404)).isInstanceOf(NotFoundError.class);
        }
    }

    @Test
    void theRecentRunsComeBackNewestFirst() {
        try (UnitOfWork work = database.unit()) {
            for (int day : new int[] {18, 20, 19}) {
                work.runs().record(run(july(day), result()));
            }
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            List<RunReport> got = work.runs().recent();
            assertThat(got.stream().map(r -> r.started().getDayOfMonth()).toList())
                    .containsExactly(20, 19, 18);
        }
    }

    @Test
    void theRecentRunsCanBeCapped() {
        try (UnitOfWork work = database.unit()) {
            for (int day = 10; day < 20; day++) {
                work.runs().record(run(july(day), result()));
            }
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().recent(3)).hasSize(3);
        }
    }

    @Test
    void theRunsSinceAMomentComeBackOldestFirst() {
        try (UnitOfWork work = database.unit()) {
            for (int day : new int[] {18, 19, 20}) {
                work.runs().record(run(july(day), result()));
            }
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            List<RunReport> got = work.runs().since(LocalDateTime.of(2025, 7, 19, 0, 0));
            assertThat(got.stream().map(r -> r.started().getDayOfMonth()).toList())
                    .containsExactly(19, 20);
        }
    }

    @Test
    void aStoredRunFeedsTheReliabilityReport() {
        try (UnitOfWork work = database.unit()) {
            work.runs()
                    .record(run(
                            result(),
                            result(Outcome.MALFORMED).attempts(1).offers(0).build(),
                            result(Outcome.BLOCKED).attempts(0).offers(0).build()));
            work.commit();
        }

        Reliability report;
        try (UnitOfWork work = database.unit()) {
            report = Reliability.build(work.runs().recent());
        }

        Optional<Health> found = report.get("gds");
        assertThat(found).isPresent();
        Health health = found.get();
        assertThat(health.planned()).isEqualTo(3);
        assertThat(health.attempted()).isEqualTo(2);
        assertThat(health.grade()).isSameAs(Grade.UNFIT);
    }

    @Test
    void pruningTakesTheOldRunsAndTheirResults() {
        try (UnitOfWork work = database.unit()) {
            for (int day : new int[] {1, 10, 20}) {
                work.runs().record(run(july(day), result(), result()));
            }
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            int gone = work.runs().prune(LocalDateTime.of(2025, 7, 15, 0, 0));
            work.commit();
            assertThat(gone).isEqualTo(2);
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().count()).isEqualTo(1);
            assertThat(work.runs().countResults()).isEqualTo(2);
        }
    }

    @Test
    void pruningNothingIsNotAnError() {
        try (UnitOfWork work = database.unit()) {
            work.runs().record(run(result()));
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().prune(LocalDateTime.of(2020, 1, 1, 0, 0))).isZero();
        }
    }

    @Test
    void aRunThatIsNeverCommittedIsNotThere() {
        try (UnitOfWork work = database.unit()) {
            work.runs().record(run(result()));
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().count()).isZero();
        }
    }

    @Test
    void aRunCanBeOpenedBeforeItHasDoneAnything() {
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().open(STARTED);
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            RunRow found = work.runs().row(identifier);
            assertThat(found.getStartedAt()).isEqualTo(STARTED);
            assertThat(found.getFinishedAt()).isEmpty();
            assertThat(found.getTasks()).isZero();
        }
    }

    @Test
    void anOpenedRunIsThereAfterTheProcessThatOpenedItIsGone() {
        // The whole point. A machine that reboots at three leaves a row saying
        // the desk started polling, which is what nothing recorded before.
        try (UnitOfWork work = database.unit()) {
            work.runs().open(STARTED);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished().stream()
                            .map(RunRow::getStartedAt)
                            .toList())
                    .containsExactly(STARTED);
        }
    }

    @Test
    void closingARunFillsInWhatItTurnedOutToBe() {
        RunReport report = run(result(), result(Outcome.TRANSIENT).offers(0).build());
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().open(STARTED);
            work.runs().close(row, report, FINISHED);
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            RunRow found = work.runs().row(identifier);
            assertThat(found.getFinishedAt()).contains(FINISHED);
            assertThat(found.getTasks()).isEqualTo(2);
            assertThat(found.getCalls()).isEqualTo(report.calls());
            assertThat(found.getResults()).hasSize(2);
        }
    }

    @Test
    void aClosedRunIsNoLongerUnfinished() {
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().open(STARTED);
            work.runs().close(row, run(result()), FINISHED);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished()).isEmpty();
        }
    }

    @Test
    void closingARunTwiceDoesNotDoubleItsResults() {
        RunReport report = run(result(), result());
        int identifier;
        try (UnitOfWork work = database.unit()) {
            RunRow row = work.runs().open(STARTED);
            work.runs().close(row, report, FINISHED);
            work.runs().close(row, report, FINISHED);
            work.commit();
            identifier = row.getId();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().row(identifier).getResults()).hasSize(2);
        }
    }

    @Test
    void theUnfinishedRunsComeBackNewestFirst() {
        try (UnitOfWork work = database.unit()) {
            work.runs().open(STARTED);
            work.runs().open(FINISHED);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished().stream()
                            .map(RunRow::getStartedAt)
                            .toList())
                    .containsExactly(FINISHED, STARTED);
        }
    }

    @Test
    void aFinishedRunAndAnOpenOneAreToldApart() {
        try (UnitOfWork work = database.unit()) {
            RunRow done = work.runs().open(STARTED);
            work.runs().close(done, run(result()), FINISHED);
            work.runs().open(FINISHED);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished()).hasSize(1);
            assertThat(work.runs().count()).isEqualTo(2);
        }
    }

    @Test
    void aRunThatWasNeverOpenedIsNotFound() {
        try (UnitOfWork work = database.unit()) {
            assertThatThrownBy(() -> work.runs().row(999)).isInstanceOf(NotFoundError.class);
        }
    }
}
