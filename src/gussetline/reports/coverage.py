"""What the desk is actually watching, as opposed to what it meant to watch.

The schedule says nine thousand series are followed. The run reports say how
many of them a supplier answered for. Coverage is the difference, and it is the
report that catches the failures nobody gets paged about, because a series that
is quietly never polled looks exactly like a series where the fare never moved.

Two decisions here and both of them are the reason a global staleness number
was thrown away.

The first is that lateness is measured against each series' own cadence. A
departure in March is polled fortnightly and one next week is polled daily, so
a single number for how old an observation may be either pages on the March
ones every morning or misses the ones next week entirely. The schedule already
works out the interval; this asks it rather than picking a number.

The second is that a quote is not a price. The calendar answers for markets the
other two do not cover, and its answers cannot be repriced, audited or turned
into a recommendation. A series only the calendar has answered for is covered
in the sense that somebody looked and uncovered in the sense that matters, so
it gets its own grade rather than being counted either way.

A departure that has gone is neither. It is finished, and counting finished
series as gaps is how the first version of this reported a coverage figure that
fell every week no matter what the collectors did.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.collect.registry import Capability, Registry
from gussetline.collect.run import RunReport
from gussetline.collect.schedule import Schedule, Series, interval_for
from gussetline.errors import ValidationError

# How many of a series' own intervals may pass before it counts as late.
LATE_AFTER_INTERVALS = 2


class Cover(str, Enum):
    """How well one series is being watched."""

    PRICED = "priced"
    QUOTED = "quoted"
    LATE = "late"
    DARK = "dark"
    FLOWN = "flown"

    @property
    def is_a_gap(self) -> bool:
        """Whether somebody should do something about it."""
        return self in (Cover.LATE, Cover.DARK)


@dataclass
class Watched:
    """One series, and what the runs managed to get for it."""

    key: str
    market: str
    departure: date
    days_out: int
    interval: int
    as_of: date
    polls: int = 0
    answers: int = 0
    priced_answers: int = 0
    last_answer: date | None = None
    last_priced: date | None = None

    @property
    def age(self) -> int | None:
        """Days since anything answered, or nothing if nothing ever did."""
        if self.last_answer is None:
            return None
        return (self.as_of - self.last_answer).days

    @property
    def allowance(self) -> int:
        """How old an answer may be before this series is late."""
        return self.interval * LATE_AFTER_INTERVALS

    def cover(self) -> Cover:
        """The grade, and the order is the argument.

        Flown first, because a departure that has gone is finished and not a
        gap. Dark next, because never having been answered for is worse than
        being late. Then lateness, which is measured on the priced answers,
        because a stream of calendar quotes does not keep a series fresh for
        anything the desk can act on.
        """
        if self.days_out < 0:
            return Cover.FLOWN
        if self.last_answer is None:
            return Cover.DARK
        if self.last_priced is None:
            return Cover.QUOTED
        if (self.as_of - self.last_priced).days > self.allowance:
            return Cover.LATE
        return Cover.PRICED

    def why(self) -> str:
        cover = self.cover()
        if cover is Cover.FLOWN:
            return "flown"
        if cover is Cover.DARK:
            return f"{self.polls} polls and nothing answered"
        if cover is Cover.QUOTED:
            return "quotes only, nothing that can be repriced"
        if cover is Cover.LATE:
            return f"last priced {self.age} days ago against an allowance of {self.allowance}"
        return f"priced {self.age} days ago"

    def __str__(self) -> str:
        return f"{self.market} {self.departure.isoformat()} {self.cover().value} {self.why()}"


@dataclass
class Coverage:
    """Every followed series, graded, with the gaps pulled out."""

    as_of: date
    series: list[Watched] = field(default_factory=list)

    @property
    def live(self) -> list[Watched]:
        """The ones still ahead of their departure, which is the denominator."""
        return [w for w in self.series if w.cover() is not Cover.FLOWN]

    def with_cover(self, cover: Cover) -> list[Watched]:
        return [w for w in self.series if w.cover() is cover]

    def gaps(self) -> list[Watched]:
        """The ones somebody should look at, worst first."""
        order = {Cover.DARK: 0, Cover.LATE: 1}
        found = [w for w in self.series if w.cover().is_a_gap]
        return sorted(found, key=lambda w: (order[w.cover()], w.days_out))

    @property
    def rate(self) -> Decimal:
        """Share of live series with a priced answer inside their allowance."""
        live = self.live
        if not live:
            return Decimal(0)
        priced = sum(1 for w in live if w.cover() is Cover.PRICED)
        return (Decimal(priced) / Decimal(len(live))).quantize(Decimal("0.01"))

    def get(self, key: str) -> Watched | None:
        for watched in self.series:
            if watched.key == key:
                return watched
        return None

    def __len__(self) -> int:
        return len(self.series)

    def __str__(self) -> str:
        return f"{len(self.live)} live series, {self.rate} priced, {len(self.gaps())} gaps"


def build(
    schedule: Schedule,
    reports: Iterable[RunReport],
    registry: Registry,
    today: date,
) -> Coverage:
    """Join what was followed to what the runs got, one row per series."""
    watched = {
        series.key[0] + " " + series.key[1]: _blank(series, today) for series in schedule.series()
    }

    for report in reports:
        day = report.started.date()
        for result in report.results:
            key = result.task.market.key + " " + result.task.departure.isoformat()
            row = watched.get(key)
            if row is None:
                continue
            row.polls += 1
            if not result.ok:
                continue
            row.answers += 1
            row.last_answer = _later(row.last_answer, day)
            if _can_price(registry, result.task.supplier):
                row.priced_answers += 1
                row.last_priced = _later(row.last_priced, day)

    return Coverage(as_of=today, series=[watched[key] for key in sorted(watched)])


def _blank(series: Series, today: date) -> Watched:
    days_out = series.days_out(today)
    return Watched(
        key=series.key[0] + " " + series.key[1],
        market=series.market.key,
        departure=series.departure,
        days_out=days_out,
        interval=interval_for(max(days_out, 0), series.quiet_polls),
        as_of=today,
    )


def _can_price(registry: Registry, supplier: str) -> bool:
    """Whether an answer from this supplier is one the desk can act on."""
    try:
        found = registry.get(supplier)
    except ValidationError:
        return False
    return found.can(Capability.SHOP) or found.can(Capability.REPRICE)


def _later(current: date | None, seen: date) -> date:
    return seen if current is None or seen > current else current


def render(report: Coverage, limit: int = 20) -> str:
    """The report as the operator reads it, gaps first and the rest counted."""
    lines = [
        f"coverage on {report.as_of.isoformat()}",
        "",
        f"followed  {len(report.series)}",
        f"live      {len(report.live)}",
        f"priced    {len(report.with_cover(Cover.PRICED))}",
        f"quoted    {len(report.with_cover(Cover.QUOTED))}",
        f"late      {len(report.with_cover(Cover.LATE))}",
        f"dark      {len(report.with_cover(Cover.DARK))}",
        f"flown     {len(report.with_cover(Cover.FLOWN))}",
        f"rate      {report.rate}",
    ]
    gaps = report.gaps()
    if gaps:
        lines.extend(["", f"gaps, worst first, showing {min(limit, len(gaps))} of {len(gaps)}"])
        lines.extend(f"  {row}" for row in gaps[:limit])
    return "\n".join(lines)
