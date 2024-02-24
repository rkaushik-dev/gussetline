from __future__ import annotations

import pytest

from conftest import segment, surface
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary, JourneyType, SurfaceSector


def test_an_itinerary_needs_an_element():
    with pytest.raises(ValidationError):
        Itinerary([])


def test_an_itinerary_that_does_not_join_up_is_refused():
    with pytest.raises(ValidationError) as caught:
        Itinerary(
            [
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "BOS", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
            ]
        )
    assert caught.value.details["arrives"] == "JFK"
    assert caught.value.details["departs"] == "BOS"


def test_a_surface_sector_goes_somewhere():
    with pytest.raises(ValidationError):
        SurfaceSector("JFK", "JFK")


def test_an_itinerary_does_not_open_or_close_on_the_ground():
    with pytest.raises(ValidationError):
        Itinerary(
            [
                surface("FRA", "MUC"),
                segment("LH", "400", "MUC", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            ]
        )
    with pytest.raises(ValidationError):
        Itinerary(
            [
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                surface("JFK", "BOS"),
            ]
        )


def test_ends_and_points(frankfurt_new_york):
    assert frankfurt_new_york.origin == "FRA"
    assert frankfurt_new_york.destination == "FRA"
    assert frankfurt_new_york.points == ("FRA", "JFK", "FRA")


def test_carriers_are_listed_once_in_order(with_a_connection):
    assert with_a_connection.carriers == ("LH",)


def test_a_short_wait_is_a_connection(with_a_connection):
    breaks = with_a_connection.breaks()
    assert len(breaks) == 1
    assert breaks[0].point == "LHR"
    assert not breaks[0].stopover
    assert with_a_connection.connections() == ("LHR",)
    assert with_a_connection.stopovers() == ()


def test_a_long_wait_is_a_stopover(with_a_stopover):
    assert with_a_stopover.stopovers() == ("LHR",)
    assert with_a_stopover.connections() == ()


def test_the_break_carries_the_ground_time_in_minutes(with_a_connection):
    # Lands 07:40 London, leaves 11:05 London, and London is one hour behind
    # Frankfurt so the wall clocks are already in the right place.
    assert with_a_connection.breaks()[0].minutes == 205


def test_the_threshold_is_four_hours_domestically_and_a_day_internationally():
    domestic = Itinerary(
        [
            segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
            segment("UA", "200", "ORD", "LAX", "2025-03-04 15:00", "2025-03-04 17:30"),
        ]
    )
    assert domestic.stopover_threshold().total_seconds() == 4 * 3600
    assert domestic.stopovers() == ("ORD",)

    international = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 15:00", "2025-03-04 18:00"),
        ]
    )
    assert international.stopover_threshold().total_seconds() == 24 * 3600
    assert international.stopovers() == ()


def test_an_international_journey_uses_the_long_threshold_for_its_domestic_breaks():
    trip = Itinerary(
        [
            segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
            segment("UA", "900", "ORD", "FRA", "2025-03-04 18:00", "2025-03-05 09:20"),
        ]
    )
    assert trip.is_international
    assert trip.stopovers() == ()


def test_a_surface_sector_is_a_stopover_however_short_it_is():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            surface("JFK", "BOS"),
            segment("LH", "423", "BOS", "FRA", "2025-03-04 17:00", "2025-03-05 06:20"),
        ]
    )
    assert trip.has_surface()
    assert "JFK" in trip.stopovers()


def test_a_sector_that_leaves_before_the_last_one_lands_is_refused():
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 06:00", "2025-03-04 09:00"),
        ]
    )
    with pytest.raises(ValidationError):
        trip.breaks()


def test_a_plain_out_and_back_is_a_round_trip(frankfurt_new_york):
    assert frankfurt_new_york.journey_type() is JourneyType.ROUND_TRIP


def test_out_and_back_through_a_connection_is_still_a_round_trip():
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
            segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
            segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35"),
        ]
    )
    assert trip.journey_type() is JourneyType.ROUND_TRIP


def test_a_loop_through_two_different_turn_points_is_a_circle_trip():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
            segment("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20"),
        ]
    )
    assert trip.journey_type() is JourneyType.CIRCLE_TRIP


def test_a_single_sector_is_a_one_way():
    trip = Itinerary([segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")])
    assert trip.journey_type() is JourneyType.ONE_WAY


def test_a_journey_home_to_a_different_airport_is_an_open_jaw():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "417", "JFK", "MUC", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )
    assert trip.journey_type() is JourneyType.OPEN_JAW


def test_the_turnaround_is_the_furthest_point_and_not_the_last_one():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
            segment("AA", "200", "LAX", "ORD", "2025-03-12 07:00", "2025-03-12 13:00"),
            segment("LH", "431", "ORD", "FRA", "2025-03-14 16:20", "2025-03-15 07:45"),
        ]
    )
    assert trip.turnaround() == "LAX"


def test_metro_members_close_a_round_trip():
    trip = Itinerary(
        [
            segment("BA", "112", "LHR", "JFK", "2025-03-04 08:25", "2025-03-04 11:20"),
            segment("BA", "184", "JFK", "LGW", "2025-03-18 21:00", "2025-03-19 09:05"),
        ]
    )
    assert trip.journey_type() is JourneyType.ROUND_TRIP


def test_total_miles_adds_the_surface_sector_in():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            surface("JFK", "BOS"),
            segment("LH", "423", "BOS", "FRA", "2025-03-06 17:00", "2025-03-07 06:20"),
        ]
    )
    assert trip.total_miles() > 7000


def test_string_form_shows_the_surface_sector_with_slashes():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            surface("JFK", "BOS"),
            segment("LH", "423", "BOS", "FRA", "2025-03-06 17:00", "2025-03-07 06:20"),
        ]
    )
    assert "//JFKBOS" in str(trip)


def test_length_and_iteration(frankfurt_new_york):
    assert len(frankfurt_new_york) == 2
    assert [e.origin for e in frankfurt_new_york] == ["FRA", "JFK"]
