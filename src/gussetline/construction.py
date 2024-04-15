"""Building a price out of published fares, in neutral units.

The ladder is always the same. Each component takes a published fare, the
amount is converted into neutral units and truncated, surcharges are added in
neutral units, the components are added together, the minimum checks raise the
total if they have to, and the result is converted once into the selling
currency and rounded up to the fare unit.

Converting each component into the selling currency and adding those is the
classic wrong answer. It rounds up several times instead of once and comes out
a few units high, which looks exactly like a supplier being greedy and is
actually us.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from gussetline.components import FareComponent, PricingUnit
from gussetline.errors import ConstructionError
from gussetline.exchange import Converter
from gussetline.itinerary import JourneyType
from gussetline.money import Money, truncate_nuc
from gussetline.tariff import Fare, OwRt
from gussetline.trace import Stage, Trace


def fare_to_nuc(fare: Fare, converter: Converter, ticketing: date) -> Decimal:
    """One published fare in neutral units.

    A round trip half converts as filed and stays a half. Doubling happens
    when the pricing unit is assembled, not here, because a half that has
    already been doubled cannot be told from a one way and the minimum checks
    need to know which it is.
    """
    return converter.to_nuc(fare.amount, ticketing)


def price_component(
    component: FareComponent,
    fare: Fare,
    converter: Converter,
    ticketing: date,
    trace: Trace | None = None,
) -> FareComponent:
    """Attach a fare to a component and record the conversion."""
    nuc = fare_to_nuc(fare, converter, ticketing)
    if trace is not None:
        trace.passed(
            Stage.CONVERSION,
            f"{fare.fare_basis} converts to {nuc} NUC",
            filed=str(fare.amount),
            nuc=str(nuc),
        )
    return component.priced_with(fare, nuc)


@dataclass(frozen=True, slots=True)
class Construction:
    """A finished price, in neutral units, with the trail that built it."""

    units: tuple[PricingUnit, ...]
    selling_currency: str
    ticketing: date
    trace: Trace = field(default_factory=Trace)
    minimum_added: Decimal = Decimal(0)

    def __post_init__(self) -> None:
        if not self.units:
            raise ConstructionError("a construction needs a pricing unit")
        for unit in self.units:
            if not unit.is_priced:
                raise ConstructionError("every component has to carry a fare first")

    @property
    def components(self) -> tuple[FareComponent, ...]:
        return tuple(c for unit in self.units for c in unit.components)

    @property
    def base_nuc(self) -> Decimal:
        """The components added up, before any minimum check."""
        return truncate_nuc(sum((unit.total_nuc for unit in self.units), Decimal(0)))

    @property
    def total_nuc(self) -> Decimal:
        return truncate_nuc(self.base_nuc + self.minimum_added)

    def base_fare(self, converter: Converter) -> Money:
        """The one conversion into the selling currency, rounded once."""
        return converter.from_nuc(self.total_nuc, self.selling_currency, self.ticketing)

    def with_minimum(self, added: Decimal, reason: str) -> Construction:
        if added < 0:
            raise ConstructionError("a minimum check does not reduce a fare", added=str(added))
        self.trace.passed(Stage.MINIMUM, reason, added=str(added))
        return Construction(
            units=self.units,
            selling_currency=self.selling_currency,
            ticketing=self.ticketing,
            trace=self.trace,
            minimum_added=self.minimum_added + added,
        )

    def __str__(self) -> str:
        return f"{self.total_nuc} NUC into {self.selling_currency}"


def assemble_round_trip(
    outbound: FareComponent,
    inbound: FareComponent,
    trace: Trace | None = None,
) -> PricingUnit:
    """Group two components as a round trip, checking they are halves.

    A round trip made of two one way only fares is not a round trip, it is two
    one ways that happen to touch, and pricing it as a unit applies a minimum
    check the tariff never asked for.
    """
    for component in (outbound, inbound):
        if component.fare is None:
            raise ConstructionError("a round trip unit needs both components priced")
    assert outbound.fare is not None and inbound.fare is not None
    if outbound.fare.owrt is OwRt.ONE_WAY_ONLY or inbound.fare.owrt is OwRt.ONE_WAY_ONLY:
        raise ConstructionError(
            "a one way only fare cannot be half of a round trip",
            outbound=outbound.fare.fare_basis,
            inbound=inbound.fare.fare_basis,
        )
    if trace is not None:
        trace.passed(
            Stage.SELECTION,
            "round trip unit assembled",
            outbound=outbound.fare.fare_basis,
            inbound=inbound.fare.fare_basis,
        )
    return PricingUnit((outbound, inbound), JourneyType.ROUND_TRIP)


def assemble_one_way(component: FareComponent, trace: Trace | None = None) -> PricingUnit:
    """Group one component as a one way, checking the fare may stand alone."""
    if component.fare is None:
        raise ConstructionError("a one way unit needs its component priced")
    if component.fare.owrt is OwRt.ROUND_TRIP_HALF:
        raise ConstructionError(
            "a round trip half cannot stand as a one way",
            fare_basis=component.fare.fare_basis,
        )
    if trace is not None:
        trace.passed(Stage.SELECTION, "one way unit assembled", fare=component.fare.fare_basis)
    return PricingUnit((component,), JourneyType.ONE_WAY)


def assemble_circle_trip(
    components: tuple[FareComponent, ...],
    trace: Trace | None = None,
) -> PricingUnit:
    if len(components) < 3:
        raise ConstructionError("a circle trip needs three components", count=len(components))
    for component in components:
        if component.fare is None:
            raise ConstructionError("a circle trip unit needs every component priced")
    if trace is not None:
        trace.passed(Stage.SELECTION, "circle trip unit assembled", count=len(components))
    return PricingUnit(components, JourneyType.CIRCLE_TRIP)
