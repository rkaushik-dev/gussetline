from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Context
from gussetline.rules.breaks import Stopovers, Transfers

TICKETING = date(2025, 2, 14)


def connecting_trip():
    """One short break in London, so a transfer and not a stopover."""
    return Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )


def stopping_trip():
    """The same routing with two days in London, so a stopover."""
    return Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00"),
        ]
    )


def component(itinerary):
    return FareComponent(itinerary.elements)


def context(itinerary) -> Context:
    return Context(itinerary=itinerary, component=component(itinerary), ticketing=TICKETING)


def test_a_filing_with_nothing_in_it_does_not_apply():
    assert not Stopovers().evaluate(context(connecting_trip())).applied
    assert not Transfers().evaluate(context(connecting_trip())).applied


def test_negative_allowances_are_refused():
    with pytest.raises(ValidationError):
        Stopovers(permitted=-1)
    with pytest.raises(ValidationError):
        Stopovers(permitted=1, free=-1)
    with pytest.raises(ValidationError):
        Stopovers(permitted=1, charge_nuc=Decimal(-1))
    with pytest.raises(ValidationError):
        Transfers(permitted=-1)


def test_the_same_routing_counts_in_two_different_buckets():
    # This is the point of the file. Nothing in either rule changed.
    no_stops = Stopovers(permitted=0)
    one_transfer = Transfers(permitted=1)

    short = context(connecting_trip())
    assert not no_stops.evaluate(short).failed
    assert not one_transfer.evaluate(short).failed

    long = context(stopping_trip())
    assert no_stops.evaluate(long).failed
    assert not one_transfer.evaluate(long).failed


def test_a_stopover_over_the_allowance_fails_with_the_count():
    outcome = Stopovers(permitted=0).evaluate(context(stopping_trip()))
    assert outcome.failed
    assert outcome.details == {"taken": 1, "permitted": 0}


def test_a_stopover_inside_the_allowance_passes():
    outcome = Stopovers(permitted=2).evaluate(context(stopping_trip()))
    assert not outcome.failed
    assert outcome.details["taken"] == 1


def test_a_stopover_at_a_point_the_fare_does_not_name_fails():
    rule = Stopovers(permitted=2, at_points=("AMS",))
    outcome = rule.evaluate(context(stopping_trip()))
    assert outcome.failed
    assert outcome.details["at"] == "LON"


def test_a_stopover_at_a_named_point_passes():
    rule = Stopovers(permitted=2, at_points=("LHR", "AMS"))
    assert not rule.evaluate(context(stopping_trip())).failed


def test_the_named_points_are_folded_to_cities():
    rule = Stopovers(permitted=2, at_points=("LGW",))
    assert not rule.evaluate(context(stopping_trip())).failed


def test_a_free_allowance_means_no_charge():
    rule = Stopovers(permitted=2, free=1, charge_nuc=Decimal("50.00"))
    assert rule.charge_for(context(stopping_trip())) == Decimal(0)


def test_a_stopover_past_the_free_allowance_is_charged():
    rule = Stopovers(permitted=2, free=0, charge_nuc=Decimal("50.00"))
    assert rule.charge_for(context(stopping_trip())) == Decimal("50.00")


def test_no_charge_filed_means_nothing_to_charge():
    rule = Stopovers(permitted=2, free=0)
    assert rule.charge_for(context(stopping_trip())) == Decimal(0)


def test_a_transfer_over_the_allowance_fails():
    outcome = Transfers(permitted=0).evaluate(context(connecting_trip()))
    assert outcome.failed
    assert outcome.details == {"taken": 1, "permitted": 0}


def test_a_transfer_at_a_point_the_fare_does_not_name_fails():
    rule = Transfers(permitted=2, at_points=("AMS",))
    outcome = rule.evaluate(context(connecting_trip()))
    assert outcome.failed
    assert outcome.details["at"] == "LON"


def test_an_online_only_filing_passes_a_single_carrier_component():
    rule = Transfers(permitted=2, online_only=True)
    assert not rule.evaluate(context(connecting_trip())).failed


def test_an_online_only_filing_refuses_an_interline_component():
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("BA", "175", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    rule = Transfers(permitted=2, online_only=True)
    outcome = rule.evaluate(context(trip))
    assert outcome.failed
    assert outcome.details["carriers"] == ["BA", "LH"]


def test_a_per_direction_count_only_sees_the_points_on_its_own_component():
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )
    outbound = FareComponent(trip.elements[:2])
    homebound = FareComponent(trip.elements[2:])
    rule = Stopovers(permitted=0, per_direction=True)
    assert rule.evaluate(Context(itinerary=trip, component=outbound, ticketing=TICKETING)).failed
    assert not rule.evaluate(
        Context(itinerary=trip, component=homebound, ticketing=TICKETING)
    ).failed


def test_a_whole_journey_count_sees_every_point():
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )
    homebound = FareComponent(trip.elements[2:])
    rule = Stopovers(permitted=0, per_direction=False)
    assert rule.evaluate(Context(itinerary=trip, component=homebound, ticketing=TICKETING)).failed
