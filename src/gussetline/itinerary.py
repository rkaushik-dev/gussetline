"""The journey, and the break in it that costs money.

A passenger who waits three hours in Frankfurt is connecting. A passenger who
waits thirty hours in Frankfurt has taken a stopover, which is a priced thing
the fare either permits free, permits for a charge or forbids outright. The
line between the two is a clock reading and nothing else, and the reading is
different for domestic travel, which is why it lives here and not in the rule
engine.

A surface sector is a break where the passenger gets themselves from one point
to another. It is written on the ticket as ARNK and it changes the shape of
the journey without being flown, so it has to be an element of the itinerary
rather than a gap between elements. An itinerary that simply jumps from one
point to a different one is refused, because the alternative is silently
inventing a surface sector that the fare may not permit.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum

from gussetline.distance import great_circle
from gussetline.errors import ValidationError
from gussetline.flights import Segment
from gussetline.geography import airport, city_of, same_country

# A break longer than this is a stopover. Domestic travel uses the shorter
# figure, and a journey with any international sector uses the longer one for
# every break in it, including the domestic ones.
STOPOVER_INTERNATIONAL = timedelta(hours=24)
STOPOVER_DOMESTIC = timedelta(hours=4)


@dataclass(frozen=True, slots=True)
class SurfaceSector:
    """A break the passenger covers themselves. Not flown, still on the ticket."""

    origin: str
    destination: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "origin", airport(self.origin).code)
        object.__setattr__(self, "destination", airport(self.destination).code)
        if self.origin == self.destination:
            raise ValidationError("a surface sector goes somewhere", point=self.origin)

    @property
    def miles(self) -> int:
        return great_circle(self.origin, self.destination)

    def __str__(self) -> str:
        return f"//{self.origin}{self.destination}"


Element = Segment | SurfaceSector


class JourneyType(str, Enum):
    ONE_WAY = "OW"
    ROUND_TRIP = "RT"
    CIRCLE_TRIP = "CT"
    OPEN_JAW = "OJ"


@dataclass(frozen=True, slots=True)
class Break:
    """Time on the ground at an intermediate point."""

    point: str
    arrived: datetime
    departs: datetime
    stopover: bool

    @property
    def minutes(self) -> int:
        return int((self.departs - self.arrived).total_seconds() // 60)

    @property
    def duration(self) -> timedelta:
        return self.departs - self.arrived


class Itinerary:
    """An ordered run of segments and surface sectors that joins up."""

    def __init__(self, elements: Sequence[Element]) -> None:
        if not elements:
            raise ValidationError("an itinerary needs at least one element")
        self.elements: tuple[Element, ...] = tuple(elements)
        self._check_continuity()

    def _check_continuity(self) -> None:
        for index in range(len(self.elements) - 1):
            left, right = self.elements[index], self.elements[index + 1]
            if left.destination != right.origin:
                raise ValidationError(
                    "itinerary does not join up",
                    at=index,
                    arrives=left.destination,
                    departs=right.origin,
                )
        if isinstance(self.elements[0], SurfaceSector):
            raise ValidationError("an itinerary does not open with a surface sector")
        if isinstance(self.elements[-1], SurfaceSector):
            raise ValidationError("an itinerary does not close with a surface sector")

    @property
    def segments(self) -> tuple[Segment, ...]:
        return tuple(e for e in self.elements if isinstance(e, Segment))

    @property
    def origin(self) -> str:
        return self.elements[0].origin

    @property
    def destination(self) -> str:
        return self.elements[-1].destination

    @property
    def points(self) -> tuple[str, ...]:
        return (self.origin, *(e.destination for e in self.elements))

    @property
    def carriers(self) -> tuple[str, ...]:
        seen: list[str] = []
        for segment in self.segments:
            if segment.marketing_carrier not in seen:
                seen.append(segment.marketing_carrier)
        return tuple(seen)

    @property
    def is_international(self) -> bool:
        return any(not same_country(e.origin, e.destination) for e in self.elements)

    @property
    def departure(self) -> datetime:
        return self.segments[0].departure

    @property
    def arrival(self) -> datetime:
        last = self.segments[-1]
        return datetime.combine(last.arrival_date, last.arrival.time())

    def stopover_threshold(self) -> timedelta:
        return STOPOVER_INTERNATIONAL if self.is_international else STOPOVER_DOMESTIC

    def breaks(self) -> tuple[Break, ...]:
        """Every intermediate point, with the ground time and the verdict.

        A surface sector is not a break with a duration, it is a break by
        definition, so the point where one starts is always a stopover
        whatever the clock says.
        """
        threshold = self.stopover_threshold()
        found: list[Break] = []
        for index in range(len(self.elements) - 1):
            left, right = self.elements[index], self.elements[index + 1]
            if isinstance(left, SurfaceSector) or isinstance(right, SurfaceSector):
                arrived = self._arrival_of(index)
                departs = self._departure_of(index + 1)
                found.append(Break(left.destination, arrived, departs, True))
                continue
            arrived = datetime.combine(left.arrival_date, left.arrival.time())
            departs = right.departure
            if departs < arrived:
                raise ValidationError(
                    "the next sector leaves before the last one lands",
                    point=left.destination,
                )
            found.append(Break(left.destination, arrived, departs, departs - arrived > threshold))
        return tuple(found)

    def _arrival_of(self, index: int) -> datetime:
        element = self.elements[index]
        if isinstance(element, Segment):
            return datetime.combine(element.arrival_date, element.arrival.time())
        return self._arrival_of(index - 1)

    def _departure_of(self, index: int) -> datetime:
        element = self.elements[index]
        if isinstance(element, Segment):
            return element.departure
        return self._departure_of(index + 1)

    def stopovers(self) -> tuple[str, ...]:
        return tuple(b.point for b in self.breaks() if b.stopover)

    def connections(self) -> tuple[str, ...]:
        return tuple(b.point for b in self.breaks() if not b.stopover)

    def journey_type(self) -> JourneyType:
        """Which of the four shapes the tariff prices this as.

        The test for a round trip is that the way home mirrors the way out,
        point for point, and not that the journey ends where it started. A
        loop out through New York and home through Los Angeles also ends where
        it started and is a circle trip, which prices on a different minimum.
        """
        cities = [city_of(p) for p in self.points]
        if cities[0] != cities[-1]:
            if self.turnaround_index() != len(self.points) - 1:
                return JourneyType.OPEN_JAW
            return JourneyType.ONE_WAY
        turn = self.turnaround_index()
        outbound = cities[: turn + 1]
        inbound = cities[turn:]
        if inbound == list(reversed(outbound)):
            return JourneyType.ROUND_TRIP
        return JourneyType.CIRCLE_TRIP

    def turnaround_index(self) -> int:
        """Where in the point list the journey turns round.

        The first point at the maximum distance wins, so a journey that sits
        at its furthest city for two sectors breaks the fare on arrival there
        rather than on departure, which is what the tariff does.
        """
        best_index, best_miles = 0, -1
        for index, point in enumerate(self.points):
            miles = great_circle(self.origin, point)
            if miles > best_miles:
                best_index, best_miles = index, miles
        return best_index

    def turnaround(self) -> str:
        """The point furthest from the origin, which is where a fare breaks."""
        return self.points[self.turnaround_index()]

    def total_miles(self) -> int:
        return sum(great_circle(e.origin, e.destination) for e in self.elements)

    def has_surface(self) -> bool:
        return any(isinstance(e, SurfaceSector) for e in self.elements)

    def __len__(self) -> int:
        return len(self.elements)

    def __iter__(self) -> Iterable[Element]:
        return iter(self.elements)

    def __str__(self) -> str:
        return " ".join(str(e) for e in self.elements)
