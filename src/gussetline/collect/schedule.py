"""What to poll and when, which is where the budget is actually spent.

A thousand shopping requests a day and nine thousand ticketed itineraries on
the books. Polling everything daily is off by a factor of nine, so the
schedule has to decide, and the two things it decides on are how close the
departure is and whether the price has been moving.

Closeness matters because fares move most in the last three weeks and a drop
found the day before departure is worth as much as one found in March, while
a drop found after departure is worth nothing at all. So the cadence tightens
as the date approaches and stops dead once it has passed.

Movement matters because a market that has sat at the same number for six
weeks will probably sit there for a seventh. Every poll that finds no change
widens the interval a little, and the first poll that finds one resets it. The
widening is capped, because a market that has been quiet for a year can still
drop the week before departure and a schedule that has backed off to monthly
will not see it.

Both of those are readings of history rather than settings, which is why this
takes the last observation rather than a configuration file.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime

from gussetline.errors import ValidationError
from gussetline.geography import city_of

# Days to departure, and how many days between polls at that distance.
CADENCE_BANDS: tuple[tuple[int, int], ...] = (
    (3, 1),
    (21, 2),
    (60, 4),
    (120, 7),
    (365, 14),
)

# However quiet a market has been, never go longer than this between polls.
MAX_INTERVAL_DAYS = 21


@dataclass(frozen=True, slots=True)
class Market:
    """A city pair, folded, because the tariff and the schedule agree on cities."""

    origin: str
    destination: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "origin", city_of(self.origin))
        object.__setattr__(self, "destination", city_of(self.destination))
        if self.origin == self.destination:
            raise ValidationError("a market goes between two places", point=self.origin)

    @property
    def key(self) -> str:
        return f"{self.origin}-{self.destination}"

    def __str__(self) -> str:
        return self.key


def base_interval(days_out: int) -> int:
    """Days between polls at a given distance from departure."""
    for limit, interval in CADENCE_BANDS:
        if days_out <= limit:
            return interval
    return CADENCE_BANDS[-1][1]


def interval_for(days_out: int, quiet_polls: int) -> int:
    """The cadence, widened by a run of polls that found nothing.

    The widening is one extra day per two quiet polls, which is gentle enough
    that a market has to be genuinely still before it slows much, and capped
    so nothing ever falls off the schedule.
    """
    if quiet_polls < 0:
        raise ValidationError("a quiet count is not negative", quiet=quiet_polls)
    widened = base_interval(days_out) + quiet_polls // 2
    return min(widened, MAX_INTERVAL_DAYS)


@dataclass
class Series:
    """What the schedule remembers about one market and departure date."""

    market: Market
    departure: date
    last_polled: date | None = None
    quiet_polls: int = 0
    last_amount: str = ""

    @property
    def key(self) -> tuple[str, str]:
        return (self.market.key, self.departure.isoformat())

    def days_out(self, today: date) -> int:
        return (self.departure - today).days

    def due_on(self, today: date) -> bool:
        """Whether this series wants polling today.

        A departure that has gone is never due again, which is the only rule
        here that saves real money: nine thousand itineraries a year means
        about twenty five expiring every day.
        """
        out = self.days_out(today)
        if out < 0:
            return False
        if self.last_polled is None:
            return True
        return (today - self.last_polled).days >= interval_for(out, self.quiet_polls)

    def record(self, today: date, amount: str) -> None:
        """Note a poll, and whether it found the price had moved."""
        if amount and amount == self.last_amount:
            self.quiet_polls += 1
        else:
            self.quiet_polls = 0
        self.last_amount = amount or self.last_amount
        self.last_polled = today

    def __str__(self) -> str:
        return f"{self.market} {self.departure.isoformat()} quiet {self.quiet_polls}"


@dataclass(frozen=True, slots=True)
class Task:
    """One poll to make, and why it is worth making."""

    supplier: str
    market: Market
    departure: date
    days_out: int
    priority: int = 0
    requested_at: datetime | None = None

    def __str__(self) -> str:
        return f"{self.supplier} {self.market} {self.departure.isoformat()}"


class Schedule:
    """Every series the desk watches, and what is due today."""

    def __init__(self) -> None:
        self._series: dict[tuple[str, str], Series] = {}

    def follow(self, market: Market, departure: date) -> Series:
        series = Series(market=market, departure=departure)
        self._series[series.key] = series
        return series

    def series(self) -> list[Series]:
        return [self._series[key] for key in sorted(self._series)]

    def get(self, market: Market, departure: date) -> Series | None:
        return self._series.get((market.key, departure.isoformat()))

    def due(self, today: date) -> list[Series]:
        return [s for s in self.series() if s.due_on(today)]

    def retire_flown(self, today: date) -> int:
        """Drop the series whose departures have gone. Returns how many."""
        gone = [key for key, s in self._series.items() if s.days_out(today) < 0]
        for key in gone:
            del self._series[key]
        return len(gone)

    def plan(self, today: date, supplier: str, budget: int) -> list[Task]:
        """The tasks to run today, closest departures first, inside the budget.

        Sorted on days out and not on how long it has been since the last
        poll. A budget that runs out should run out on the March departures
        rather than on the ones next week.
        """
        if budget < 0:
            raise ValidationError("a budget is not negative", budget=budget)
        wanted = sorted(self.due(today), key=lambda s: (s.days_out(today), s.market.key))
        return [
            Task(
                supplier=supplier,
                market=s.market,
                departure=s.departure,
                days_out=s.days_out(today),
                priority=index,
            )
            for index, s in enumerate(wanted[:budget])
        ]

    def __len__(self) -> int:
        return len(self._series)


@dataclass
class RunPlan:
    """What a whole run intends to do, across every supplier."""

    tasks: list[Task] = field(default_factory=list)

    def for_supplier(self, name: str) -> list[Task]:
        return [t for t in self.tasks if t.supplier == name]

    def markets(self) -> list[str]:
        return sorted({t.market.key for t in self.tasks})

    def __len__(self) -> int:
        return len(self.tasks)

    def __str__(self) -> str:
        return f"{len(self.tasks)} tasks over {len(self.markets())} markets"
