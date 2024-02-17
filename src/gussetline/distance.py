"""Distances, and why the published one is not the one you can compute.

Mileage in the tariff is ticketed point mileage, which is published per city
pair and per global indicator and is not the great circle distance. It is
close to it, it is usually a little longer, and it is occasionally very
different because it follows a corridor that aircraft are actually allowed to
fly. Two cities can also have two different TPMs depending on which way round
the world the fare goes, which is why the indicator is part of the key.

Great circle is still here because a pair with no published mileage has to
price somehow, and falling back to it with the fallback recorded is better
than refusing. Everything downstream reads the flag, because a mileage
surcharge computed off a fallback is a finding the desk will not send to a
supplier without checking.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from gussetline.errors import MileageUnavailable
from gussetline.geography import airport, city_of
from gussetline.indicators import GlobalIndicator

EARTH_RADIUS_MILES = 3958.7613


def great_circle(left: str, right: str) -> int:
    """Statute miles between two airports, rounded to the nearest whole mile.

    The tariff works in statute miles and rounds, so this rounds too. Doing
    the rounding later means two call sites disagree by a mile and the
    difference surfaces as a surcharge band change on exactly one itinerary.
    """
    one, two = airport(left), airport(right)
    lat1, lon1 = math.radians(one.latitude), math.radians(one.longitude)
    lat2, lon2 = math.radians(two.latitude), math.radians(two.longitude)
    dlat, dlon = lat2 - lat1, lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return round(2 * EARTH_RADIUS_MILES * math.asin(math.sqrt(a)))


@dataclass(frozen=True, slots=True)
class Mileage:
    """One mileage reading, and where it came from."""

    miles: int
    published: bool
    indicator: GlobalIndicator | None = None

    @property
    def is_fallback(self) -> bool:
        return not self.published


class MileageTable:
    """Published ticketed point mileages, keyed on city pair and indicator.

    Keys are city pairs and not airport pairs, because the tariff publishes
    LON to NYC and never Gatwick to Newark. A lookup folds airports up to
    their metro before it goes anywhere near the table.

    A pair filed with no indicator answers for every indicator, which is how
    most short haul pairs are published. A pair filed with one answers only
    for that one, and the two forms can both be present, in which case the
    specific one wins.
    """

    def __init__(self) -> None:
        self._exact: dict[tuple[str, str, str], int] = {}
        self._any: dict[tuple[str, str], int] = {}

    @staticmethod
    def _pair(left: str, right: str) -> tuple[str, str]:
        one, two = city_of(left), city_of(right)
        return (one, two) if one <= two else (two, one)

    def add(
        self,
        left: str,
        right: str,
        miles: int,
        indicator: GlobalIndicator | None = None,
    ) -> None:
        if miles <= 0:
            raise MileageUnavailable("a mileage has to be positive", miles=miles)
        pair = self._pair(left, right)
        if indicator is None:
            self._any[pair] = miles
        else:
            self._exact[(pair[0], pair[1], indicator.value)] = miles

    def add_many(self, rows: list[tuple[str, str, int]]) -> None:
        for left, right, miles in rows:
            self.add(left, right, miles)

    def has(self, left: str, right: str, indicator: GlobalIndicator | None = None) -> bool:
        pair = self._pair(left, right)
        if indicator is not None and (pair[0], pair[1], indicator.value) in self._exact:
            return True
        return pair in self._any

    def published(self, left: str, right: str, indicator: GlobalIndicator | None = None) -> int:
        pair = self._pair(left, right)
        if indicator is not None:
            found = self._exact.get((pair[0], pair[1], indicator.value))
            if found is not None:
                return found
        found = self._any.get(pair)
        if found is None:
            raise MileageUnavailable(
                "no ticketed point mileage published",
                origin=pair[0],
                destination=pair[1],
                indicator=indicator.value if indicator else None,
            )
        return found

    def tpm(self, left: str, right: str, indicator: GlobalIndicator | None = None) -> Mileage:
        """The mileage to use, published if we have it and great circle if not."""
        try:
            return Mileage(self.published(left, right, indicator), True, indicator)
        except MileageUnavailable:
            return Mileage(great_circle(left, right), False, indicator)

    def size(self) -> int:
        return len(self._any) + len(self._exact)


def default_mileage_table() -> MileageTable:
    """The pairs the desk sells often enough to have keyed in by hand.

    Everything else falls back to great circle, and the coverage report says
    how often that happened so the list can grow where it matters.
    """
    table = MileageTable()
    table.add_many(
        [
            ("FRA", "JFK", 3851),
            ("FRA", "ORD", 4342),
            ("FRA", "LAX", 5791),
            ("FRA", "SIN", 6383),
            ("FRA", "NRT", 5928),
            ("FRA", "DXB", 2984),
            ("FRA", "DEL", 3812),
            ("FRA", "JNB", 5388),
            ("FRA", "GRU", 5972),
            ("LON", "JFK", 3451),
            ("LON", "ORD", 3947),
            ("LON", "LAX", 5439),
            ("LON", "SIN", 6754),
            ("LON", "TYO", 5941),
            ("LON", "DXB", 3402),
            ("LON", "DEL", 4172),
            ("LON", "JNB", 5622),
            ("LON", "GRU", 5892),
            ("PAR", "JFK", 3628),
            ("PAR", "SIN", 6673),
            ("PAR", "DXB", 3253),
            ("AMS", "JFK", 3639),
            ("AMS", "SIN", 6524),
            ("ZRH", "JFK", 3925),
            ("MAD", "JFK", 3585),
            ("FCO", "JFK", 4271),
            ("IST", "JFK", 5009),
            ("DXB", "SIN", 3634),
            ("DXB", "TYO", 4915),
            ("DXB", "SYD", 7481),
            ("SIN", "SYD", 3915),
            ("SIN", "TYO", 3312),
            ("TYO", "LAX", 5451),
            ("TYO", "SFO", 5130),
            ("HKG", "LAX", 7260),
            ("SYD", "LAX", 7488),
            ("JFK", "LAX", 2475),
            ("JFK", "ORD", 740),
            ("JFK", "MIA", 1090),
            ("LON", "FRA", 406),
            ("LON", "PAR", 215),
            ("LON", "MAD", 785),
            ("LON", "FCO", 895),
            ("FRA", "IST", 1160),
            ("FRA", "MAD", 882),
            ("FRA", "ARN", 741),
            ("DEL", "SIN", 2571),
            ("DEL", "DXB", 1372),
        ]
    )
    # Frankfurt to Tokyo is published twice, because the eastbound Siberian
    # routing and the westbound polar one are not the same distance and the
    # surcharge bands land differently on each.
    table.add("FRA", "TYO", 5928, GlobalIndicator.TS)
    table.add("FRA", "TYO", 7169, GlobalIndicator.PA)
    return table
