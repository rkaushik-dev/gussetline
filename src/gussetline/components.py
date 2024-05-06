"""Fare components and pricing units, which are not the same thing.

A fare component is the run of sectors one published fare covers, from one
fare break point to the next. A pricing unit is the group of components that
get priced together and share a minimum check, and it is the pricing unit that
decides whether a round trip minimum applies, not the component.

Frankfurt to New York and back is two components in one round trip pricing
unit. Frankfurt to New York, New York to Los Angeles and Los Angeles home is
three components in one circle trip pricing unit. Frankfurt to New York on one
ticket and New York to Boston on another is two components in two one way
pricing units, and nothing about the second one can make the first cheaper.

Getting the grouping wrong does not produce a wrong looking answer. It
produces a plausible answer that is out by whatever the minimum check would
have added.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from decimal import Decimal

from gussetline.errors import ConstructionError, ValidationError
from gussetline.flights import Segment
from gussetline.geography import city_of, same_country
from gussetline.indicators import GlobalIndicator, global_indicator
from gussetline.itinerary import Element, JourneyType, SurfaceSector
from gussetline.tariff import Fare


@dataclass(frozen=True, slots=True)
class Charge:
    """One addition to a component, and what put it there.

    A single running total was enough until the fare calculation line had to
    be written, and then it was not: the line shows a Q surcharge in a
    different place from a mileage surcharge and does not show a higher
    intermediate point finding as an addition at all. A total cannot be taken
    apart afterwards, so the parts are kept.
    """

    reason: str
    nuc: Decimal

    def __str__(self) -> str:
        return f"{self.reason} {self.nuc}"


@dataclass(frozen=True, slots=True)
class FareComponent:
    """One published fare and the sectors it covers."""

    elements: tuple[Element, ...]
    fare: Fare | None = None
    nuc: Decimal | None = None
    charges: tuple[Charge, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.elements:
            raise ValidationError("a fare component covers at least one sector")
        for index in range(len(self.elements) - 1):
            if self.elements[index].destination != self.elements[index + 1].origin:
                raise ValidationError("a fare component has to join up", at=index)

    @property
    def origin(self) -> str:
        return self.elements[0].origin

    @property
    def destination(self) -> str:
        return self.elements[-1].destination

    @property
    def board_point(self) -> str:
        """The city the fare is published from."""
        return city_of(self.origin)

    @property
    def off_point(self) -> str:
        return city_of(self.destination)

    @property
    def via_points(self) -> tuple[str, ...]:
        return tuple(e.destination for e in self.elements[:-1])

    @property
    def segments(self) -> tuple[Segment, ...]:
        return tuple(e for e in self.elements if isinstance(e, Segment))

    @property
    def carriers(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(s.marketing_carrier for s in self.segments))

    @property
    def governing_carrier(self) -> str:
        """Whose fare prices the component.

        The carrier of the first international sector governs. On a component
        with no international sector the first carrier governs, which is the
        same answer arrived at by a shorter road.
        """
        if not self.segments:
            raise ConstructionError("a component with no flown sector has no carrier")
        for segment in self.segments:
            if not same_country(segment.origin, segment.destination):
                return segment.marketing_carrier
        return self.segments[0].marketing_carrier

    @property
    def indicator(self) -> GlobalIndicator:
        return global_indicator(self.origin, self.destination, self.via_points)

    @property
    def has_surface(self) -> bool:
        return any(isinstance(e, SurfaceSector) for e in self.elements)

    @property
    def surcharges(self) -> Decimal:
        return sum((charge.nuc for charge in self.charges), Decimal(0))

    @property
    def notes(self) -> tuple[str, ...]:
        return tuple(charge.reason for charge in self.charges)

    @property
    def total_nuc(self) -> Decimal:
        if self.nuc is None:
            raise ConstructionError("this component has not been priced yet")
        return self.nuc + self.surcharges

    def charge_for(self, reason: str) -> Decimal:
        """What one named reason added. Zero when it did not fire."""
        return sum((c.nuc for c in self.charges if c.reason == reason), Decimal(0))

    def priced_with(self, fare: Fare, nuc: Decimal) -> FareComponent:
        return FareComponent(
            elements=self.elements,
            fare=fare,
            nuc=nuc,
            charges=self.charges,
        )

    def with_surcharge(self, amount: Decimal, reason: str) -> FareComponent:
        return FareComponent(
            elements=self.elements,
            fare=self.fare,
            nuc=self.nuc,
            charges=(*self.charges, Charge(reason, amount)),
        )

    def __str__(self) -> str:
        basis = self.fare.fare_basis if self.fare else "unpriced"
        return f"{self.board_point}{self.off_point} {basis}"


@dataclass(frozen=True, slots=True)
class PricingUnit:
    """The components that get priced and minimum checked together."""

    components: tuple[FareComponent, ...]
    kind: JourneyType

    def __post_init__(self) -> None:
        if not self.components:
            raise ValidationError("a pricing unit needs a component")
        if self.kind is JourneyType.ROUND_TRIP and len(self.components) != 2:
            raise ValidationError(
                "a round trip pricing unit is exactly two components",
                count=len(self.components),
            )
        if self.kind is JourneyType.ONE_WAY and len(self.components) != 1:
            raise ValidationError(
                "a one way pricing unit is exactly one component",
                count=len(self.components),
            )
        if self.kind is JourneyType.CIRCLE_TRIP and len(self.components) < 3:
            raise ValidationError(
                "a circle trip pricing unit is three components or more",
                count=len(self.components),
            )

    @property
    def origin(self) -> str:
        return self.components[0].origin

    @property
    def destination(self) -> str:
        return self.components[-1].destination

    @property
    def turnaround(self) -> str:
        """Where the unit turns round. A one way does not have one."""
        if self.kind is JourneyType.ONE_WAY:
            raise ConstructionError("a one way pricing unit does not turn round")
        return self.components[0].destination

    @property
    def total_nuc(self) -> Decimal:
        return sum((c.total_nuc for c in self.components), Decimal(0))

    @property
    def is_priced(self) -> bool:
        return all(c.nuc is not None for c in self.components)

    def replace(self, index: int, component: FareComponent) -> PricingUnit:
        parts = list(self.components)
        parts[index] = component
        return PricingUnit(tuple(parts), self.kind)

    def __len__(self) -> int:
        return len(self.components)

    def __str__(self) -> str:
        return f"{self.kind.value} " + " ".join(str(c) for c in self.components)


def split_at(elements: Sequence[Element], breaks: Sequence[int]) -> tuple[FareComponent, ...]:
    """Cut a run of elements into components at the given indices.

    ``breaks`` holds the index of the last element of each component except
    the final one, so a two component split of four elements at [1] gives two
    and two. The indices have to climb and stay inside the run, because an
    out of order break silently produces an empty component and a component
    that covers the whole journey twice.
    """
    if not elements:
        raise ValidationError("nothing to split")
    last = -1
    for point in breaks:
        if point <= last:
            raise ValidationError("fare break points have to climb", at=point)
        if point >= len(elements) - 1:
            raise ValidationError("a fare break past the end", at=point)
        last = point

    parts: list[FareComponent] = []
    start = 0
    for point in breaks:
        parts.append(FareComponent(tuple(elements[start : point + 1])))
        start = point + 1
    parts.append(FareComponent(tuple(elements[start:])))
    return tuple(parts)
