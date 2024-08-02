from __future__ import annotations

from datetime import date, time

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Context
from gussetline.rules.application import DayAndTime, FlightApplication, TimeWindow

TICKETING = date(2025, 2, 14)


def out(**over):
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05", **over),)
    )


def trip() -> Itinerary:
    # Out on a Tuesday, home on a Saturday.
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-08 18:30", "2025-03-09 08:05"),
        ]
    )


def context(itinerary=None, component=None) -> Context:
    return Context(
        itinerary=itinerary if itinerary is not None else trip(),
        component=component if component is not None else out(),
        ticketing=TICKETING,
    )


def test_a_window_inside_a_day_covers_its_ends():
    window = TimeWindow(time(6, 0), time(12, 0))
    assert window.covers(time(6, 0))
    assert window.covers(time(12, 0))
    assert not window.covers(time(5, 59))
    assert not window.covers(time(12, 1))
    assert not window.wraps_midnight


def test_a_window_that_wraps_midnight_covers_both_sides():
    window = TimeWindow(time(22, 0), time(4, 0))
    assert window.wraps_midnight
    assert window.covers(time(23, 30))
    assert window.covers(time(2, 0))
    assert not window.covers(time(12, 0))


def test_a_window_reads_back_in_timetable_form():
    assert str(TimeWindow(time(6, 0), time(12, 0))) == "0600 to 1200"


def test_a_day_and_time_filing_with_nothing_in_it_does_not_apply():
    assert not DayAndTime().evaluate(context()).applied


def test_a_day_that_is_not_a_day_is_refused():
    with pytest.raises(ValidationError):
        DayAndTime(days=("funday",))


def test_a_journey_on_the_permitted_days_passes():
    rule = DayAndTime(days=("tue", "sat"))
    assert not rule.evaluate(context()).failed


def test_a_sector_on_a_refused_day_fails_and_names_it():
    rule = DayAndTime(days=("tue",))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details == {"sector": "LH401", "day": "sat"}


def test_an_outbound_only_filing_ignores_the_return():
    rule = DayAndTime(days=("tue",), outbound_only=True)
    assert not rule.evaluate(context()).failed


def test_a_departure_inside_the_time_window_passes():
    rule = DayAndTime(window=TimeWindow(time(6, 0), time(20, 0)))
    assert not rule.evaluate(context()).failed


def test_a_departure_outside_the_time_window_fails():
    rule = DayAndTime(window=TimeWindow(time(6, 0), time(12, 0)))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["sector"] == "LH401"
    assert outcome.details["window"] == "0600 to 1200"


def test_days_and_a_window_both_have_to_hold():
    rule = DayAndTime(days=("tue", "sat"), window=TimeWindow(time(6, 0), time(12, 0)))
    assert rule.evaluate(context()).failed


def test_a_flight_application_with_nothing_in_it_does_not_apply():
    assert not FlightApplication().evaluate(context()).applied


def test_own_metal_only_has_to_say_whose():
    with pytest.raises(ValidationError):
        FlightApplication(own_metal_only=True)


def test_a_flight_on_the_list_passes():
    rule = FlightApplication(flights=("LH400", "LH401"))
    assert not rule.evaluate(context()).failed


def test_a_flight_off_the_list_fails_and_says_what_was_permitted():
    rule = FlightApplication(flights=("LH440",))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["permitted"] == ["LH440"]


def test_the_flight_list_is_case_insensitive():
    rule = FlightApplication(flights=("lh400",))
    assert not rule.evaluate(context()).failed


def test_a_codeshare_is_refused_when_the_fare_bans_them():
    rule = FlightApplication(no_codeshare=True)
    codeshare = out(operating_carrier="UA")
    outcome = rule.evaluate(context(component=codeshare))
    assert outcome.failed
    assert outcome.details["operated_by"] == "UA"


def test_a_flight_the_carrier_operates_itself_is_not_a_codeshare():
    rule = FlightApplication(no_codeshare=True)
    assert not rule.evaluate(context()).failed


def test_own_metal_refuses_the_filing_carriers_own_flight_number_on_somebody_elses_aircraft():
    rule = FlightApplication(own_metal_only=True, carrier="LH")
    codeshare = out(operating_carrier="UA")
    outcome = rule.evaluate(context(component=codeshare))
    assert outcome.failed
    assert outcome.details["operated_by"] == "UA"


def test_own_metal_passes_when_the_filing_carrier_flies_it():
    rule = FlightApplication(own_metal_only=True, carrier="LH")
    assert not rule.evaluate(context()).failed


def test_flight_application_reads_the_component_and_not_the_journey():
    # The return is not on the list and the component does not contain it.
    rule = FlightApplication(flights=("LH400",))
    assert not rule.evaluate(context()).failed
