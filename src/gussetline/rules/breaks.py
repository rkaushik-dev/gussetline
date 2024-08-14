"""Categories 8 and 9, stopovers and transfers.

The two categories count the same points and count them differently, and
which bucket a point falls into was decided by a clock reading in the
itinerary rather than by anything in the rule. A fare that permits one
stopover and four transfers is perfectly happy with a journey that has five
intermediate points, as long as four of them are under the ground time
threshold, and it refuses the identical routing if one connection runs long.

That is the whole shape of this file. The rules count, the itinerary decides
what is being counted, and neither of them can be checked without the other.

Charged stopovers are the second thing. A fare permits some number free and
some number for money, and the money is added to the component in neutral
units, so a stopover charge is a fare surcharge and not a fee. It goes on
before the minimum checks like every other fare surcharge.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal

from gussetline.errors import ValidationError
from gussetline.geography import city_of
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


@dataclass(frozen=True, slots=True)
class Stopovers(Provision):
    """Category 8. How many breaks over the threshold are permitted."""

    permitted: int | None = None
    free: int = 0
    charge_nuc: Decimal = Decimal(0)
    at_points: tuple[str, ...] = field(default_factory=tuple)
    per_direction: bool = False

    category: Category = Category.STOPOVERS

    def __post_init__(self) -> None:
        if self.permitted is not None and self.permitted < 0:
            raise ValidationError("a stopover allowance is not negative")
        if self.free < 0:
            raise ValidationError("a free stopover count is not negative")
        if self.charge_nuc < 0:
            raise ValidationError("a stopover charge is not negative")
        object.__setattr__(self, "at_points", tuple(city_of(p) for p in self.at_points))

    @property
    def is_empty(self) -> bool:
        return self.permitted is None and not self.at_points

    def _taken(self, context: Context) -> tuple[str, ...]:
        points = context.itinerary.stopovers()
        if not self.per_direction:
            return tuple(city_of(p) for p in points)
        inside = {city_of(p) for p in context.component.via_points}
        return tuple(city_of(p) for p in points if city_of(p) in inside)

    def charge_for(self, context: Context) -> Decimal:
        """What the stopovers on this journey cost, over and above the free ones."""
        if self.charge_nuc <= 0:
            return Decimal(0)
        taken = len(self._taken(context))
        chargeable = max(0, taken - self.free)
        return self.charge_nuc * Decimal(chargeable)

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        taken = self._taken(context)

        if self.at_points:
            outside = [p for p in taken if p not in self.at_points]
            if outside:
                return fails(
                    self.category,
                    f"stopover at {outside[0]} and the fare permits only "
                    f"{'/'.join(self.at_points)}",
                    at=outside[0],
                    permitted=list(self.at_points),
                )

        if self.permitted is not None and len(taken) > self.permitted:
            return fails(
                self.category,
                f"{len(taken)} stopovers and the fare permits {self.permitted}",
                taken=len(taken),
                permitted=self.permitted,
            )

        return passes(
            self.category,
            f"{len(taken)} stopovers, {max(0, len(taken) - self.free)} chargeable",
            taken=len(taken),
        )


@dataclass(frozen=True, slots=True)
class Transfers(Provision):
    """Category 9. How many breaks under the threshold are permitted."""

    permitted: int | None = None
    at_points: tuple[str, ...] = field(default_factory=tuple)
    online_only: bool = False
    per_direction: bool = True

    category: Category = Category.TRANSFERS

    def __post_init__(self) -> None:
        if self.permitted is not None and self.permitted < 0:
            raise ValidationError("a transfer allowance is not negative")
        object.__setattr__(self, "at_points", tuple(city_of(p) for p in self.at_points))

    @property
    def is_empty(self) -> bool:
        return self.permitted is None and not self.at_points and not self.online_only

    def _taken(self, context: Context) -> tuple[str, ...]:
        points = context.itinerary.connections()
        if not self.per_direction:
            return tuple(city_of(p) for p in points)
        inside = {city_of(p) for p in context.component.via_points}
        return tuple(city_of(p) for p in points if city_of(p) in inside)

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        taken = self._taken(context)

        if self.at_points:
            outside = [p for p in taken if p not in self.at_points]
            if outside:
                return fails(
                    self.category,
                    f"transfer at {outside[0]} and the fare permits only "
                    f"{'/'.join(self.at_points)}",
                    at=outside[0],
                    permitted=list(self.at_points),
                )

        if self.permitted is not None and len(taken) > self.permitted:
            return fails(
                self.category,
                f"{len(taken)} transfers and the fare permits {self.permitted}",
                taken=len(taken),
                permitted=self.permitted,
            )

        if self.online_only:
            carriers = {s.marketing_carrier for s in context.component.segments}
            if len(carriers) > 1:
                return fails(
                    self.category,
                    "the fare permits online transfers only and this one is interline",
                    carriers=sorted(carriers),
                )

        return passes(self.category, f"{len(taken)} transfers", taken=len(taken))
