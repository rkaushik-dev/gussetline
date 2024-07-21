from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Context
from gussetline.rules.stay import MaximumStay, MinimumStay, crosses_weekday

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def trip(back_out: str, back_in: str) -> Itinerary:
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", back_out, back_in),
        ]
    )


def context(back_out: str = "2025-03-18 18:30", back_in: str = "2025-03-19 08:05") -> Context:
    return Context(itinerary=trip(back_out, back_in), component=out(), ticketing=TICKETING)


def one_way_context() -> Context:
    return Context(
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")]
        ),
        component=out(),
        ticketing=TICKETING,
    )


def test_a_week_or_more_covers_every_weekday():
    for day in range(7):
        assert crosses_weekday(date(2025, 3, 4), date(2025, 3, 11), day)


def test_a_short_stay_covers_only_the_days_it_touches():
    # Tuesday the fourth to Friday the seventh.
    assert crosses_weekday(date(2025, 3, 4), date(2025, 3, 7), 1)
    assert crosses_weekday(date(2025, 3, 4), date(2025, 3, 7), 3)
    assert not crosses_weekday(date(2025, 3, 4), date(2025, 3, 7), 4)
    assert not crosses_weekday(date(2025, 3, 4), date(2025, 3, 7), 6)


def test_a_stay_that_ends_before_it_starts_covers_nothing():
    assert not crosses_weekday(date(2025, 3, 7), date(2025, 3, 4), 1)
    assert not crosses_weekday(date(2025, 3, 4), date(2025, 3, 4), 1)


def test_a_filing_with_nothing_in_it_does_not_apply():
    assert not MinimumStay().evaluate(context()).applied
    assert not MaximumStay().evaluate(context()).applied


def test_a_negative_or_zero_period_is_refused():
    with pytest.raises(ValidationError):
        MinimumStay(days=-1)
    with pytest.raises(ValidationError):
        MaximumStay(days=0)
    with pytest.raises(ValidationError):
        MaximumStay(months=0)
    with pytest.raises(ValidationError):
        MinimumStay(weekday="funday")


def test_a_one_way_has_no_stay_to_measure():
    assert not MinimumStay(days=7).evaluate(one_way_context()).failed
    assert not MaximumStay(days=7).evaluate(one_way_context()).failed


def test_a_long_enough_stay_passes_and_says_how_long():
    outcome = MinimumStay(days=7).evaluate(context())
    assert not outcome.failed
    assert outcome.details["nights"] == 14


def test_a_short_stay_fails_and_says_by_how_much():
    outcome = MinimumStay(days=7).evaluate(context("2025-03-07 18:30", "2025-03-08 08:05"))
    assert outcome.failed
    assert outcome.details == {"needed": 7, "had": 3}


def test_a_stay_exactly_on_the_minimum_passes():
    assert not MinimumStay(days=3).evaluate(context("2025-03-07 18:30", "2025-03-08 08:05")).failed


def test_the_sunday_rule_is_not_a_number_of_days():
    # Out Tuesday the fourth, home Friday the seventh. Three nights and no
    # Sunday in them.
    short = context("2025-03-07 18:30", "2025-03-08 08:05")
    assert MinimumStay(weekday="sun").evaluate(short).failed
    # Out Tuesday, home Monday the tenth. Six nights, and a Sunday.
    across = context("2025-03-10 18:30", "2025-03-11 08:05")
    assert not MinimumStay(weekday="sun").evaluate(across).failed


def test_a_longer_stay_can_still_miss_the_weekday_it_wants():
    # Out Tuesday the fourth, home Saturday the eighth. Four nights, no Sunday.
    assert (
        MinimumStay(weekday="sun").evaluate(context("2025-03-08 18:30", "2025-03-09 08:05")).failed
    )


def test_a_weekday_is_read_from_three_letters_in_any_case():
    assert not MinimumStay(weekday="SUNDAY").evaluate(context()).failed
    assert not MinimumStay(weekday=" sun ").evaluate(context()).failed


def test_days_and_a_weekday_both_have_to_be_satisfied():
    rule = MinimumStay(days=2, weekday="sun")
    # Three nights, Friday to Monday, so the days pass and the Sunday passes.
    assert not rule.evaluate(context("2025-03-10 18:30", "2025-03-11 08:05")).failed
    # Three nights, Tuesday to Friday, so the days pass and the Sunday fails.
    assert rule.evaluate(context("2025-03-07 18:30", "2025-03-08 08:05")).failed


def test_a_maximum_in_days_counts_from_the_start_of_the_journey():
    assert MaximumStay(days=30).limit_from(date(2025, 3, 4)) == date(2025, 4, 3)


def test_a_maximum_in_months_lands_on_the_same_day_number():
    assert MaximumStay(months=3).limit_from(date(2025, 3, 4)) == date(2025, 6, 4)
    assert MaximumStay(months=12).limit_from(date(2025, 3, 4)) == date(2026, 3, 4)


def test_a_month_that_is_too_short_takes_its_last_day():
    assert MaximumStay(months=1).limit_from(date(2025, 1, 31)) == date(2025, 2, 28)
    assert MaximumStay(months=1).limit_from(date(2025, 3, 31)) == date(2025, 4, 30)


def test_a_return_inside_the_maximum_passes():
    assert not MaximumStay(months=1).evaluate(context()).failed


def test_a_return_past_the_maximum_fails_and_names_both_dates():
    outcome = MaximumStay(days=7).evaluate(context())
    assert outcome.failed
    assert outcome.details == {"latest": "2025-03-11", "returned": "2025-03-18"}


def test_a_maximum_measured_from_the_turnaround_is_a_different_date():
    from_origin = MaximumStay(days=10)
    from_turn = MaximumStay(days=10, from_turnaround=True)
    assert from_origin.evaluate(context()).failed
    assert from_turn.evaluate(context()).failed
    assert from_origin.limit_from(date(2025, 3, 4)) == date(2025, 3, 14)


def test_days_and_months_together_take_the_later_limit():
    rule = MaximumStay(days=7, months=6)
    assert rule.limit_from(date(2025, 3, 4)) == date(2025, 9, 4)
