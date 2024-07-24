"""Categories 3, 11 and 14, which are all about dates and none about the same one.

Seasonality says which dates the fare is priced for, and it is checked against
the date the first sector of the component departs. Blackouts say which dates
travel may not happen at all, and they are checked against every sector,
because a journey that flies out before a blackout and home during it is
blacked out. Travel dates say when travel has to start and finish, and they
are checked against the ends of the whole journey rather than of the
component.

Three categories, three different readings of the same itinerary, and getting
them confused produces a fare that prices in the wrong season and passes every
test written by somebody who made the same mistake twice.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date

from gussetline.errors import ValidationError
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


@dataclass(frozen=True, slots=True)
class DateRange:
    """A closed range of dates, both ends included."""

    start: date
    end: date

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValidationError(
                "a date range closes before it opens",
                start=self.start.isoformat(),
                end=self.end.isoformat(),
            )

    def covers(self, when: date) -> bool:
        return self.start <= when <= self.end

    def overlaps(self, other: DateRange) -> bool:
        return self.start <= other.end and other.start <= self.end

    def __str__(self) -> str:
        return f"{self.start.isoformat()} to {self.end.isoformat()}"


@dataclass(frozen=True, slots=True)
class Seasonality(Provision):
    """Category 3. The fare is priced for these dates and no others."""

    ranges: tuple[DateRange, ...] = field(default_factory=tuple)
    season: str = ""

    category: Category = Category.SEASONALITY

    def evaluate(self, context: Context) -> Outcome:
        if not self.ranges:
            return silent(self.category)
        when = context.departure
        for window in self.ranges:
            if window.covers(when):
                return passes(
                    self.category,
                    f"{self.season or 'season'} covers {when.isoformat()}",
                    window=str(window),
                )
        return fails(
            self.category,
            f"{when.isoformat()} is outside the {self.season or 'filed'} season",
            departure=when.isoformat(),
            windows=[str(w) for w in self.ranges],
        )


@dataclass(frozen=True, slots=True)
class Blackout(Provision):
    """Category 11. No sector may fly on these dates."""

    ranges: tuple[DateRange, ...] = field(default_factory=tuple)

    category: Category = Category.BLACKOUTS

    def evaluate(self, context: Context) -> Outcome:
        if not self.ranges:
            return silent(self.category)
        for segment in context.itinerary.segments:
            when = segment.departure.date()
            for window in self.ranges:
                if window.covers(when):
                    return fails(
                        self.category,
                        f"{segment.designator} flies {when.isoformat()} inside a blackout",
                        sector=segment.designator,
                        blackout=str(window),
                    )
        return passes(self.category, "no sector flies in a blackout")


@dataclass(frozen=True, slots=True)
class TravelDates(Provision):
    """Category 14. When travel has to start, and when it has to be over."""

    first_departure: date | None = None
    last_departure: date | None = None
    complete_by: date | None = None

    category: Category = Category.TRAVEL_RESTRICTIONS

    def __post_init__(self) -> None:
        if (
            self.first_departure is not None
            and self.last_departure is not None
            and self.last_departure < self.first_departure
        ):
            raise ValidationError("the departure window closes before it opens")

    @property
    def is_empty(self) -> bool:
        return (
            self.first_departure is None
            and self.last_departure is None
            and self.complete_by is None
        )

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        out = context.journey_departure
        if self.first_departure is not None and out < self.first_departure:
            return fails(
                self.category,
                f"travel may not start before {self.first_departure.isoformat()}",
                earliest=self.first_departure.isoformat(),
                departure=out.isoformat(),
            )
        if self.last_departure is not None and out > self.last_departure:
            return fails(
                self.category,
                f"travel may not start after {self.last_departure.isoformat()}",
                latest=self.last_departure.isoformat(),
                departure=out.isoformat(),
            )

        if self.complete_by is not None:
            done = context.itinerary.arrival.date()
            if done > self.complete_by:
                return fails(
                    self.category,
                    f"travel has to be over by {self.complete_by.isoformat()} "
                    f"and it ends {done.isoformat()}",
                    complete_by=self.complete_by.isoformat(),
                    ends=done.isoformat(),
                )

        return passes(self.category, "inside the filed travel dates")


def ranges_from(pairs: Sequence[tuple[str, str]]) -> tuple[DateRange, ...]:
    """Read the shape a loader produces into date ranges."""
    return tuple(
        DateRange(date.fromisoformat(start), date.fromisoformat(end)) for start, end in pairs
    )
