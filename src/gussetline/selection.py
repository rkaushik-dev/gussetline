"""Choosing which published fare prices a component.

The tariff answers with everything filed on the market and most of it does not
apply. A fare is a candidate only once its dates, its cabin, its booking class,
its global indicator, its routing and its mileage have all been checked, and
the cheapest survivor wins, measured on the total after the mileage surcharge.

The surcharge is a percentage of the fare, so it cannot reorder the survivors
on one component, and carrying it here is still worth doing: the number that
leaves this module is compared against other components and against what the
supplier quoted, and a base fare that has not had its band applied is not
comparable to either.

Every fare that was thrown out keeps its reason. Nine times in ten a desk
question is not what did we price but why did we not price the fare the
supplier used, and the only cheap answer to that is a list.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from gussetline.components import FareComponent
from gussetline.distance import MileageTable
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter
from gussetline.indicators import indicator_matches
from gussetline.mileage import ExtraMileageAllowance, MileageResult
from gussetline.mileage import check as mileage_check
from gussetline.routing import RoutingStore
from gussetline.tariff import Fare, TariffStore
from gussetline.trace import Stage, Trace


@dataclass(frozen=True, slots=True)
class Candidate:
    """One fare that survived, and what it costs after the mileage ladder."""

    fare: Fare
    base_nuc: Decimal
    mileage: MileageResult

    @property
    def surcharge(self) -> Decimal:
        return self.mileage.surcharge_on(self.base_nuc)

    @property
    def total_nuc(self) -> Decimal:
        return self.base_nuc + self.surcharge

    @property
    def band(self) -> str:
        return self.mileage.band

    def __str__(self) -> str:
        return f"{self.fare.fare_basis} {self.total_nuc} NUC {self.band}"


@dataclass(frozen=True, slots=True)
class Selection:
    """The winner, the runners up and everything that was refused."""

    chosen: Candidate
    considered: tuple[Candidate, ...] = field(default_factory=tuple)
    rejected: tuple[tuple[str, str], ...] = field(default_factory=tuple)

    def reason_for(self, fare_basis: str) -> str | None:
        """Why one named fare was not used, or None if it was not refused."""
        wanted = fare_basis.strip().upper()
        for basis, reason in self.rejected:
            if basis == wanted:
                return reason
        return None

    @property
    def runner_up(self) -> Candidate | None:
        others = [c for c in self.considered if c is not self.chosen]
        if not others:
            return None
        return min(others, key=lambda c: c.total_nuc)


def select(
    component: FareComponent,
    tariff: TariffStore,
    routings: RoutingStore,
    miles: MileageTable,
    converter: Converter,
    ticketing: date,
    *,
    allowances: ExtraMileageAllowance | None = None,
    trace: Trace | None = None,
) -> Selection:
    """Pick the cheapest fare that survives every check, or say why none did."""
    if not component.segments:
        raise NoFareFound("a component with no flown sector cannot be priced")

    carrier = component.governing_carrier
    departure = component.segments[0].departure.date()
    indicator = component.indicator
    cities = [component.board_point, *(c for c in component.via_points), component.off_point]
    carriers: list[str | None] = [s.marketing_carrier for s in component.segments]

    filed = tariff.search(
        carrier,
        component.board_point,
        component.off_point,
        ticketing=ticketing,
        departure=departure,
    )
    if not filed:
        raise NoFareFound(
            "nothing published for this component",
            carrier=carrier,
            origin=component.board_point,
            destination=component.off_point,
        )

    survivors: list[Candidate] = []
    rejected: list[tuple[str, str]] = []

    for fare in filed:
        if not indicator_matches(fare.global_indicator, indicator):
            rejected.append(
                (
                    fare.fare_basis,
                    f"filed {fare.global_indicator.value}, journey is {indicator.value}",
                )
            )
            continue
        if not _class_available(component, fare):
            rejected.append((fare.fare_basis, f"no sector booked in {fare.booking_class}"))
            continue
        if not routings.permits(fare.carrier, fare.routing_number, cities, carriers):
            rejected.append((fare.fare_basis, f"routing {fare.routing_number} refuses the path"))
            continue

        result = mileage_check(
            component.board_point,
            component.off_point,
            list(component.via_points),
            miles,
            indicator=indicator,
            allowances=allowances,
        )
        if not result.permitted:
            rejected.append((fare.fare_basis, "over the mileage ladder"))
            continue

        survivors.append(Candidate(fare, converter.to_nuc(fare.amount, ticketing), result))

    if not survivors:
        if trace is not None:
            for basis, reason in rejected:
                trace.failed_step(Stage.SELECTION, f"{basis} refused, {reason}")
        raise NoFareFound(
            "every published fare was refused",
            carrier=carrier,
            origin=component.board_point,
            destination=component.off_point,
            refused=len(rejected),
        )

    chosen = min(survivors, key=lambda c: (c.total_nuc, c.fare.fare_basis))
    if trace is not None:
        trace.passed(
            Stage.SELECTION,
            f"took {chosen.fare.fare_basis} at {chosen.total_nuc} NUC",
            considered=len(survivors),
            refused=len(rejected),
        )
    return Selection(chosen, tuple(survivors), tuple(rejected))


def _class_available(component: FareComponent, fare: Fare) -> bool:
    """Whether some sector on the component is booked in the fare's class.

    Checking every sector would be stricter and would be wrong, because a
    component priced on a through fare frequently has a feeder in a different
    class and the tariff does not care.
    """
    return any(segment.booking_class == fare.booking_class for segment in component.segments)


def apply_fare(component: FareComponent, selection: Selection) -> FareComponent:
    """Attach the winning fare at its filed amount, with no band on it yet.

    The band is left off because the minimum checks run against the base and
    can raise it, and the mileage percentage applies to whatever fare ends up
    governing rather than to the one that was selected.
    """
    return component.priced_with(selection.chosen.fare, selection.chosen.base_nuc)


def apply_band(component: FareComponent, selection: Selection) -> FareComponent:
    """Put the mileage surcharge on, measured against the fare as it now is."""
    result = selection.chosen.mileage
    amount = result.surcharge_on(component.total_nuc)
    if amount <= 0:
        return component
    return component.with_surcharge(amount, result.band)


def apply_selection(component: FareComponent, selection: Selection) -> FareComponent:
    """Fare and band in one step, for a component nothing else will touch."""
    return apply_band(apply_fare(component, selection), selection)
