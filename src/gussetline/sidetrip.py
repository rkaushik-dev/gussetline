"""Side trips, which are the one thing that leaves a journey and comes back.

A passenger flying Frankfurt to New York who goes Frankfurt, Vienna, Frankfurt,
New York has taken a side trip to Vienna. It is a real shape, agents sell it
constantly, and it breaks two things that otherwise work.

It breaks the mileage check. Vienna is a point the itinerary touches, so the
obvious reading puts it on the path of the transatlantic component, and the
flown mileage then includes a thousand miles east that the transatlantic fare
has nothing to do with. The component fails the mileage ladder and prices as
a construction the passenger did not buy. A side trip is priced on its own and
its points do not belong to the component it hangs off.

It breaks the journey type. Frankfurt to Vienna to Frankfurt to New York is
not a circle trip and it is not two round trips. Strip the side trip and what
is left is a plain one way, which is what it is.

Finding one is easy to get wrong in the direction that costs money. A point
that repeats is not a side trip if the journey ends there, because that is a
round trip and it prices as one. It is not a side trip if it repeats at the
start, because that is where the journey began. And the run between the two
visits has to be a genuine excursion: a connection through Frankfurt on the
way to New York touches it twice in an afternoon and is a connection, not a
side trip, which is why the time between the two visits is checked.

Category 10 has a side trip flag and it is not optional. A fare that does not
permit one prices the whole thing as one component, which is exactly the
mileage failure above, and that failure is the correct answer: the passenger
has to buy something else.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from gussetline.combinability import CombinabilityStore
from gussetline.distance import great_circle
from gussetline.errors import ValidationError
from gussetline.geography import city_of
from gussetline.itinerary import Element, Itinerary, SurfaceSector

# A point touched twice inside this is a connection and not an excursion.
LEAST_SIDE_TRIP = timedelta(hours=24)


@dataclass(frozen=True, slots=True)
class SideTrip:
    """One excursion off a journey, and where it hangs off it."""

    at: str
    first: int
    last: int
    elements: tuple[Element, ...]

    def __post_init__(self) -> None:
        if not self.elements:
            raise ValidationError("a side trip goes somewhere", at=self.at)
        if self.first > self.last:
            raise ValidationError("a side trip runs forwards", first=self.first)

    @property
    def points(self) -> tuple[str, ...]:
        """Everywhere the excursion goes, not counting the point it hangs off."""
        seen = [city_of(e.destination) for e in self.elements]
        return tuple(dict.fromkeys(p for p in seen if p != city_of(self.at)))

    @property
    def turnaround(self) -> str:
        """The furthest point of the excursion, which is what it is called by."""
        found = self.points
        return found[-1] if found else city_of(self.at)

    def __len__(self) -> int:
        return len(self.elements)

    def __str__(self) -> str:
        return f"side trip to {self.turnaround} off {city_of(self.at)}"


def find(itinerary: Itinerary, least: timedelta = LEAST_SIDE_TRIP) -> tuple[SideTrip, ...]:
    """Every excursion in a journey, outermost first.

    Outermost first because a side trip inside a side trip is priced with the
    outer one and pulling the inner one out first leaves the outer with a hole
    in the middle of it.
    """
    elements = itinerary.elements
    found: list[SideTrip] = []
    index = 0

    while index < len(elements):
        close = _returns_to(itinerary, index, least)
        if close is None:
            index += 1
            continue
        found.append(
            SideTrip(
                at=elements[index].origin,
                first=index,
                last=close,
                elements=tuple(elements[index : close + 1]),
            )
        )
        index = close + 1

    return tuple(found)


def _returns_to(itinerary: Itinerary, start: int, least: timedelta) -> int | None:
    """Where the journey comes back to where element ``start`` left, if it does.

    The last return and not the first, so an excursion that touches its own
    board point in the middle comes back as one side trip rather than two.
    """
    elements = itinerary.elements
    point = city_of(elements[start].origin)
    close: int | None = None
    for index in range(start, len(elements)):
        if city_of(elements[index].destination) != point:
            continue
        if index == len(elements) - 1:
            # Ending here means the journey came home, which is a round trip
            # and not an excursion. Only the last element counts: a journey
            # that touches its origin in the middle and carries on has left
            # again, and that is exactly what a side trip is.
            break
        if _apart(itinerary, start, index) < least:
            continue
        close = index
    return close


def _apart(itinerary: Itinerary, start: int, close: int) -> timedelta:
    """How long the journey was away, so a connection is not read as a trip."""
    left = _departure_of(itinerary, start)
    back = _arrival_of(itinerary, close)
    if left is None or back is None:
        # A surface sector at either end carries no clock. An excursion the
        # passenger drove is still an excursion, so it is let through.
        return LEAST_SIDE_TRIP
    return back - left


def _departure_of(itinerary: Itinerary, index: int) -> datetime | None:
    element = itinerary.elements[index]
    return None if isinstance(element, SurfaceSector) else element.departure


def _arrival_of(itinerary: Itinerary, index: int) -> datetime | None:
    element = itinerary.elements[index]
    return None if isinstance(element, SurfaceSector) else element.arrival


def without(itinerary: Itinerary, trips: tuple[SideTrip, ...]) -> Itinerary:
    """The journey with its excursions taken out, which is what prices.

    What is left has to join up on its own, and it does: an excursion leaves a
    point and returns to it, so removing the whole run leaves the element
    before it arriving where the element after it departs.
    """
    if not trips:
        return itinerary
    cut = {index for trip in trips for index in range(trip.first, trip.last + 1)}
    kept = [element for index, element in enumerate(itinerary.elements) if index not in cut]
    if not kept:
        raise ValidationError("a journey cannot be all side trip")
    return Itinerary(kept)


def permitted(
    trips: tuple[SideTrip, ...],
    carrier: str,
    fare_basis: str,
    store: CombinabilityStore,
) -> list[str]:
    """Every reason these excursions may not be priced as side trips.

    An empty list is the pass. A fare with no category 10 filed refuses, the
    same as everywhere else: the safe reading of a filing we do not hold is
    that it does not permit something, because the alternative prices a
    construction the carrier will not sell.
    """
    if not trips:
        return []
    record = store.get(carrier, fare_basis)
    if record is None:
        return [f"{carrier} {fare_basis} has no combinability filed"]
    if not record.side_trip:
        return [f"{carrier} {fare_basis} does not permit a side trip"]
    return []


def miles_off(itinerary: Itinerary, trips: tuple[SideTrip, ...]) -> int:
    """How many miles the excursions account for, for the run report.

    Worth a number of its own because it is the difference between the
    mileage a naive check would use and the one the component is entitled to,
    and that difference is what a surcharge band argument turns on.
    """
    return sum(_miles_of(element) for trip in trips for element in trip.elements)


def _miles_of(element: Element) -> int:
    """Great circle miles for one element, flown or driven."""
    return great_circle(element.origin, element.destination)
