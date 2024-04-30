"""Q surcharges, which are part of the fare and are not a tax.

A Q surcharge is filed against a market and added to the fare component in
neutral units before any minimum check. That ordering is the whole reason this
is a separate file. Add it after the minimum checks and a component that
should have been raised to the Chicago fare quietly is not, because the
surcharge already carried it past the comparison.

There are two shapes. A component surcharge is charged once for the whole
component however many sectors it has. A sector surcharge is charged for each
sector that matches, so a component with two matching sectors pays twice. The
filings look nearly identical and getting the shape wrong is a doubling error
that only shows on connecting itineraries.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.carriers import normalise_code
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.exchange import Converter
from gussetline.geography import city_of
from gussetline.money import Money
from gussetline.tariff import FAR_FUTURE
from gussetline.trace import Stage, Trace


class Scope(str, Enum):
    """How many times a filing is charged."""

    COMPONENT = "component"
    SECTOR = "sector"


@dataclass(frozen=True, slots=True)
class QSurcharge:
    """One filed surcharge and the market it attaches to."""

    carrier: str
    origin: str
    destination: str
    amount: Money
    scope: Scope = Scope.COMPONENT
    reason: str = "Q"
    directional: bool = False
    first_travel: date = date(1900, 1, 1)
    last_travel: date = FAR_FUTURE

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "origin", city_of(self.origin))
        object.__setattr__(self, "destination", city_of(self.destination))
        if self.origin == self.destination:
            raise ValidationError("a surcharge covers a market", point=self.origin)
        if self.amount.amount <= 0:
            raise ValidationError("a surcharge is positive", amount=str(self.amount))
        if self.last_travel < self.first_travel:
            raise ValidationError("the travel window closes before it opens")

    def covers(self, origin: str, destination: str) -> bool:
        left, right = city_of(origin), city_of(destination)
        if (left, right) == (self.origin, self.destination):
            return True
        if self.directional:
            return False
        return (right, left) == (self.origin, self.destination)

    def flyable_on(self, departure: date) -> bool:
        return self.first_travel <= departure <= self.last_travel

    def __str__(self) -> str:
        return f"{self.reason} {self.origin}{self.destination} {self.amount}"


@dataclass(frozen=True, slots=True)
class AppliedSurcharge:
    """A surcharge that was charged, and how many times."""

    surcharge: QSurcharge
    times: int
    nuc: Decimal

    @property
    def reason(self) -> str:
        return self.surcharge.reason

    def __str__(self) -> str:
        if self.times == 1:
            return f"{self.reason} {self.nuc} NUC"
        return f"{self.reason} {self.nuc} NUC over {self.times} sectors"


class SurchargeTable:
    """Filed surcharges, looked up by carrier."""

    def __init__(self) -> None:
        self._by_carrier: dict[str, list[QSurcharge]] = {}

    def add(self, surcharge: QSurcharge) -> QSurcharge:
        self._by_carrier.setdefault(surcharge.carrier, []).append(surcharge)
        return surcharge

    def add_many(self, surcharges: Iterable[QSurcharge]) -> None:
        for surcharge in surcharges:
            self.add(surcharge)

    def for_carrier(self, carrier: str) -> tuple[QSurcharge, ...]:
        return tuple(self._by_carrier.get(normalise_code(carrier), ()))

    def __len__(self) -> int:
        return sum(len(rows) for rows in self._by_carrier.values())


def assess_component(
    component: FareComponent,
    table: SurchargeTable,
    converter: Converter,
    ticketing: date,
    trace: Trace | None = None,
) -> tuple[AppliedSurcharge, ...]:
    """Every surcharge this component owes, counted the right number of times."""
    if component.fare is None:
        raise ValidationError("a surcharge check runs after the component is priced")

    carrier = component.fare.carrier
    departure = component.segments[0].departure.date()
    found: list[AppliedSurcharge] = []

    for surcharge in table.for_carrier(carrier):
        if not surcharge.flyable_on(departure):
            continue
        if surcharge.scope is Scope.COMPONENT:
            if not surcharge.covers(component.origin, component.destination):
                continue
            times = 1
        else:
            times = sum(
                1
                for element in component.elements
                if surcharge.covers(element.origin, element.destination)
            )
            if not times:
                continue
        per = converter.to_nuc(surcharge.amount, ticketing)
        applied = AppliedSurcharge(surcharge, times, per * Decimal(times))
        found.append(applied)
        if trace is not None:
            trace.passed(Stage.SURCHARGE, str(applied), times=times)

    return tuple(found)


def total_nuc(applied: Iterable[AppliedSurcharge]) -> Decimal:
    return sum((item.nuc for item in applied), Decimal(0))


def apply_all(component: FareComponent, applied: Iterable[AppliedSurcharge]) -> FareComponent:
    """Put the surcharges onto the component, each under its own note."""
    result = component
    for item in applied:
        result = result.with_surcharge(item.nuc, item.reason)
    return result
