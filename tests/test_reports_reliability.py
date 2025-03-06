from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from gussetline.collect.registry import Outcome
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Task
from gussetline.reports.reliability import (
    Grade,
    Health,
    build,
    render,
)

STARTED = datetime(2025, 7, 2, 6, 0)
FRA_JFK = Market("FRA", "JFK")


def task(supplier="gds") -> Task:
    return Task(supplier=supplier, market=FRA_JFK, departure=date(2025, 8, 4), days_out=33)


def result(outcome=Outcome.OK, supplier="gds", attempts=1, **over) -> Result:
    return Result(task=task(supplier), outcome=outcome, attempts=attempts, **over)


def run(*results: Result, started=STARTED) -> RunReport:
    return RunReport(started=started, results=list(results))


def test_nothing_at_all_reads_as_nothing():
    report = build([])
    assert report.runs == 0
    assert len(report) == 0
    assert report.worst is None
    assert report.first is None


def test_the_window_runs_from_the_first_run_to_the_last():
    later = datetime(2025, 7, 4, 6, 0)
    report = build([run(result()), run(result(), started=later)])
    assert report.first == STARTED
    assert report.last == later
    assert report.runs == 2


def test_a_supplier_that_answers_everything_is_sound():
    report = build([run(result(), result(), result())])
    health = report.get("gds")
    assert health is not None
    assert health.answer_rate == Decimal("1.00")
    assert health.grade() is Grade.SOUND


def test_the_rate_is_measured_against_the_calls_that_went_out():
    # Two calls, both answered. Three tasks skipped on an open breaker, which
    # say nothing about the far end and must not drag the rate down.
    skipped = [result(Outcome.BLOCKED, attempts=0) for _ in range(3)]
    report = build([run(result(), result(), *skipped)])
    health = report.get("gds")
    assert health is not None
    assert health.attempted == 2
    assert health.answer_rate == Decimal("1.00")
    assert health.skipped == 3


def test_how_much_of_the_plan_got_out_of_the_door_is_reported_separately():
    report = build([run(result(), result(Outcome.BUDGET, attempts=0))])
    health = report.get("gds")
    assert health is not None
    assert health.reach_rate == Decimal("0.50")
    assert health.budget == 1


def test_a_supplier_that_was_never_called_has_no_reading():
    report = build([run(result(Outcome.BLOCKED, attempts=0))])
    health = report.get("gds")
    assert health is not None
    assert not health.has_evidence
    assert health.answer_rate == Decimal(0)
    assert health.reach_rate == Decimal(0)


def test_throttling_is_ours_and_does_not_count_against_the_supplier():
    report = build([run(result(), result(Outcome.THROTTLED, attempts=0))])
    health = report.get("gds")
    assert health is not None
    assert health.throttled == 1
    assert health.answer_rate == Decimal("1.00")
    assert health.grade() is Grade.SOUND


def test_a_blocked_supplier_is_unfit_even_if_the_rest_answered():
    report = build([run(result(), result(), result(Outcome.BLOCKED))])
    health = report.get("gds")
    assert health is not None
    assert health.grade() is Grade.UNFIT
    assert health.why() == "blocked"


def test_malformed_payloads_grade_a_supplier_down_though_they_are_two_hundreds():
    # The whole point. These calls answered, so every layer below counts them
    # as fine, and the desk gets nothing out of them.
    report = build([run(result(), result(), result(), result(Outcome.MALFORMED))])
    health = report.get("gds")
    assert health is not None
    assert health.grade() is Grade.WATCH
    assert "did not parse" in health.why()


def test_mostly_malformed_is_unfit_and_not_merely_watched():
    report = build([run(result(), result(Outcome.MALFORMED), result(Outcome.MALFORMED))])
    health = report.get("gds")
    assert health is not None
    assert health.grade() is Grade.UNFIT


def test_a_half_answering_supplier_is_unfit():
    failures = [result(Outcome.TRANSIENT) for _ in range(3)]
    report = build([run(result(), *failures)])
    health = report.get("gds")
    assert health is not None
    assert health.answer_rate == Decimal("0.25")
    assert health.grade() is Grade.UNFIT


def test_a_mostly_answering_supplier_is_watched():
    good = [result() for _ in range(8)]
    report = build([run(*good, result(Outcome.TRANSIENT), result(Outcome.TRANSIENT))])
    health = report.get("gds")
    assert health is not None
    assert health.answer_rate == Decimal("0.80")
    assert health.grade() is Grade.WATCH
    assert health.why() == "answered 8 of 10"


def test_the_same_bytes_coming_back_is_noticed_though_nothing_failed():
    stale = [result(fresh=False) for _ in range(4)]
    report = build([run(*stale)])
    health = report.get("gds")
    assert health is not None
    assert health.repeats == 4
    assert health.repeat_rate == Decimal("1.00")
    assert health.grade() is Grade.WATCH
    assert health.why() == "serving the same bytes back"


def test_a_couple_of_repeats_is_not_enough_to_call_it():
    report = build([run(result(fresh=False), result(fresh=False))])
    health = report.get("gds")
    assert health is not None
    assert health.grade() is Grade.SOUND


def test_only_answered_calls_can_repeat():
    report = build([run(result(Outcome.TRANSIENT, fresh=False))])
    health = report.get("gds")
    assert health is not None
    assert health.repeats == 0


def test_the_suppliers_come_back_in_a_settled_order():
    report = build([run(result(supplier="ndc"), result(supplier="calendar"), result())])
    assert [h.name for h in report.suppliers] == ["calendar", "gds", "ndc"]


def test_a_supplier_counts_a_run_once_however_many_tasks_it_had():
    report = build([run(result(), result(), result()), run(result())])
    health = report.get("gds")
    assert health is not None
    assert health.runs == 2
    assert health.planned == 4


def test_a_run_a_supplier_was_not_in_is_not_one_of_its_runs():
    report = build([run(result()), run(result(supplier="ndc"))])
    gds = report.get("gds")
    assert gds is not None
    assert gds.runs == 1
    assert report.runs == 2


def test_the_offers_and_the_problems_are_carried_through():
    report = build([run(result(offers=12, problems=2), result(offers=8))])
    health = report.get("gds")
    assert health is not None
    assert health.offers == 20
    assert health.problems == 2


def test_the_ones_wanting_a_human_are_the_ones_that_are_not_sound():
    report = build(
        [
            run(
                result(),
                result(supplier="ndc", outcome=Outcome.BLOCKED),
                result(supplier="calendar", outcome=Outcome.MALFORMED),
            )
        ]
    )
    assert [h.name for h in report.needing_a_human()] == ["calendar", "ndc"]


def test_the_worst_one_is_the_one_to_look_at_first():
    report = build([run(result(), result(supplier="ndc", outcome=Outcome.BLOCKED))])
    worst = report.worst
    assert worst is not None
    assert worst.name == "ndc"


def test_a_watched_one_is_worse_than_a_sound_one_but_better_than_an_unfit_one():
    watched = Health(name="a", attempted=10, answered=8)
    unfit = Health(name="b", attempted=10, answered=1)
    sound = Health(name="c", attempted=10, answered=10)
    assert watched.grade() is Grade.WATCH
    assert unfit.grade() is Grade.UNFIT
    assert sound.grade() is Grade.SOUND


def test_a_grade_says_whether_anybody_needs_telling():
    assert not Grade.SOUND.wants_a_human
    assert Grade.WATCH.wants_a_human
    assert Grade.UNFIT.wants_a_human


def test_an_unknown_supplier_is_simply_not_there():
    assert build([run(result())]).get("nobody") is None


def test_a_reading_reads_back_in_one_line():
    report = build([run(result())])
    health = report.get("gds")
    assert health is not None
    assert str(health) == "gds        sound  1.00 answering"


def test_the_report_reads_back_with_the_count_that_matters():
    report = build([run(result(), result(supplier="ndc", outcome=Outcome.BLOCKED))])
    assert str(report) == "1 runs, 2 suppliers, 1 wanting a look"


def test_the_rendered_report_names_the_window_and_the_counts():
    out = render(build([run(result(), result(Outcome.THROTTLED, attempts=0))]))
    assert "reliability over 1 runs" in out
    assert "2025-07-02 06:00" in out
    assert "planned 2 called 1 answered 1" in out
    assert "our pacing and not theirs" in out


def test_the_rendered_report_says_so_when_nothing_ran():
    assert "nothing ran" in render(build([]))


def test_the_rendered_report_mentions_the_payload_problems():
    out = render(build([run(result(offers=4, problems=3))]))
    assert "3 offers in the payloads did not parse" in out


def test_the_rendered_report_mentions_the_budget_skips():
    out = render(build([run(result(), result(Outcome.BUDGET, attempts=0))]))
    assert "1 skipped on the budget" in out
