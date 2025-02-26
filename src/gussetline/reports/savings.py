"""The savings report, which is the one the desk is measured on.

Three numbers and they are deliberately different questions.

What was found is the benefit of every recommendation raised. It measures the
collectors and the tariff, and it goes up when the desk polls more.

What was taken is the benefit of the ones somebody acted on. It measures the
desk, and it is the number that goes on the invoice.

What was missed is the benefit of the ones that expired. It measures the
staffing, and it is the only one of the three that anybody argues about, which
is why it is reported at all rather than being quietly folded into the others.

A killed recommendation is in none of them past the first. Somebody looked and
said no, usually because the customer wanted the flight they had, and counting
that as a miss blames the desk for a decision that was correct.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from gussetline.desk.recommend import Recommendation, State
from gussetline.money import Money


@dataclass(frozen=True, slots=True)
class Line:
    """One row of the report, for one grouping."""

    label: str
    raised: int = 0
    taken: int = 0
    killed: int = 0
    expired: int = 0
    still_open: int = 0
    found: Money | None = None
    realised: Money | None = None
    missed: Money | None = None

    @property
    def settled(self) -> int:
        return self.taken + self.killed + self.expired

    @property
    def take_rate(self) -> Decimal:
        if not self.settled:
            return Decimal(0)
        return (Decimal(self.taken) / Decimal(self.settled)).quantize(Decimal("0.01"))

    def __str__(self) -> str:
        return (
            f"{self.label:<16} raised {self.raised:>4} taken {self.taken:>4} worth {self.realised}"
        )


@dataclass
class Savings:
    """The whole report, with the lines the desk actually reads."""

    currency: str
    overall: Line
    by_month: list[Line] = field(default_factory=list)
    by_agent: list[Line] = field(default_factory=list)

    def __str__(self) -> str:
        return str(self.overall)


def _line(label: str, rows: list[Recommendation], currency: str) -> Line:
    found = Money.zero(currency)
    realised = Money.zero(currency)
    missed = Money.zero(currency)
    taken = killed = expired = still_open = 0

    for row in rows:
        found = found + row.benefit
        if row.state is State.TAKEN:
            taken += 1
            realised = realised + row.benefit
        elif row.state is State.KILLED:
            killed += 1
        elif row.state is State.EXPIRED:
            expired += 1
            missed = missed + row.benefit
        else:
            still_open += 1

    return Line(
        label=label,
        raised=len(rows),
        taken=taken,
        killed=killed,
        expired=expired,
        still_open=still_open,
        found=found,
        realised=realised,
        missed=missed,
    )


def build(
    recommendations: Iterable[Recommendation],
    currency: str,
    *,
    first: date | None = None,
    last: date | None = None,
) -> Savings:
    """Every number, over a window, grouped the two ways the desk asks for."""
    rows = [
        r
        for r in recommendations
        if (first is None or r.raised_on >= first) and (last is None or r.raised_on <= last)
    ]

    months: dict[str, list[Recommendation]] = {}
    agents: dict[str, list[Recommendation]] = {}
    for row in rows:
        months.setdefault(f"{row.raised_on:%Y-%m}", []).append(row)
        if row.state.is_settled and row.decided_by:
            agents.setdefault(row.decided_by, []).append(row)

    return Savings(
        currency=currency,
        overall=_line("overall", rows, currency),
        by_month=[_line(key, months[key], currency) for key in sorted(months)],
        by_agent=[_line(key, agents[key], currency) for key in sorted(agents)],
    )


def render(report: Savings) -> str:
    """The report as the desk prints it, one line per row."""
    lines = [
        f"savings in {report.currency}",
        "",
        f"found     {report.overall.found}",
        f"taken     {report.overall.realised}",
        f"missed    {report.overall.missed}",
        f"raised    {report.overall.raised}",
        f"settled   {report.overall.settled}",
        f"open      {report.overall.still_open}",
        f"take rate {report.overall.take_rate}",
    ]
    if report.by_month:
        lines.extend(["", "by month"])
        lines.extend(f"  {line}" for line in report.by_month)
    if report.by_agent:
        lines.extend(["", "by agent"])
        lines.extend(f"  {line}" for line in report.by_agent)
    return "\n".join(lines)
