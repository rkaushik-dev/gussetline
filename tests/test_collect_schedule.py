from __future__ import annotations

from datetime import date

import pytest

from gussetline.collect.schedule import (
    MAX_INTERVAL_DAYS,
    Market,
    RunPlan,
    Schedule,
    Series,
    base_interval,
    interval_for,
)
from gussetline.errors import ValidationError

TODAY = date(2025, 5, 22)


def market(origin="FRA", destination="JFK") -> Market:
    return Market(origin, destination)


def test_a_market_folds_to_cities_and_reads_back_as_a_key():
    assert market("LHR", "EWR").key == "LON-NYC"
    assert str(market()) == "FRA-NYC"


def test_a_market_goes_between_two_places():
    with pytest.raises(ValidationError):
        Market("LHR", "LGW")


def test_the_cadence_tightens_as_the_departure_approaches():
    assert base_interval(2) == 1
    assert base_interval(3) == 1
    assert base_interval(10) == 2
    assert base_interval(45) == 4
    assert base_interval(90) == 7
    assert base_interval(200) == 14


def test_a_departure_further_out_than_the_last_band_takes_the_last_band():
    assert base_interval(900) == 14


def test_a_quiet_market_is_polled_less_often():
    assert interval_for(90, 0) == 7
    assert interval_for(90, 2) == 8
    assert interval_for(90, 8) == 11


def test_the_widening_is_capped_so_nothing_falls_off():
    assert interval_for(200, 200) == MAX_INTERVAL_DAYS


def test_a_negative_quiet_count_is_refused():
    with pytest.raises(ValidationError):
        interval_for(90, -1)


def series(departure=date(2025, 6, 4), **over) -> Series:
    base = Series(market=market(), departure=departure)
    for name, value in over.items():
        setattr(base, name, value)
    return base


def test_a_series_that_has_never_been_polled_is_due():
    assert series().due_on(TODAY)


def test_a_series_polled_today_is_not_due_again():
    assert not series(last_polled=TODAY).due_on(TODAY)


def test_a_series_becomes_due_when_its_interval_has_passed():
    # Thirteen days out, so the interval is two days.
    s = series(last_polled=date(2025, 5, 21))
    assert not s.due_on(TODAY)
    s.last_polled = date(2025, 5, 20)
    assert s.due_on(TODAY)


def test_a_departure_that_has_gone_is_never_due():
    assert not series(departure=date(2025, 5, 21)).due_on(TODAY)


def test_a_departure_today_is_still_due():
    assert series(departure=TODAY).due_on(TODAY)


def test_a_poll_that_finds_the_same_price_counts_as_quiet():
    s = series()
    s.record(TODAY, "420.00")
    assert s.quiet_polls == 0
    s.record(date(2025, 5, 24), "420.00")
    assert s.quiet_polls == 1
    s.record(date(2025, 5, 26), "420.00")
    assert s.quiet_polls == 2


def test_a_poll_that_finds_a_move_resets_the_run():
    s = series()
    s.record(TODAY, "420.00")
    s.record(date(2025, 5, 24), "420.00")
    s.record(date(2025, 5, 26), "380.00")
    assert s.quiet_polls == 0
    assert s.last_amount == "380.00"


def test_a_poll_that_found_nothing_at_all_keeps_the_last_price():
    s = series()
    s.record(TODAY, "420.00")
    s.record(date(2025, 5, 24), "")
    assert s.last_amount == "420.00"
    assert s.quiet_polls == 0


def test_a_series_reads_back_with_its_quiet_run():
    s = series()
    s.record(TODAY, "420.00")
    s.record(date(2025, 5, 24), "420.00")
    assert str(s) == "FRA-NYC 2025-06-04 quiet 1"


def schedule_with(*departures) -> Schedule:
    schedule = Schedule()
    for departure in departures:
        schedule.follow(market(), departure)
    return schedule


def test_a_schedule_follows_a_series_and_finds_it_again():
    schedule = Schedule()
    schedule.follow(market(), date(2025, 6, 4))
    assert len(schedule) == 1
    assert schedule.get(market(), date(2025, 6, 4)) is not None
    assert schedule.get(market(), date(2025, 7, 4)) is None


def test_everything_unpolled_is_due():
    schedule = schedule_with(date(2025, 6, 4), date(2025, 7, 4))
    assert len(schedule.due(TODAY)) == 2


def test_departures_that_have_gone_are_retired():
    schedule = schedule_with(date(2025, 5, 1), date(2025, 6, 4))
    assert schedule.retire_flown(TODAY) == 1
    assert len(schedule) == 1


def test_retiring_twice_finds_nothing_the_second_time():
    schedule = schedule_with(date(2025, 5, 1))
    assert schedule.retire_flown(TODAY) == 1
    assert schedule.retire_flown(TODAY) == 0


def test_a_plan_takes_the_closest_departures_first():
    schedule = Schedule()
    schedule.follow(market(), date(2025, 8, 4))
    schedule.follow(market(), date(2025, 6, 4))
    schedule.follow(market(), date(2025, 7, 4))
    plan = schedule.plan(TODAY, "gds", budget=10)
    assert [t.departure for t in plan] == [
        date(2025, 6, 4),
        date(2025, 7, 4),
        date(2025, 8, 4),
    ]


def test_a_budget_runs_out_on_the_furthest_departures():
    schedule = Schedule()
    schedule.follow(market(), date(2025, 8, 4))
    schedule.follow(market(), date(2025, 6, 4))
    plan = schedule.plan(TODAY, "gds", budget=1)
    assert len(plan) == 1
    assert plan[0].departure == date(2025, 6, 4)


def test_a_budget_of_nothing_plans_nothing():
    schedule = schedule_with(date(2025, 6, 4))
    assert schedule.plan(TODAY, "gds", budget=0) == []


def test_a_negative_budget_is_refused():
    with pytest.raises(ValidationError):
        schedule_with(date(2025, 6, 4)).plan(TODAY, "gds", budget=-1)


def test_a_task_carries_the_distance_it_was_planned_at():
    plan = schedule_with(date(2025, 6, 4)).plan(TODAY, "gds", budget=1)
    assert plan[0].days_out == 13
    assert str(plan[0]) == "gds FRA-NYC 2025-06-04"


def test_a_run_plan_splits_by_supplier_and_lists_its_markets():
    schedule = Schedule()
    schedule.follow(market(), date(2025, 6, 4))
    schedule.follow(Market("MUC", "ORD"), date(2025, 6, 4))
    plan = RunPlan(schedule.plan(TODAY, "gds", 10) + schedule.plan(TODAY, "ndc", 10))
    assert len(plan) == 4
    assert len(plan.for_supplier("gds")) == 2
    assert plan.markets() == ["FRA-NYC", "MUC-CHI"]
    assert str(plan) == "4 tasks over 2 markets"
