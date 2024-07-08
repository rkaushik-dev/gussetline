"""Shared builders. Every test that needs a journey builds it from here.

The helpers take wall clock strings because that is what a timetable reads
like and because a test that spells out datetime constructors four times
stops being readable at the third sector.
"""

from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.combinability import Combinability, CombinabilityStore
from gussetline.distance import MileageTable
from gussetline.exchange import Converter, RateTable
from gussetline.flights import Cabin, Segment
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary, SurfaceSector
from gussetline.money import Money
from gussetline.routing import MILEAGE_ROUTING
from gussetline.tariff import Fare, OwRt


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


def fare(
    origin: str = "FRA",
    destination: str = "JFK",
    amount: str = "420.00",
    *,
    carrier: str = "LH",
    basis: str = "KLWAP7RT",
    currency: str = "EUR",
    **over,
) -> Fare:
    """A published fare with the fields nine tests out of ten do not care about.

    Every one of those tests had its own copy of this and they had drifted:
    two of them defaulted the routing to 0001, which is a real routing number
    that no test filed a map for, so those tests were passing through the
    unknown map fallback rather than through the mileage path they said they
    were testing.
    """
    fields = {
        "carrier": carrier,
        "origin": origin,
        "destination": destination,
        "fare_basis": basis,
        "amount": Money.of(amount, currency),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": MILEAGE_ROUTING,
    }
    fields.update(over)
    return Fare(**fields)


def one_way_fare(origin: str = "FRA", destination: str = "JFK", amount: str = "420.00", **over):
    """The same thing filed so it can stand on its own."""
    return fare(origin, destination, amount, owrt=OwRt.ONE_WAY_ONLY, **over)


@pytest.fixture
def flat_converter() -> Converter:
    """Every currency at one to one, so a test can read the arithmetic."""
    table = RateTable("roe")
    for code in ("EUR", "USD", "GBP", "JPY", "CHF"):
        table.add(code, date(2025, 1, 1), "1.000")
    return Converter(table)


@pytest.fixture
def transatlantic_miles() -> MileageTable:
    """Enough published mileage to keep the usual markets off the fallback."""
    table = MileageTable()
    table.add("FRA", "NYC", 4400)
    table.add("FRA", "LON", 500)
    table.add("LON", "NYC", 4400)
    table.add("FRA", "CHI", 4300)
    table.add("CHI", "NYC", 740)
    table.add("FRA", "DXB", 3000)
    table.add("DXB", "NYC", 6800)
    table.add("FRA", "LAX", 5800)
    table.add("CHI", "LAX", 1700)
    return table


def combinable(basis: str = "KLWAP7RT", carrier: str = "LH") -> Combinability:
    """A record that permits everything, for tests not about combinability."""
    return Combinability(
        carrier=carrier,
        fare_basis=basis,
        round_trip=True,
        circle_trip=True,
        open_jaw=True,
        end_on_end=True,
        same_carrier_only=False,
    )


@pytest.fixture
def permissive_combinability() -> CombinabilityStore:
    store = CombinabilityStore()
    store.add(combinable())
    return store


@pytest.fixture
def tight_miles() -> MileageTable:
    """Mileages that put the usual connecting routings onto a surcharge band.

    Separate from the roomy table because a test about the band needs the
    ladder to fire and a test about anything else needs it not to.
    """
    table = MileageTable()
    table.add("FRA", "NYC", 4000)
    table.add("FRA", "LON", 500)
    table.add("LON", "NYC", 4400)
    table.add("FRA", "DXB", 3000)
    table.add("DXB", "NYC", 6800)
    return table
