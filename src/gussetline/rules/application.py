"""Categories 2 and 4, which say when the fare flies and what it flies on.

Category 2 is days of the week and times of day. It is filed per direction,
so a fare can be a weekday fare outbound and an any day fare home, and the
outbound and the return are checked separately against their own sectors.

Category 4 is flight application. It names flight numbers, or bans codeshares,
or insists on the filing carrier's own metal. The last of those is the one
that surprises people, because a codeshare sector marketed by the filing
carrier and operated by somebody else is the filing carrier's flight number
and is not its flight, and a fare filed own metal only refuses it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import time

from gussetline.carriers import normalise_code
from gussetline.errors import ValidationError
from gussetline.flights import Segment
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent
from gussetline.rules.stay import WEEKDAYS, _weekday_index


@dataclass(frozen=True, slots=True)
class TimeWindow:
    """A window inside a day. A window that wraps midnight is allowed."""

    start: time
    end: time

    def covers(self, when: time) -> bool:
        if self.start <= self.end:
            return self.start <= when <= self.end
        return when >= self.start or when <= self.end

    @property
    def wraps_midnight(self) -> bool:
        return self.start > self.end

    def __str__(self) -> str:
        return f"{self.start:%H%M} to {self.end:%H%M}"


@dataclass(frozen=True, slots=True)
class DayAndTime(Provision):
    """Category 2. Which days and hours the sectors may depart on."""

    days: tuple[str, ...] = field(default_factory=tuple)
    window: TimeWindow | None = None
    outbound_only: bool = False

    category: Category = Category.DAY_TIME

    def __post_init__(self) -> None:
        for name in self.days:
            _weekday_index(name)

    @property
    def is_empty(self) -> bool:
        return not self.days and self.window is None

    def _sectors(self, context: Context) -> tuple[Segment, ...]:
        if self.outbound_only:
            return context.component.segments
        return context.itinerary.segments

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        wanted = {_weekday_index(name) for name in self.days}
        for segment in self._sectors(context):
            when = segment.departure
            if wanted and when.weekday() not in wanted:
                return fails(
                    self.category,
                    f"{segment.designator} departs a "
                    f"{WEEKDAYS[when.weekday()]} and the fare wants "
                    f"{'/'.join(self.days)}",
                    sector=segment.designator,
                    day=WEEKDAYS[when.weekday()],
                )
            if self.window is not None and not self.window.covers(when.time()):
                return fails(
                    self.category,
                    f"{segment.designator} departs {when:%H%M} outside {self.window}",
                    sector=segment.designator,
                    window=str(self.window),
                )
        return passes(self.category, "every sector departs inside the filed days and hours")


@dataclass(frozen=True, slots=True)
class FlightApplication(Provision):
    """Category 4. Which flights the fare may be used on."""

    flights: tuple[str, ...] = field(default_factory=tuple)
    own_metal_only: bool = False
    no_codeshare: bool = False
    carrier: str | None = None

    category: Category = Category.FLIGHT_APPLICATION

    def __post_init__(self) -> None:
        if self.carrier is not None:
            object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "flights", tuple(f.strip().upper() for f in self.flights))
        if self.own_metal_only and self.carrier is None:
            raise ValidationError("own metal only has to say whose metal")

    @property
    def is_empty(self) -> bool:
        return not self.flights and not self.own_metal_only and not self.no_codeshare

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        for segment in context.component.segments:
            if self.flights and segment.designator not in self.flights:
                return fails(
                    self.category,
                    f"{segment.designator} is not on the filed flight list",
                    sector=segment.designator,
                    permitted=list(self.flights),
                )
            if self.no_codeshare and segment.is_codeshare:
                return fails(
                    self.category,
                    f"{segment.designator} is a codeshare and the fare refuses them",
                    sector=segment.designator,
                    operated_by=segment.operated_by,
                )
            if self.own_metal_only and segment.operated_by != self.carrier:
                return fails(
                    self.category,
                    f"{segment.designator} is operated by {segment.operated_by} "
                    f"and the fare wants {self.carrier} metal",
                    sector=segment.designator,
                    operated_by=segment.operated_by,
                )
        return passes(self.category, "every sector is on a permitted flight")
