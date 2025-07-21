"""The pricing engine, which is the ladder run in the one order that works.

Split the journey into components, select a fare for each, add the surcharges,
run the minimum checks, group the components into pricing units, check the
combinability, add the unit minimums, convert once, and write the line.

The order is not a preference and it took two rounds of arguing with a
supplier to get right.

The fare is selected at its filed amount with no band on it. The higher
intermediate point and backhaul checks run against that base and can replace
it, because they are fare determination and not surcharges. Only then does the
mileage band apply, and it applies to whichever fare ended up governing, so a
component raised to the Chicago fare pays five percent of Chicago and not five
percent of Los Angeles. Q surcharges go on after that. Unit minimums come last
before the single conversion into the selling currency.

Put the band on at selection time instead and every component the minimum
checks touch is surcharged on the wrong base. It is a small number and it is
wrong in the supplier's favour, which is why nobody notices.

Side trips come out before any of that happens. An excursion off the middle of
a journey is priced as its own component and its points are not on the path of
the component it hangs off, so the journey has to be split at its edges before
the shape is read at all. Reading the shape first sees Frankfurt twice and
calls a one way with an excursion a circle trip, which then breaks the fare
where nothing turns round.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from gussetline.combinability import CombinabilityStore, check_end_on_end, check_unit
from gussetline.components import FareComponent, PricingUnit, split_at
from gussetline.construction import (
    Construction,
    assemble_circle_trip,
    assemble_one_way,
    assemble_round_trip,
)
from gussetline.distance import MileageTable
from gussetline.errors import ConstructionError
from gussetline.exchange import Converter
from gussetline.fcl import render
from gussetline.itinerary import Itinerary, JourneyType
from gussetline.mileage import ExtraMileageAllowance
from gussetline.minimums import (
    apply_minimum,
    backhaul_check,
    circle_trip_minimum,
    highest_of,
    hip_check,
    open_jaw_minimum,
)
from gussetline.money import Money
from gussetline.routing import RoutingStore
from gussetline.rules.base import Context as RuleContext
from gussetline.rules.base import RuleStore, evaluate, refusals
from gussetline.rules.breaks import Stopovers
from gussetline.selection import apply_band, apply_fare, select
from gussetline.sidetrip import SideTrip
from gussetline.sidetrip import find as find_side_trips
from gussetline.sidetrip import without as strip_side_trips
from gussetline.surcharges import SurchargeTable, apply_all, assess_component
from gussetline.tariff import TariffStore
from gussetline.trace import Stage, Trace


@dataclass(frozen=True, slots=True)
class Tariffs:
    """Everything the engine reads. Passed as one thing so it cannot go stale.

    A run that reads the fare store from one snapshot and the routing store
    from another produces a price nobody can reproduce, so they travel
    together and the loaders replace the whole object.
    """

    fares: TariffStore
    routings: RoutingStore = field(default_factory=RoutingStore)
    miles: MileageTable = field(default_factory=MileageTable)
    surcharges: SurchargeTable = field(default_factory=SurchargeTable)
    combinability: CombinabilityStore = field(default_factory=CombinabilityStore)
    allowances: ExtraMileageAllowance = field(default_factory=ExtraMileageAllowance)
    rules: RuleStore = field(default_factory=RuleStore)


@dataclass(frozen=True, slots=True)
class Priced:
    """A finished price and everything needed to argue about it."""

    construction: Construction
    base_fare: Money
    line: str
    refusals: tuple[str, ...] = field(default_factory=tuple)

    @property
    def trace(self) -> Trace:
        return self.construction.trace

    @property
    def total_nuc(self) -> Decimal:
        return self.construction.total_nuc

    @property
    def issuable(self) -> bool:
        """Whether this could actually be ticketed.

        A price with combinability refusals on it is still a price and is
        still worth showing, because the desk needs to see what the supplier
        must have done differently. It just cannot be sold.
        """
        return not self.refusals

    def __str__(self) -> str:
        return f"{self.base_fare} on {self.line}"


def price(
    itinerary: Itinerary,
    tariffs: Tariffs,
    converter: Converter,
    ticketing: date,
    selling_currency: str,
    *,
    breaks: tuple[int, ...] | None = None,
    trace: Trace | None = None,
) -> Priced:
    """Run the whole ladder over one itinerary.

    ``breaks`` names where the fare components split, as element indices. It
    is not derived here on purpose. Finding the cheapest split is a search
    over every combination and belongs above this function, which prices the
    split it is given so that the search can compare two of them.
    """
    trail = trace if trace is not None else Trace(f"{itinerary.origin}{itinerary.destination}")
    excursions = find_side_trips(itinerary)
    kind = _shape_of(itinerary, excursions, trail)
    points = breaks if breaks is not None else _default_breaks(itinerary, kind, excursions)
    components = split_at(itinerary.elements, points)
    stopovers = itinerary.stopovers()
    connections = itinerary.connections()

    priced: list[FareComponent] = []
    refused: list[str] = []
    for component in components:
        built, said = _price_component(
            component, itinerary, tariffs, converter, ticketing, stopovers, trail
        )
        priced.append(built)
        refused.extend(said)

    units = _assemble(priced, kind, trail)
    for unit in units:
        refused.extend(check_unit(unit, tariffs.combinability, trail))
    refused.extend(check_end_on_end(units, tariffs.combinability, trail))

    construction = Construction(units, selling_currency, ticketing, trail)
    for unit in units:
        construction = _apply_unit_minimums(
            construction, unit, tariffs, converter, ticketing, trail
        )

    base = construction.base_fare(converter)
    trail.passed(Stage.CONVERSION, f"{construction.total_nuc} NUC becomes {base}")
    roe = converter.roe.rate_for(selling_currency, ticketing)
    return Priced(construction, base, render(construction, roe, connections), tuple(refused))


def _price_component(
    component: FareComponent,
    itinerary: Itinerary,
    tariffs: Tariffs,
    converter: Converter,
    ticketing: date,
    stopovers: tuple[str, ...],
    trail: Trace,
) -> tuple[FareComponent, list[str]]:
    """Take one component from unpriced to fully charged.

    Pulled out of price because the ladder for a single component is eight
    steps in a fixed order, and having it inline inside a loop inside the
    journey level function made the order look like an accident of where the
    lines happened to sit.
    """
    chosen = select(
        component,
        tariffs.fares,
        tariffs.routings,
        tariffs.miles,
        converter,
        ticketing,
        allowances=tariffs.allowances,
        trace=trail,
    )
    with_fare = apply_fare(component, chosen)

    said = _check_rules(with_fare, itinerary, tariffs, ticketing, trail)

    hip = hip_check(with_fare, tariffs.fares, converter, ticketing, stopovers, trail)
    backhaul = backhaul_check(with_fare, tariffs.fares, converter, ticketing, tariffs.miles, trail)
    governing = apply_minimum(with_fare, highest_of(hip, backhaul), "HIP")
    banded = apply_band(governing, chosen)
    charged = apply_all(
        banded,
        assess_component(banded, tariffs.surcharges, converter, ticketing, trail),
    )
    return _charge_stopovers(charged, itinerary, tariffs, ticketing, trail), said


def _rule_context(component: FareComponent, itinerary: Itinerary, ticketing: date) -> RuleContext:
    return RuleContext(itinerary=itinerary, component=component, ticketing=ticketing)


def _check_rules(
    component: FareComponent,
    itinerary: Itinerary,
    tariffs: Tariffs,
    ticketing: date,
    trail: Trace,
) -> list[str]:
    """Run the fare's own rule and return everything that refused."""
    if component.fare is None:
        return []
    rule = tariffs.rules.find(
        component.fare.carrier, component.fare.rule_number, component.fare.tariff_number
    )
    if rule is None:
        trail.note(
            Stage.RULE,
            f"no rule {component.fare.rule_number} filed for {component.fare.carrier}",
        )
        return []
    outcomes = evaluate(rule.provisions, _rule_context(component, itinerary, ticketing), trail)
    return list(refusals(outcomes))


def _charge_stopovers(
    component: FareComponent,
    itinerary: Itinerary,
    tariffs: Tariffs,
    ticketing: date,
    trail: Trace,
) -> FareComponent:
    """Add whatever the fare charges for the stopovers actually taken.

    This is a rule producing money rather than a verdict, which is why it
    happens here and not inside the rule check. It lands after the surcharges
    and before the unit minimums, in the same place a Q surcharge does.
    """
    if component.fare is None:
        return component
    rule = tariffs.rules.find(
        component.fare.carrier, component.fare.rule_number, component.fare.tariff_number
    )
    if rule is None:
        return component
    context = _rule_context(component, itinerary, ticketing)
    charged = component
    for provision in rule.provisions:
        if not isinstance(provision, Stopovers):
            continue
        amount = provision.charge_for(context)
        if amount > 0:
            trail.passed(Stage.SURCHARGE, f"stopover charge {amount} NUC", nuc=str(amount))
            charged = charged.with_surcharge(amount, "SO")
    return charged


def _shape_of(itinerary: Itinerary, excursions: tuple[SideTrip, ...], trail: Trace) -> JourneyType:
    """What kind of journey this is, once the excursions are set aside.

    Frankfurt, Vienna, Frankfurt, New York touches Frankfurt twice and reads
    as a circle trip while the excursion is still in it. Take the excursion
    out and it is the one way it always was.
    """
    if not excursions:
        return itinerary.journey_type()
    kind = strip_side_trips(itinerary, excursions).journey_type()
    trail.note(
        Stage.SHAPE,
        f"{len(excursions)} side trips set aside, leaving a {kind.value}",
        excursions=", ".join(str(trip) for trip in excursions),
    )
    return kind


def _default_breaks(
    itinerary: Itinerary,
    kind: JourneyType,
    excursions: tuple[SideTrip, ...] = (),
) -> tuple[int, ...]:
    """Break the fare where the journey turns round, and at every excursion.

    An excursion is its own component whatever else the journey does, so its
    edges are breaks even on a one way. It also breaks in the middle: an
    excursion leaves a point and comes back to it, which is a round trip in
    miniature, and pricing it whole asks the tariff for a fare from Frankfurt
    to Frankfurt that nobody has ever filed.

    The turnaround break is worked out on the stripped journey and put back in
    the full journey's own numbering, because a break index means nothing once
    the elements have moved.
    """
    points = set()
    for trip in excursions:
        if trip.first > 0:
            points.add(trip.first - 1)
        if trip.last < len(itinerary.elements) - 1:
            points.add(trip.last)
        inside = _excursion_break(trip)
        if inside is not None:
            points.add(inside)

    if kind is not JourneyType.ONE_WAY:
        turn = _turnaround_break(itinerary, excursions)
        if turn is not None:
            points.add(turn)

    return tuple(sorted(points))


def _excursion_break(trip: SideTrip) -> int | None:
    """Where an excursion turns round, in the full journey's numbering."""
    if len(trip.elements) < 2:
        return None
    turn = Itinerary(list(trip.elements)).turnaround_index()
    if turn <= 0 or turn >= len(trip.elements):
        return None
    return trip.first + turn - 1


def _turnaround_break(itinerary: Itinerary, excursions: tuple[SideTrip, ...]) -> int | None:
    """Where the journey turns round, counted in the full element list."""
    stripped = strip_side_trips(itinerary, excursions)
    turn = stripped.turnaround_index()
    if turn <= 0 or turn >= len(stripped.elements):
        return None

    kept = [
        index
        for index in range(len(itinerary.elements))
        if not any(trip.first <= index <= trip.last for trip in excursions)
    ]
    return kept[turn - 1]


def _assemble(
    components: list[FareComponent], kind: JourneyType, trace: Trace
) -> tuple[PricingUnit, ...]:
    """Group the components into pricing units.

    The shape of the journey decides the grouping only when the split fits
    it. A round trip cut into three components is not a round trip pricing
    unit, it is three one way units joined end on end, and it has to be
    priced as such rather than quietly losing the third component, which is
    what an earlier version of this did.
    """
    if not components:
        raise ConstructionError("nothing to assemble")
    if len(components) == 1:
        return (assemble_one_way(components[0], trace),)
    if kind is JourneyType.CIRCLE_TRIP and len(components) >= 3:
        return (assemble_circle_trip(tuple(components), trace),)
    if len(components) == 2 and kind is JourneyType.OPEN_JAW:
        return (PricingUnit(tuple(components), JourneyType.OPEN_JAW),)
    if len(components) == 2 and kind is JourneyType.ROUND_TRIP:
        return (assemble_round_trip(components[0], components[1], trace),)
    trace.note(
        Stage.SELECTION,
        f"{len(components)} components do not fit one {kind.value} unit, pricing end on end",
    )
    return tuple(assemble_one_way(component, trace) for component in components)


def _apply_unit_minimums(
    construction: Construction,
    unit: PricingUnit,
    tariffs: Tariffs,
    converter: Converter,
    ticketing: date,
    trace: Trace,
) -> Construction:
    circle = circle_trip_minimum(unit, tariffs.fares, converter, ticketing, trace)
    jaw = open_jaw_minimum(unit, tariffs.fares, converter, ticketing, trace)
    raised = highest_of(circle, jaw)
    if raised.applied:
        return construction.with_minimum(raised.added, raised.reason)
    return construction
