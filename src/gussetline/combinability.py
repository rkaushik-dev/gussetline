"""Combinability, which decides whether two fares may sit on one ticket.

Every published fare says what it may be joined to. A fare filed for round
trip combination only cannot be half of a circle trip. A fare that permits
end on end combination may be joined to a second pricing unit but not
necessarily to one on another carrier. A fare with no combinability filed at
all combines with nothing and has to stand as a one way, which is the case
most systems get wrong because absent reads as permissive if nobody looks.

The check runs on the assembled pricing unit and not on the fares in
isolation. A pair of fares that are each combinable can still be an illegal
pair, because both sides have to permit the other and the tariff files them
separately.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from gussetline.carriers import normalise_code
from gussetline.components import PricingUnit
from gussetline.errors import CombinabilityError
from gussetline.itinerary import JourneyType
from gussetline.trace import Stage, Trace


@dataclass(frozen=True, slots=True)
class Combinability:
    """What one fare permits itself to be joined to.

    Everything defaults to refused. A filing the loader could not read
    produces a record that combines with nothing, which shows up as a
    refusal the desk can look at rather than as a price that is quietly a
    hundred euro light.
    """

    carrier: str
    fare_basis: str
    round_trip: bool = False
    circle_trip: bool = False
    open_jaw: bool = False
    double_open_jaw: bool = False
    end_on_end: bool = False
    same_carrier_only: bool = True
    same_rule_only: bool = False
    side_trip: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "fare_basis", self.fare_basis.strip().upper())

    def permits(self, kind: JourneyType) -> bool:
        if kind is JourneyType.ONE_WAY:
            return True
        if kind is JourneyType.ROUND_TRIP:
            return self.round_trip
        if kind is JourneyType.CIRCLE_TRIP:
            return self.circle_trip
        return self.open_jaw

    def __str__(self) -> str:
        permitted = [
            name
            for name, on in (
                ("RT", self.round_trip),
                ("CT", self.circle_trip),
                ("OJ", self.open_jaw),
                ("EOE", self.end_on_end),
            )
            if on
        ]
        return f"{self.carrier} {self.fare_basis} {'/'.join(permitted) or 'nothing'}"


class CombinabilityStore:
    """Combinability records by carrier and fare basis."""

    def __init__(self) -> None:
        self._records: dict[tuple[str, str], Combinability] = {}

    def add(self, record: Combinability) -> Combinability:
        self._records[(record.carrier, record.fare_basis)] = record
        return record

    def get(self, carrier: str, fare_basis: str) -> Combinability | None:
        return self._records.get((normalise_code(carrier), fare_basis.strip().upper()))

    def __len__(self) -> int:
        return len(self._records)


def check_unit(
    unit: PricingUnit,
    store: CombinabilityStore,
    trace: Trace | None = None,
) -> list[str]:
    """Every reason this unit may not be combined the way it has been.

    An empty list is the pass. A one way unit passes without a lookup,
    because there is nothing to combine it with yet, and whether it may be
    joined to a second unit is the end on end question and not this one.
    """
    refusals: list[str] = []
    if unit.kind is JourneyType.ONE_WAY:
        if trace is not None:
            trace.passed(Stage.COMBINABILITY, "a one way unit combines with itself")
        return refusals

    records: list[Combinability] = []
    for component in unit.components:
        if component.fare is None:
            raise CombinabilityError("combinability runs after the unit is priced")
        found = store.get(component.fare.carrier, component.fare.fare_basis)
        if found is None:
            refusals.append(
                f"{component.fare.carrier} {component.fare.fare_basis} has no combinability filed"
            )
            continue
        records.append(found)
        if not found.permits(unit.kind):
            refusals.append(f"{found.carrier} {found.fare_basis} does not permit {unit.kind.value}")

    if len(records) > 1:
        carriers = {r.carrier for r in records}
        if len(carriers) > 1 and any(r.same_carrier_only for r in records):
            refusals.append(
                "a fare filed same carrier only is combined with " + "/".join(sorted(carriers))
            )
        bases = {r.fare_basis for r in records}
        if len(bases) > 1 and any(r.same_rule_only for r in records):
            refusals.append(
                "a fare filed same rule only is combined with " + "/".join(sorted(bases))
            )

    if trace is not None:
        for refusal in refusals:
            trace.failed_step(Stage.COMBINABILITY, refusal)
        if not refusals:
            trace.passed(Stage.COMBINABILITY, f"{unit.kind.value} combination permitted")
    return refusals


def check_end_on_end(
    units: Sequence[PricingUnit],
    store: CombinabilityStore,
    trace: Trace | None = None,
) -> list[str]:
    """Whether several pricing units may sit on one ticket.

    A single unit needs no permission. Two or more do, and every fare in
    every unit has to carry it, not just the first one, because a passenger
    only has to find one fare that refuses for the whole ticket to be
    unissuable.
    """
    refusals: list[str] = []
    if len(units) < 2:
        if trace is not None:
            trace.passed(Stage.COMBINABILITY, "one pricing unit, nothing to join")
        return refusals

    for unit in units:
        for component in unit.components:
            if component.fare is None:
                raise CombinabilityError("combinability runs after the unit is priced")
            found = store.get(component.fare.carrier, component.fare.fare_basis)
            if found is None:
                refusals.append(
                    f"{component.fare.carrier} {component.fare.fare_basis} "
                    "has no combinability filed"
                )
                continue
            if not found.end_on_end:
                refusals.append(f"{found.carrier} {found.fare_basis} does not permit end on end")

    if trace is not None:
        for refusal in refusals:
            trace.failed_step(Stage.COMBINABILITY, refusal)
        if not refusals:
            trace.passed(Stage.COMBINABILITY, f"{len(units)} units joined end on end")
    return refusals
