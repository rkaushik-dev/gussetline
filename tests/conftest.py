"""Shared builders. Every test that needs a journey builds it from here.

The helpers take wall clock strings because that is what a timetable reads
like and because a test that spells out datetime constructors four times
stops being readable at the third sector.
"""

from __future__ import annotations

from datetime import datetime

import pytest

from gussetline.flights import Cabin, Segment
from gussetline.itinerary import Itinerary, SurfaceSector


def at(text: str) -> datetime:
    """Read ``2025-03-04 10:25`` into a naive local datetime."""
    return datetime.strptime(text, "%Y-%m-%d %H:%M")


def segment(
    carrier: str,
    number: str,
    origin: str,
    destination: str,
    departure: str,
    arrival: str,
    booking_class: str = "K",
    cabin: Cabin = Cabin.ECONOMY,
    **extra,
) -> Segment:
    return Segment(
        marketing_carrier=carrier,
        flight_number=number,
        origin=origin,
        destination=destination,
        departure=at(departure),
        arrival=at(arrival),
        booking_class=booking_class,
        cabin=cabin,
        day_change=(at(arrival).date() - at(departure).date()).days,
        **extra,
    )


def surface(origin: str, destination: str) -> SurfaceSector:
    return SurfaceSector(origin, destination)


@pytest.fixture
def frankfurt_new_york() -> Itinerary:
    """A plain transatlantic round trip with one connection each way."""
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


@pytest.fixture
def with_a_connection() -> Itinerary:
    return Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )


@pytest.fixture
def with_a_stopover() -> Itinerary:
    return Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00"),
        ]
    )
