"""The mileage principle, which is the oldest surviving idea in the tariff.

A fare between two cities buys a maximum permitted mileage, not a route. Fly
further than the maximum and the fare goes up in five percent steps, and there
are only five steps. Come in at a hundred and twenty six percent of the
maximum and there is no fare at all, because the ladder stops at twenty five.

Two allowances soften it. Extra mileage allowance is filed per city pair and
comes off the flown total before the comparison. The ticketed point mileage
deduction takes off the mileage of certain intermediate points, which is how a
carrier makes its own hub free. Both are subtractions from the flown figure
and never additions to the maximum, and that matters because they are applied
in a fixed order and the result differs if you swap them.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import ROUND_CEILING, Decimal

from gussetline.distance import MileageTable
from gussetline.errors import MileageUnavailable
from gussetline.indicators import GlobalIndicator
from gussetline.trace import Stage, Trace

# The surcharge ladder. Percentage of the maximum, and what the fare is
# multiplied by. Nothing past twenty five is sellable on a mileage fare.
SURCHARGE_BANDS: tuple[tuple[int, int], ...] = (
    (100, 0),
    (105, 5),
    (110, 10),
    (115, 15),
    (120, 20),
    (125, 25),
)

MAX_SURCHARGE = 25


@dataclass(frozen=True, slots=True)
class MileageResult:
    """What the mileage check decided, and every figure it used."""

    flown: int
    maximum: int
    deduction: int
    surcharge_percent: int
    permitted: bool
    fallback_used: bool = False

    @property
    def chargeable(self) -> int:
        """Flown mileage after the allowances come off."""
        return max(0, self.flown - self.deduction)

    @property
    def percentage(self) -> int:
        """Chargeable as a percentage of the maximum, rounded up.

        Rounded up because the bands are inclusive at the top. A journey at
        a hundred and five point one percent is in the ten percent band, and
        rounding to nearest would put it in the five and undercharge.
        """
        if self.maximum <= 0:
            return 0
        raw = Decimal(self.chargeable) * 100 / Decimal(self.maximum)
        return int(raw.quantize(Decimal(1), rounding=ROUND_CEILING))

    @property
    def multiplier(self) -> Decimal:
        return Decimal(100 + self.surcharge_percent) / Decimal(100)

    @property
    def band(self) -> str:
        """How the surcharge is written on a ticket."""
        return f"{self.surcharge_percent}M" if self.surcharge_percent else "M"

    def surcharge_on(self, nuc: Decimal) -> Decimal:
        """The amount to add, not the total. Zero when nothing is owed."""
        if not self.surcharge_percent:
            return Decimal(0)
        return (nuc * Decimal(self.surcharge_percent) / Decimal(100)).quantize(Decimal("0.01"))

    def __str__(self) -> str:
        if not self.permitted:
            return f"{self.percentage} percent of maximum, over the ladder"
        return f"{self.percentage} percent of maximum, {self.band}"


def band_for(percentage: int) -> int | None:
    """The surcharge step a percentage falls into, or None if it is off the end."""
    for limit, surcharge in SURCHARGE_BANDS:
        if percentage <= limit:
            return surcharge
    return None


class ExtraMileageAllowance:
    """Filed deductions, by city pair and by intermediate point."""

    def __init__(self) -> None:
        self._pairs: dict[tuple[str, str], int] = {}
        self._points: dict[tuple[str, str, str], int] = {}

    def add_pair(self, origin: str, destination: str, miles: int) -> None:
        key = (origin.upper(), destination.upper())
        self._pairs[key] = miles
        self._pairs[(key[1], key[0])] = miles

    def add_point(self, origin: str, destination: str, via: str, miles: int) -> None:
        self._points[(origin.upper(), destination.upper(), via.upper())] = miles

    def deduction_for(self, origin: str, destination: str, via: Sequence[str]) -> int:
        """The total allowance, pair first and then every named point.

        Both are subtractions and they stack. A carrier that files a pair
        allowance and a hub deduction on the same market means both, which
        is the only reading that reproduces the fares it actually sells.
        """
        total = self._pairs.get((origin.upper(), destination.upper()), 0)
        for point in via:
            total += self._points.get((origin.upper(), destination.upper(), point.upper()), 0)
        return total


def check(
    origin: str,
    destination: str,
    via: Sequence[str],
    table: MileageTable,
    *,
    indicator: GlobalIndicator | None = None,
    allowances: ExtraMileageAllowance | None = None,
    trace: Trace | None = None,
) -> MileageResult:
    """Run the mileage principle over one fare component.

    The flown figure is the sum of the ticketed point mileages of each sector
    and not the great circle from end to end, because a routing through a hub
    is longer and that is the whole point of the check.
    """
    if not origin.strip() or not destination.strip():
        raise MileageUnavailable(
            "a mileage check needs both ends", origin=origin, destination=destination
        )
    points = [origin, *via, destination]

    flown = 0
    fallback = False
    for index in range(len(points) - 1):
        reading = table.tpm(points[index], points[index + 1], indicator)
        flown += reading.miles
        fallback = fallback or reading.is_fallback

    maximum_reading = table.tpm(origin, destination, indicator)
    maximum = int(maximum_reading.miles * Decimal("1.2"))
    fallback = fallback or maximum_reading.is_fallback

    deduction = 0
    if allowances is not None:
        deduction = allowances.deduction_for(origin, destination, via)

    provisional = MileageResult(flown, maximum, deduction, 0, True, fallback)
    surcharge = band_for(provisional.percentage)
    result = MileageResult(
        flown=flown,
        maximum=maximum,
        deduction=deduction,
        surcharge_percent=surcharge if surcharge is not None else MAX_SURCHARGE,
        permitted=surcharge is not None,
        fallback_used=fallback,
    )

    if trace is not None:
        if fallback:
            trace.note(Stage.MILEAGE, "a great circle stood in for a published mileage")
        if result.permitted:
            trace.passed(
                Stage.MILEAGE,
                str(result),
                flown=result.chargeable,
                maximum=result.maximum,
            )
        else:
            trace.failed_step(
                Stage.MILEAGE,
                "over the mileage ladder, no fare constructs",
                flown=result.chargeable,
                maximum=result.maximum,
            )
    return result


def maximum_permitted(
    origin: str, destination: str, table: MileageTable, indicator: GlobalIndicator | None = None
) -> int:
    """The maximum permitted mileage for a city pair.

    Published as twenty percent above the shortest ticketed point mileage,
    which is the convention the tariff has used since it was printed on paper
    and is why the number is never round.
    """
    return int(table.tpm(origin, destination, indicator).miles * Decimal("1.2"))
