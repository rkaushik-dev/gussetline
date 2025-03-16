from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from gussetline.collect.registry import Outcome, default_registry
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Schedule, Task
from gussetline.reports.coverage import Cover, build, render

TODAY = date(2025, 7, 15)
AUGUST = date(2025, 8, 20)
FRA_JFK = Market("FRA", "JFK")
MUC_ORD = Market("MUC", "ORD")


@pytest.fixture
def registry():
    return default_registry()


@pytest.fixture
def schedule():
    made = Schedule()
    made.follow(FRA_JFK, AUGUST)
    return made


def task(market=FRA_JFK, departure=AUGUST, supplier="gds") -> Task:
    return Task(
        supplier=supplier,
        market=market,
        departure=departure,
        days_out=(departure - TODAY).days,
    )


def run(*results: Result, on: date = TODAY) -> RunReport:
    return RunReport(started=datetime(on.year, on.month, on.day, 6, 0), results=list(results))


def answered(market=FRA_JFK, departure=AUGUST, supplier="gds") -> Result:
    return Result(task=task(market, departure, supplier), outcome=Outcome.OK, offers=6, attempts=1)


def failed(market=FRA_JFK, departure=AUGUST, supplier="gds") -> Result:
    return Result(task=task(market, departure, supplier), outcome=Outcome.TRANSIENT, attempts=1)


def test_an_empty_schedule_covers_nothing_and_says_so(registry):
    report = build(Schedule(), [], registry, TODAY)
    assert len(report) == 0
    assert report.rate == Decimal(0)
    assert report.gaps() == []


def test_a_series_nobody_polled_is_dark(schedule, registry):
    report = build(schedule, [], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.cover() is Cover.DARK
    assert row.polls == 0


def test_a_series_polled_with_nothing_coming_back_is_still_dark(schedule, registry):
    report = build(schedule, [run(failed(), failed())], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.cover() is Cover.DARK
    assert row.polls == 2
    assert row.answers == 0
    assert row.why() == "2 polls and nothing answered"


def test_a_series_a_pricing_supplier_answered_for_today_is_priced(schedule, registry):
    report = build(schedule, [run(answered())], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.cover() is Cover.PRICED
    assert row.age == 0
    assert row.priced_answers == 1


def test_only_the_calendar_answering_leaves_the_series_quoted(schedule, registry):
    # The calendar covers markets the other two do not, and none of its
    # answers can be repriced, so this is not coverage the desk can use.
    report = build(schedule, [run(answered(supplier="calendar"))], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.cover() is Cover.QUOTED
    assert row.answers == 1
    assert row.priced_answers == 0
    assert row.why() == "quotes only, nothing that can be repriced"


def test_a_quote_does_not_keep_a_priced_series_fresh(schedule, registry):
    old = run(answered(), on=date(2025, 6, 1))
    recent = run(answered(supplier="calendar"), on=TODAY)
    report = build(schedule, [old, recent], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.cover() is Cover.LATE


def test_an_answer_inside_the_allowance_is_not_late(schedule, registry):
    # August 20 is 36 days out, which the schedule polls every 4 days, so an
    # answer 6 days old is well inside twice that.
    report = build(schedule, [run(answered(), on=date(2025, 7, 9))], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.interval == 4
    assert row.allowance == 8
    assert row.age == 6
    assert row.cover() is Cover.PRICED


def test_the_allowance_follows_the_series_and_not_a_global_number(registry):
    # Same age of answer, two departures. The near one is late and the far
    # one is not, which is the entire reason the allowance is per series.
    schedule = Schedule()
    schedule.follow(FRA_JFK, date(2025, 7, 17))
    schedule.follow(MUC_ORD, date(2026, 2, 1))
    reports = [
        run(
            answered(FRA_JFK, date(2025, 7, 17)),
            answered(MUC_ORD, date(2026, 2, 1)),
            on=date(2025, 7, 8),
        )
    ]
    report = build(schedule, reports, registry, TODAY)
    near = report.get("FRA-NYC 2025-07-17")
    far = report.get("MUC-CHI 2026-02-01")
    assert near is not None and far is not None
    assert near.allowance == 2
    assert far.allowance == 28
    assert near.cover() is Cover.LATE
    assert far.cover() is Cover.PRICED


def test_a_late_series_says_how_late_against_what(schedule, registry):
    report = build(schedule, [run(answered(), on=date(2025, 6, 1))], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.why() == "last priced 44 days ago against an allowance of 8"


def test_a_departure_that_has_gone_is_finished_and_not_a_gap(registry):
    schedule = Schedule()
    schedule.follow(FRA_JFK, date(2025, 6, 1))
    report = build(schedule, [], registry, TODAY)
    row = report.get("FRA-NYC 2025-06-01")
    assert row is not None
    assert row.cover() is Cover.FLOWN
    assert row.why() == "flown"
    assert report.gaps() == []
    assert report.live == []


def test_the_rate_is_measured_against_the_live_ones_only(registry):
    schedule = Schedule()
    schedule.follow(FRA_JFK, AUGUST)
    schedule.follow(MUC_ORD, AUGUST)
    schedule.follow(FRA_JFK, date(2025, 5, 1))
    report = build(schedule, [run(answered())], registry, TODAY)
    assert len(report) == 3
    assert len(report.live) == 2
    assert report.rate == Decimal("0.50")


def test_nothing_live_gives_no_rate_rather_than_a_division(registry):
    schedule = Schedule()
    schedule.follow(FRA_JFK, date(2025, 5, 1))
    assert build(schedule, [], registry, TODAY).rate == Decimal(0)


def test_the_latest_answer_wins_whatever_order_the_runs_arrive_in(schedule, registry):
    late = run(answered(), on=date(2025, 7, 14))
    early = run(answered(), on=date(2025, 6, 1))
    report = build(schedule, [late, early], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.age == 1


def test_a_result_for_a_series_nobody_follows_is_dropped(schedule, registry):
    report = build(schedule, [run(answered(MUC_ORD, AUGUST))], registry, TODAY)
    assert len(report) == 1
    assert report.get("MUC-CHI 2025-08-20") is None


def test_an_answer_from_a_supplier_we_do_not_know_counts_as_a_quote(schedule, registry):
    report = build(schedule, [run(answered(supplier="somebody"))], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert row.answers == 1
    assert row.cover() is Cover.QUOTED


def test_the_gaps_come_back_dark_first_then_by_how_close_the_departure_is(registry):
    schedule = Schedule()
    schedule.follow(FRA_JFK, date(2025, 9, 1))
    schedule.follow(MUC_ORD, date(2025, 7, 20))
    schedule.follow(FRA_JFK, date(2025, 7, 25))
    reports = [run(answered(FRA_JFK, date(2025, 7, 25)), on=date(2025, 6, 1))]
    report = build(schedule, reports, registry, TODAY)
    assert [row.cover() for row in report.gaps()] == [Cover.DARK, Cover.DARK, Cover.LATE]
    assert [row.departure for row in report.gaps()][:2] == [
        date(2025, 7, 20),
        date(2025, 9, 1),
    ]


def test_only_late_and_dark_count_as_gaps():
    assert Cover.DARK.is_a_gap
    assert Cover.LATE.is_a_gap
    assert not Cover.PRICED.is_a_gap
    assert not Cover.QUOTED.is_a_gap
    assert not Cover.FLOWN.is_a_gap


def test_a_row_reads_back_in_one_line(schedule, registry):
    report = build(schedule, [run(answered())], registry, TODAY)
    row = report.get("FRA-NYC 2025-08-20")
    assert row is not None
    assert str(row) == "FRA-NYC 2025-08-20 priced priced 0 days ago"


def test_the_report_reads_back_with_the_three_numbers(schedule, registry):
    report = build(schedule, [], registry, TODAY)
    assert str(report) == "1 live series, 0.00 priced, 1 gaps"


def test_the_rendered_report_counts_every_grade(schedule, registry):
    out = render(build(schedule, [run(answered())], registry, TODAY))
    assert "coverage on 2025-07-15" in out
    assert "followed  1" in out
    assert "priced    1" in out
    assert "rate      1.00" in out


def test_the_rendered_report_leaves_the_gap_list_out_when_there_are_none(schedule, registry):
    out = render(build(schedule, [run(answered())], registry, TODAY))
    assert "gaps, worst first" not in out


def test_the_rendered_report_lists_the_gaps_and_says_how_many_it_showed(registry):
    schedule = Schedule()
    for day in range(1, 6):
        schedule.follow(FRA_JFK, date(2025, 9, day))
    out = render(build(schedule, [], registry, TODAY), limit=2)
    assert "showing 2 of 5" in out
    assert out.count("2025-09-0") == 2
