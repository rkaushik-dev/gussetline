"""Categories 6 and 7, how long the passenger has to stay and may stay.

Both are measured from a point in the journey and the points are different.
Minimum stay runs from the arrival at the turnaround, because that is when
the stay starts. Maximum stay runs from the departure from origin, because
that is when the ticket starts. Measuring both from the same end is the
mistake, and it is invisible on a short trip and wrong by a day on a long one.

The Sunday rule is the other thing worth being careful about. A minimum stay
filed as Sunday does not mean seven days, it means the passenger has to be
away over a Sunday, and a Friday to Monday trip satisfies it while a Monday
to Friday trip four days longer does not.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

from gussetline.errors import ValidationError
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent

WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


def _weekday_index(name: str) -> int:
    key = name.strip().lower()[:3]
    if key not in WEEKDAYS:
        raise ValidationError("not a day of the week", value=name)
    return WEEKDAYS.index(key)


def crosses_weekday(start: date, end: date, weekday: int) -> bool:
    """Whether a stay from one date to another covers a given weekday.

    Inclusive of the start and exclusive of the end, because the passenger
    is away on the night of the start day and has gone home on the end day.
    A stay of a week or more always covers every weekday and short circuits.
    """
    if end <= start:
        return False
    if (end - start).days >= 7:
        return True
    current = start
    while current < end:
        if current.weekday() == weekday:
            return True
        current += timedelta(days=1)
    return False


@dataclass(frozen=True, slots=True)
class MinimumStay(Provision):
    """Category 6. Days, or a weekday that has to be crossed, or neither."""

    days: int | None = None
    weekday: str | None = None

    category: Category = Category.MINIMUM_STAY

    def __post_init__(self) -> None:
        if self.days is not None and self.days < 0:
            raise ValidationError("a minimum stay is not negative", days=self.days)
        if self.weekday is not None:
            _weekday_index(self.weekday)

    @property
    def is_empty(self) -> bool:
        return self.days is None and self.weekday is None

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        home = context.return_departure
        if home is None:
            return passes(self.category, "one way, no stay to measure")

        arrived = context.turnaround_arrival
        if arrived is None:
            return passes(self.category, "no turnaround, no stay to measure")
        stayed = (home - arrived).days

        if self.days is not None and stayed < self.days:
            return fails(
                self.category,
                f"minimum stay is {self.days} nights and the trip is {stayed}",
                needed=self.days,
                had=stayed,
            )

        if self.weekday is not None:
            wanted = _weekday_index(self.weekday)
            if not crosses_weekday(arrived, home, wanted):
                return fails(
                    self.category,
                    f"minimum stay wants a {self.weekday} and the trip does not cross one",
                    weekday=self.weekday,
                )

        return passes(self.category, f"stayed {stayed} nights", nights=stayed)


@dataclass(frozen=True, slots=True)
class MaximumStay(Provision):
    """Category 7. Days or months from the start of the journey."""

    days: int | None = None
    months: int | None = None
    from_turnaround: bool = False

    category: Category = Category.MAXIMUM_STAY

    def __post_init__(self) -> None:
        if self.days is not None and self.days <= 0:
            raise ValidationError("a maximum stay is positive", days=self.days)
        if self.months is not None and self.months <= 0:
            raise ValidationError("a maximum stay is positive", months=self.months)

    @property
    def is_empty(self) -> bool:
        return self.days is None and self.months is None

    def limit_from(self, start: date) -> date:
        """The last date travel may still be under way."""
        latest = start
        if self.days is not None:
            latest = start + timedelta(days=self.days)
        if self.months is not None:
            month = start.month - 1 + self.months
            year = start.year + month // 12
            month = month % 12 + 1
            day = min(start.day, _days_in(year, month))
            candidate = date(year, month, day)
            latest = max(latest, candidate) if self.days is not None else candidate
        return latest

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        home = context.return_departure
        if home is None:
            return passes(self.category, "one way, no stay to measure")
        start = context.journey_departure
        if self.from_turnaround:
            arrived = context.turnaround_arrival
            if arrived is None:
                return passes(self.category, "no turnaround, no stay to measure")
            start = arrived

        latest = self.limit_from(start)
        if home > latest:
            return fails(
                self.category,
                f"maximum stay ends {latest.isoformat()} and the return is {home.isoformat()}",
                latest=latest.isoformat(),
                returned=home.isoformat(),
            )
        return passes(self.category, f"return is inside {latest.isoformat()}")


def _days_in(year: int, month: int) -> int:
    if month == 12:
        return 31
    return (date(year, month + 1, 1) - timedelta(days=1)).day
