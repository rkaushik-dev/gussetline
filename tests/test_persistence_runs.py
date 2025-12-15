from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.collect.registry import Outcome
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Task
from gussetline.errors import NotFoundError
from gussetline.persistence.unitofwork import in_memory
from gussetline.reports.reliability import Grade
from gussetline.reports.reliability import build as reliability

FRA_JFK = Market("FRA", "JFK")
AUGUST = date(2025, 8, 24)
STARTED = datetime(2025, 7, 20, 6, 0)
FINISHED = datetime(2025, 7, 20, 6, 41)


@pytest.fixture
def database():
    return in_memory()


def task(supplier="gds", market=FRA_JFK, departure=AUGUST, days_out=35) -> Task:
    return Task(supplier=supplier, market=market, departure=departure, days_out=days_out)


def result(outcome=Outcome.OK, **over) -> Result:
    made = {"attempts": 1, "offers": 6, "digest": "d" * 32}
    made.update(over)
    return Result(task=made.pop("task", task()), outcome=outcome, **made)


def run(*results: Result, started=datetime(2025, 7, 20, 6, 0)) -> RunReport:
    return RunReport(started=started, results=list(results))


def test_a_run_with_nothing_in_it_still_records(database):
    with database.unit() as work:
        row = work.runs.record(run())
        work.commit()
        assert row.id is not None
        assert work.runs.count() == 1


def test_a_run_comes_back_the_way_it_went_in(database):
    with database.unit() as work:
        row = work.runs.record(run(result(), result(Outcome.TRANSIENT, attempts=2, offers=0)))
        work.commit()
        identifier = row.id

    with database.unit() as work:
        back = work.runs.get(identifier)
        assert len(back) == 2
        assert back.started == datetime(2025, 7, 20, 6, 0)
        assert back.offers == 6
        assert [r.outcome for r in back.results] == [Outcome.OK, Outcome.TRANSIENT]


def test_the_task_comes_back_whole(database):
    with database.unit() as work:
        row = work.runs.record(run(result()))
        work.commit()
        identifier = row.id

    with database.unit() as work:
        back = work.runs.get(identifier).results[0].task
        assert back.supplier == "gds"
        assert back.market.key == "FRA-NYC"
        assert back.departure == AUGUST


def test_days_out_is_worked_out_again_rather_than_stored(database):
    # A stored copy can disagree with the departure next to it, and nothing
    # afterwards can say which of the two is wrong.
    with database.unit() as work:
        row = work.runs.record(run(result(task=task(days_out=999))))
        work.commit()
        identifier = row.id

    with database.unit() as work:
        assert work.runs.get(identifier).results[0].task.days_out == 35


def test_the_two_readings_no_status_code_shows_survive_the_round_trip(database):
    with database.unit() as work:
        row = work.runs.record(run(result(fresh=False, problems=3)))
        work.commit()
        identifier = row.id

    with database.unit() as work:
        back = work.runs.get(identifier).results[0]
        assert not back.fresh
        assert back.problems == 3


def test_the_skips_are_kept_because_they_are_the_denominator(database):
    report = run(result())
    report.skipped_breaker = 4
    report.skipped_budget = 2
    with database.unit() as work:
        row = work.runs.record(report)
        work.commit()
        identifier = row.id

    with database.unit() as work:
        back = work.runs.get(identifier)
        assert back.skipped_breaker == 4
        assert back.skipped_budget == 2


def test_a_long_reason_is_cut_to_fit_rather_than_refused(database):
    with database.unit() as work:
        work.runs.record(run(result(Outcome.TRANSIENT, reason="x" * 400)))
        work.commit()
        assert work.runs.count_results() == 1


def test_a_run_that_is_not_there_is_a_not_found(database):
    with database.unit() as work, pytest.raises(NotFoundError):
        work.runs.get(404)


def test_the_recent_runs_come_back_newest_first(database):
    with database.unit() as work:
        for day in (18, 20, 19):
            work.runs.record(run(result(), started=datetime(2025, 7, day, 6, 0)))
        work.commit()

    with database.unit() as work:
        got = work.runs.recent()
        assert [r.started.day for r in got] == [20, 19, 18]


def test_the_recent_runs_can_be_capped(database):
    with database.unit() as work:
        for day in range(10, 20):
            work.runs.record(run(result(), started=datetime(2025, 7, day, 6, 0)))
        work.commit()

    with database.unit() as work:
        assert len(work.runs.recent(limit=3)) == 3


def test_the_runs_since_a_moment_come_back_oldest_first(database):
    with database.unit() as work:
        for day in (18, 19, 20):
            work.runs.record(run(result(), started=datetime(2025, 7, day, 6, 0)))
        work.commit()

    with database.unit() as work:
        got = work.runs.since(datetime(2025, 7, 19, 0, 0))
        assert [r.started.day for r in got] == [19, 20]


def test_a_stored_run_feeds_the_reliability_report(database):
    with database.unit() as work:
        work.runs.record(
            run(
                result(),
                result(Outcome.MALFORMED, attempts=1, offers=0),
                result(Outcome.BLOCKED, attempts=0, offers=0),
            )
        )
        work.commit()

    with database.unit() as work:
        report = reliability(work.runs.recent())

    health = report.get("gds")
    assert health is not None
    assert health.planned == 3
    assert health.attempted == 2
    assert health.grade() is Grade.UNFIT


def test_pruning_takes_the_old_runs_and_their_results(database):
    with database.unit() as work:
        for day in (1, 10, 20):
            work.runs.record(run(result(), result(), started=datetime(2025, 7, day, 6, 0)))
        work.commit()

    with database.unit() as work:
        gone = work.runs.prune(datetime(2025, 7, 15, 0, 0))
        work.commit()
        assert gone == 2

    with database.unit() as work:
        assert work.runs.count() == 1
        assert work.runs.count_results() == 2


def test_pruning_nothing_is_not_an_error(database):
    with database.unit() as work:
        work.runs.record(run(result()))
        work.commit()

    with database.unit() as work:
        assert work.runs.prune(datetime(2020, 1, 1)) == 0


def test_a_run_that_is_never_committed_is_not_there(database):
    with database.unit() as work:
        work.runs.record(run(result()))

    with database.unit() as work:
        assert work.runs.count() == 0


def test_a_run_can_be_opened_before_it_has_done_anything(database):
    with database.unit() as work:
        row = work.runs.open(STARTED)
        work.commit()
        identifier = row.id

    with database.unit() as work:
        found = work.runs.row(identifier)
        assert found.started_at == STARTED
        assert found.finished_at is None
        assert found.tasks == 0


def test_an_opened_run_is_there_after_the_process_that_opened_it_is_gone(database):
    # The whole point. A machine that reboots at three leaves a row saying
    # the desk started polling, which is what nothing recorded before.
    with database.unit() as work:
        work.runs.open(STARTED)
        work.commit()

    with database.unit() as work:
        assert [row.started_at for row in work.runs.unfinished()] == [STARTED]


def test_closing_a_run_fills_in_what_it_turned_out_to_be(database):
    report = run(result(), result(Outcome.TRANSIENT, offers=0))
    with database.unit() as work:
        row = work.runs.open(STARTED)
        work.runs.close(row, report, FINISHED)
        work.commit()
        identifier = row.id

    with database.unit() as work:
        found = work.runs.row(identifier)
        assert found.finished_at == FINISHED
        assert found.tasks == 2
        assert found.calls == report.calls
        assert len(found.results) == 2


def test_a_closed_run_is_no_longer_unfinished(database):
    with database.unit() as work:
        row = work.runs.open(STARTED)
        work.runs.close(row, run(result()), FINISHED)
        work.commit()

    with database.unit() as work:
        assert work.runs.unfinished() == []


def test_closing_a_run_twice_does_not_double_its_results(database):
    report = run(result(), result())
    with database.unit() as work:
        row = work.runs.open(STARTED)
        work.runs.close(row, report, FINISHED)
        work.runs.close(row, report, FINISHED)
        work.commit()
        identifier = row.id

    with database.unit() as work:
        assert len(work.runs.row(identifier).results) == 2


def test_the_unfinished_runs_come_back_newest_first(database):
    with database.unit() as work:
        work.runs.open(STARTED)
        work.runs.open(FINISHED)
        work.commit()

    with database.unit() as work:
        assert [row.started_at for row in work.runs.unfinished()] == [FINISHED, STARTED]


def test_a_finished_run_and_an_open_one_are_told_apart(database):
    with database.unit() as work:
        done = work.runs.open(STARTED)
        work.runs.close(done, run(result()), FINISHED)
        work.runs.open(FINISHED)
        work.commit()

    with database.unit() as work:
        assert len(work.runs.unfinished()) == 1
        assert work.runs.count() == 2


def test_a_run_that_was_never_opened_is_not_found(database):
    with database.unit() as work, pytest.raises(NotFoundError):
        work.runs.row(999)
