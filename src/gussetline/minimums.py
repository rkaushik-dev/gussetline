"""Minimum checks, which exist so a longer journey cannot cost less.

The tariff publishes fares between cities, and city pairs are not consistent
with each other. Frankfurt to Chicago can be dearer than Frankfurt to Los
Angeles even though Los Angeles is further, because the two markets have
different competition. Left alone that means a passenger books Frankfurt to
Los Angeles, gets off in Chicago, and pays less than the Chicago fare.

The higher intermediate point check closes that. Every intermediate point on
a component is priced against both ends, and if any of those fares beats the
one selected, the component is raised to it. The check reads the same tariff
the selection did, which is why it can disagree with it.

Only stopovers count. A passenger who connects through Chicago never has the
option of stopping there, so the Chicago fare is not something they could have
bought instead, and charging them for it would be charging for a journey they
did not take.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from gussetline.components import FareComponent, PricingUnit
from gussetline.distance import MileageTable
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter
from gussetline.geography import city_of
from gussetline.itinerary import JourneyType
from gussetline.tariff import OwRt, TariffStore
from gussetline.trace import Stage, Trace


@dataclass(frozen=True, slots=True)
class MinimumResult:
    """What a minimum check found. Zero added is the usual answer."""

    added: Decimal
    reason: str
    pair: tuple[str, str] | None = None
    compared: Decimal | None = None

    @property
    def applied(self) -> bool:
        return self.added > 0

    def __str__(self) -> str:
        if not self.applied:
            return self.reason
        where = f"{self.pair[0]}-{self.pair[1]}" if self.pair else "unknown"
        return f"{self.reason} on {where}, up {self.added} NUC"


NOTHING = MinimumResult(Decimal(0), "nothing to raise")


def _fare_between(
    store: TariffStore,
    carrier: str,
    origin: str,
    destination: str,
    converter: Converter,
    ticketing: date,
    departure: date,
) -> Decimal | None:
    """The cheapest published fare between two points, in neutral units.

    Returns None rather than raising when nothing is published, because an
    intermediate market with no fare on this carrier simply does not
    constrain the component and is not an error.
    """
    try:
        found = store.cheapest(
            carrier,
            origin,
            destination,
            ticketing=ticketing,
            departure=departure,
        )
    except NoFareFound:
        return None
    return converter.to_nuc(found.amount, ticketing)


def hip_check(
    component: FareComponent,
    store: TariffStore,
    converter: Converter,
    ticketing: date,
    stopovers: tuple[str, ...] = (),
    trace: Trace | None = None,
) -> MinimumResult:
    """Raise a component to its highest intermediate fare, if there is one.

    ``stopovers`` names the points a passenger could have broken at. Passing
    an empty tuple is not a shortcut for all of them, it means the component
    has no stopovers and the check has nothing to compare, which is the
    correct answer for a straight through component.
    """
    if component.nuc is None or component.fare is None:
        raise NoFareFound("a minimum check runs after the component is priced")

    # Folded to cities, because the tariff publishes cities and a finding
    # that names an airport cannot be looked up in it.
    interior = [city_of(p) for p in component.via_points if p in stopovers]
    if not interior:
        if trace is not None:
            trace.note(Stage.MINIMUM, "no stopover to compare, HIP does not apply")
        return NOTHING

    carrier = component.fare.carrier
    departure = component.segments[0].departure.date()
    board, off = component.board_point, component.off_point

    highest = component.nuc
    winner: tuple[str, str] | None = None

    candidates: list[tuple[str, str]] = []
    for point in interior:
        candidates.append((board, point))
        candidates.append((point, off))
    for left in range(len(interior)):
        for right in range(left + 1, len(interior)):
            candidates.append((interior[left], interior[right]))

    for origin, destination in candidates:
        if origin == destination:
            continue
        found = _fare_between(store, carrier, origin, destination, converter, ticketing, departure)
        if found is not None and found > highest:
            highest, winner = found, (origin, destination)

    if winner is None:
        if trace is not None:
            trace.passed(Stage.MINIMUM, "no intermediate fare beats the component")
        return MinimumResult(Decimal(0), "no higher intermediate point")

    added = highest - component.nuc
    result = MinimumResult(added, "higher intermediate point", winner, highest)
    if trace is not None:
        trace.passed(
            Stage.MINIMUM,
            str(result),
            pair=f"{winner[0]}-{winner[1]}",
            added=str(added),
        )
    return result


def apply_hip(component: FareComponent, result: MinimumResult) -> FareComponent:
    """Put a HIP finding onto the component as a surcharge.

    It rides in the surcharge field rather than replacing the fare, because
    the fare basis on the ticket is still the one that was selected and the
    fare calculation line has to show both.
    """
    if not result.applied:
        return component
    return component.with_surcharge(result.added, "HIP")


def backhaul_check(
    component: FareComponent,
    store: TariffStore,
    converter: Converter,
    ticketing: date,
    table: MileageTable,
    trace: Trace | None = None,
) -> MinimumResult:
    """Charge for a journey that goes past its destination and comes back.

    A component from Frankfurt to Chicago routed through Los Angeles is not
    a Chicago journey with a detour, it is a Los Angeles journey that ends
    short. The passenger has had the use of the Los Angeles sector and the
    Chicago fare does not pay for it.

    Two things separate this from the higher intermediate point check and
    both matter. It looks at every ticketed point and not only the stopovers,
    because a passenger who backtracked was carried past the point whether
    they got off or not. And it only fires when the geometry says there was
    a backtrack, which is a reading of the mileage table rather than of the
    fares, so the two checks can disagree and often do.
    """
    if component.nuc is None or component.fare is None:
        raise NoFareFound("a minimum check runs after the component is priced")

    board, off = component.board_point, component.off_point
    to_destination = table.tpm(board, off).miles
    beyond = [
        city_of(point)
        for point in component.via_points
        if table.tpm(board, city_of(point)).miles > to_destination
    ]
    if not beyond:
        if trace is not None:
            trace.note(Stage.MINIMUM, "nothing is beyond the destination, no backhaul")
        return MinimumResult(Decimal(0), "no backhaul")

    carrier = component.fare.carrier
    departure = component.segments[0].departure.date()
    highest = component.nuc
    winner: tuple[str, str] | None = None
    for point in beyond:
        found = _fare_between(store, carrier, board, point, converter, ticketing, departure)
        if found is not None and found > highest:
            highest, winner = found, (board, point)

    if winner is None:
        if trace is not None:
            trace.passed(Stage.MINIMUM, "backtrack found but no dearer fare beyond")
        return MinimumResult(Decimal(0), "no backhaul")

    added = highest - component.nuc
    result = MinimumResult(added, "backhaul", winner, highest)
    if trace is not None:
        trace.passed(Stage.MINIMUM, str(result), pair=f"{winner[0]}-{winner[1]}", added=str(added))
    return result


def apply_minimum(component: FareComponent, result: MinimumResult, label: str) -> FareComponent:
    """Put any minimum finding onto a component under its own label."""
    if not result.applied:
        return component
    return component.with_surcharge(result.added, label)


def highest_of(*results: MinimumResult) -> MinimumResult:
    """The dearest of several checks, because they do not stack.

    A component that fails both the higher intermediate point check and the
    backhaul check is raised once, to whichever found more. Adding both would
    charge twice for the same sector, which is the mistake this function
    exists to make impossible at the call site.
    """
    if not results:
        return NOTHING
    return max(results, key=lambda r: r.added)


def _round_trip_nuc(
    store: TariffStore,
    carrier: str,
    origin: str,
    destination: str,
    converter: Converter,
    ticketing: date,
    departure: date,
) -> Decimal | None:
    """What a plain round trip between two points would have cost, in NUC.

    A filed round trip half is doubled and a one way is taken as it stands,
    because the comparison is against what the passenger could have bought
    instead, and what they could have bought is a whole round trip.
    """
    try:
        found = store.cheapest(
            carrier, origin, destination, ticketing=ticketing, departure=departure
        )
    except NoFareFound:
        return None
    amount = converter.to_nuc(found.amount, ticketing)
    if found.owrt is OwRt.ROUND_TRIP_HALF:
        return amount * Decimal(2)
    return amount


def circle_trip_minimum(
    unit: PricingUnit,
    store: TariffStore,
    converter: Converter,
    ticketing: date,
    trace: Trace | None = None,
) -> MinimumResult:
    """A circle trip may not undercut a round trip to any point on it.

    Frankfurt to New York to Los Angeles and home is three components and can
    add up to less than the Frankfurt to Los Angeles round trip, because the
    three markets are priced independently. The passenger has been to Los
    Angeles and back either way, so the round trip fare is the floor.
    """
    if unit.kind is not JourneyType.CIRCLE_TRIP:
        return MinimumResult(Decimal(0), "not a circle trip")
    if not unit.is_priced:
        raise NoFareFound("a minimum check runs after the unit is priced")

    first = unit.components[0]
    assert first.fare is not None
    carrier = first.fare.carrier
    departure = first.segments[0].departure.date()
    board = first.board_point

    floor = unit.total_nuc
    winner: tuple[str, str] | None = None
    for component in unit.components:
        point = component.off_point
        if point == board:
            continue
        found = _round_trip_nuc(store, carrier, board, point, converter, ticketing, departure)
        if found is not None and found > floor:
            floor, winner = found, (board, point)

    if winner is None:
        if trace is not None:
            trace.passed(Stage.MINIMUM, "circle trip clears every round trip on it")
        return MinimumResult(Decimal(0), "no circle trip minimum")

    added = floor - unit.total_nuc
    result = MinimumResult(added, "circle trip minimum", winner, floor)
    if trace is not None:
        trace.passed(Stage.MINIMUM, str(result), pair=f"{winner[0]}-{winner[1]}", added=str(added))
    return result


def open_jaw_minimum(
    unit: PricingUnit,
    store: TariffStore,
    converter: Converter,
    ticketing: date,
    trace: Trace | None = None,
) -> MinimumResult:
    """An open jaw may not undercut the round trip it is a variation of.

    Out to New York and home to Munich instead of Frankfurt is a Frankfurt to
    New York round trip with the last sector changed. It may cost more and it
    frequently does, but it may not cost less, because the passenger would
    then buy the open jaw and throw the ticket away at Munich.
    """
    if unit.kind is not JourneyType.OPEN_JAW:
        return MinimumResult(Decimal(0), "not an open jaw")
    if not unit.is_priced:
        raise NoFareFound("a minimum check runs after the unit is priced")

    first = unit.components[0]
    assert first.fare is not None
    carrier = first.fare.carrier
    departure = first.segments[0].departure.date()
    board, turn = first.board_point, first.off_point

    floor = _round_trip_nuc(store, carrier, board, turn, converter, ticketing, departure)
    if floor is None or floor <= unit.total_nuc:
        if trace is not None:
            trace.passed(Stage.MINIMUM, "open jaw clears the round trip it varies")
        return MinimumResult(Decimal(0), "no open jaw minimum")

    added = floor - unit.total_nuc
    result = MinimumResult(added, "open jaw minimum", (board, turn), floor)
    if trace is not None:
        trace.passed(Stage.MINIMUM, str(result), pair=f"{board}-{turn}", added=str(added))
    return result
