from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Context
from gussetline.rules.dates import Blackout, DateRange, Seasonality, TravelDates, ranges_from

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def trip() -> Itinerary:
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


def context(itinerary=None, component=None) -> Context:
    return Context(
        itinerary=itinerary if itinerary is not None else trip(),
        component=component if component is not None else out(),
        ticketing=TICKETING,
    )


def test_a_range_closes_after_it_opens():
    with pytest.raises(ValidationError):
        DateRange(date(2025, 6, 1), date(2025, 5, 1))
    assert DateRange(date(2025, 5, 1), date(2025, 5, 1)).covers(date(2025, 5, 1))


def test_a_range_includes_both_ends():
    window = DateRange(date(2025, 3, 1), date(2025, 3, 31))
    assert window.covers(date(2025, 3, 1))
    assert window.covers(date(2025, 3, 31))
    assert not window.covers(date(2025, 2, 28))
    assert not window.covers(date(2025, 4, 1))


def test_two_ranges_overlap_when_they_touch():
    left = DateRange(date(2025, 3, 1), date(2025, 3, 31))
    assert left.overlaps(DateRange(date(2025, 3, 31), date(2025, 4, 30)))
    assert not left.overlaps(DateRange(date(2025, 4, 1), date(2025, 4, 30)))


def test_a_range_reads_back_as_its_ends():
    assert str(DateRange(date(2025, 3, 1), date(2025, 3, 31))) == "2025-03-01 to 2025-03-31"


def test_ranges_are_read_from_the_loader_shape():
    windows = ranges_from([("2025-03-01", "2025-03-31"), ("2025-06-01", "2025-08-31")])
    assert len(windows) == 2
    assert windows[1].covers(date(2025, 7, 4))


def test_a_season_with_nothing_filed_does_not_apply():
    assert not Seasonality().evaluate(context()).applied


def test_a_departure_inside_the_season_passes():
    rule = Seasonality(ranges_from([("2025-03-01", "2025-03-31")]), season="low")
    outcome = rule.evaluate(context())
    assert not outcome.failed
    assert outcome.details["window"] == "2025-03-01 to 2025-03-31"


def test_a_departure_outside_every_window_fails_and_lists_them():
    rule = Seasonality(
        ranges_from([("2025-06-01", "2025-08-31"), ("2025-12-15", "2025-12-31")]),
        season="high",
    )
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert len(outcome.details["windows"]) == 2
    assert outcome.details["departure"] == "2025-03-04"


def test_seasonality_reads_the_component_and_not_the_journey():
    # The component leaves in March and the journey comes home in April. The
    # season is checked on the component, so April is not consulted.
    late = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-04-18 18:30", "2025-04-19 08:05"),
        ]
    )
    rule = Seasonality(ranges_from([("2025-03-01", "2025-03-31")]))
    assert not rule.evaluate(context(itinerary=late)).failed


def test_a_blackout_with_nothing_filed_does_not_apply():
    assert not Blackout().evaluate(context()).applied


def test_a_journey_clear_of_the_blackout_passes():
    rule = Blackout(ranges_from([("2025-12-20", "2025-12-27")]))
    assert not rule.evaluate(context()).failed


def test_a_blackout_on_the_outbound_fails():
    rule = Blackout(ranges_from([("2025-03-01", "2025-03-07")]))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["sector"] == "LH400"


def test_a_blackout_on_the_return_alone_still_fails():
    # This is the one a component level check misses. The outbound is clear
    # and the journey is not.
    rule = Blackout(ranges_from([("2025-03-15", "2025-03-20")]))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["sector"] == "LH401"


def test_travel_dates_with_nothing_filed_do_not_apply():
    assert not TravelDates().evaluate(context()).applied


def test_a_departure_window_that_closes_before_it_opens_is_refused():
    with pytest.raises(ValidationError):
        TravelDates(first_departure=date(2025, 6, 1), last_departure=date(2025, 5, 1))


def test_travel_starting_too_early_fails():
    rule = TravelDates(first_departure=date(2025, 4, 1))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["earliest"] == "2025-04-01"


def test_travel_starting_too_late_fails():
    rule = TravelDates(last_departure=date(2025, 2, 28))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["latest"] == "2025-02-28"


def test_travel_inside_the_departure_window_passes():
    rule = TravelDates(first_departure=date(2025, 1, 1), last_departure=date(2025, 6, 30))
    assert not rule.evaluate(context()).failed


def test_travel_that_finishes_too_late_fails_on_the_completion_date():
    rule = TravelDates(complete_by=date(2025, 3, 10))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["ends"] == "2025-03-19"


def test_travel_that_finishes_in_time_passes():
    rule = TravelDates(complete_by=date(2025, 3, 31))
    assert not rule.evaluate(context()).failed


def test_the_departure_window_is_checked_before_the_completion_date():
    rule = TravelDates(first_departure=date(2025, 4, 1), complete_by=date(2025, 3, 10))
    outcome = rule.evaluate(context())
    assert outcome.details["earliest"] == "2025-04-01"
