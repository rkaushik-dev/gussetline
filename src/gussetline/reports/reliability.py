"""Which suppliers are actually answering, read off the run reports.

The number everybody asks for is a success rate, and the naive one is wrong in
three separate ways that all flatter the supplier.

It is wrong in the denominator, because a task that never left the building is
not evidence about the far end. A supplier whose breaker is open contributes a
row to every run and no information at all, and counting those rows makes an
outage look like a slow decline instead of a stop. So the rate is measured
against the calls that went out.

It is wrong about throttling, which is our own pacing and not their fault. A
throttled task means the limiter did its job late. It belongs in the report,
because somebody has to widen the budget, but it does not belong in the
supplier's rate or the desk spends a week tuning the wrong end.

It is wrong about malformed payloads, because those arrive with a 200 on them.
A feed that quietly changed its date format answers every call perfectly and
returns nothing usable, and an answer rate that counts a 200 as an answer will
sit at a hundred percent through the whole outage. So malformed is counted
separately and it is the reading that grades a supplier down fastest.

There is a fourth thing that no status code shows at all. A supplier can serve
the same bytes back for a week, which the archive notices because the digest
stops changing. That is repeated rather than failed, and it is reported on its
own because the fix is different: the call is fine, the cache behind it is not.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from enum import Enum

from gussetline.collect.registry import Outcome
from gussetline.collect.run import RunReport

# Below this share of answered calls a supplier is not worth planning against.
UNFIT_ANSWER_RATE = Decimal("0.50")

# Above this it is fine. Between the two somebody should be told.
SOUND_ANSWER_RATE = Decimal("0.90")

# A supplier serving this share of its bodies unchanged is caching at us.
REPEAT_LIMIT = Decimal("0.80")


class Grade(str, Enum):
    """What to do about a supplier, which is the point of the report."""

    SOUND = "sound"
    WATCH = "watch"
    UNFIT = "unfit"

    @property
    def wants_a_human(self) -> bool:
        return self is not Grade.SOUND


@dataclass
class Health:
    """One supplier, over however many runs were handed in."""

    name: str
    planned: int = 0
    attempted: int = 0
    answered: int = 0
    transient: int = 0
    throttled: int = 0
    blocked: int = 0
    malformed: int = 0
    budget: int = 0
    refused: int = 0
    repeats: int = 0
    offers: int = 0
    problems: int = 0
    runs: int = 0

    @property
    def skipped(self) -> int:
        """Tasks that never left the building, for whatever reason."""
        return self.planned - self.attempted

    @property
    def answer_rate(self) -> Decimal:
        """Answered calls over calls made. No calls means no reading."""
        if not self.attempted:
            return Decimal(0)
        return _share(self.answered, self.attempted)

    @property
    def reach_rate(self) -> Decimal:
        """How much of the plan got out of the door at all."""
        if not self.planned:
            return Decimal(0)
        return _share(self.attempted, self.planned)

    @property
    def repeat_rate(self) -> Decimal:
        """Share of answered calls that brought back bytes we already had."""
        if not self.answered:
            return Decimal(0)
        return _share(self.repeats, self.answered)

    @property
    def has_evidence(self) -> bool:
        """Whether anything here is worth grading."""
        return self.attempted > 0

    def grade(self) -> Grade:
        """The verdict, and the order of these tests is the whole argument.

        Blocked first because it is the one outcome that means stop asking.
        Malformed next, before the answer rate, because malformed calls are
        counted as answered by every layer below this one and a supplier that
        returns unusable 200s would otherwise grade sound.
        """
        if not self.has_evidence:
            return Grade.WATCH
        if self.blocked:
            return Grade.UNFIT
        if self.malformed:
            return Grade.UNFIT if self.malformed >= self.answered else Grade.WATCH
        if self.answer_rate < UNFIT_ANSWER_RATE:
            return Grade.UNFIT
        if self.answer_rate < SOUND_ANSWER_RATE:
            return Grade.WATCH
        if self.repeat_rate >= REPEAT_LIMIT and self.answered > 2:
            return Grade.WATCH
        return Grade.SOUND

    def why(self) -> str:
        """One line saying what the grade is about, for the pager."""
        if not self.has_evidence:
            return "nothing went out"
        if self.blocked:
            return "blocked"
        if self.malformed:
            return f"{self.malformed} payloads did not parse"
        if self.answer_rate < SOUND_ANSWER_RATE:
            return f"answered {self.answered} of {self.attempted}"
        if self.repeat_rate >= REPEAT_LIMIT and self.answered > 2:
            return "serving the same bytes back"
        return "answering"

    def __str__(self) -> str:
        return f"{self.name:<10} {self.grade().value:<6} {self.answer_rate} {self.why()}"


def _share(part: int, whole: int) -> Decimal:
    return (Decimal(part) / Decimal(whole)).quantize(Decimal("0.01"))


@dataclass
class Reliability:
    """The whole reading, over a window of runs."""

    first: datetime | None = None
    last: datetime | None = None
    runs: int = 0
    suppliers: list[Health] = field(default_factory=list)

    def get(self, name: str) -> Health | None:
        for health in self.suppliers:
            if health.name == name:
                return health
        return None

    def needing_a_human(self) -> list[Health]:
        return [h for h in self.suppliers if h.grade().wants_a_human]

    @property
    def worst(self) -> Health | None:
        """The one to look at first, unfit before watch before sound."""
        order = {Grade.UNFIT: 0, Grade.WATCH: 1, Grade.SOUND: 2}
        if not self.suppliers:
            return None
        return sorted(self.suppliers, key=lambda h: (order[h.grade()], h.answer_rate))[0]

    def __len__(self) -> int:
        return len(self.suppliers)

    def __str__(self) -> str:
        bad = len(self.needing_a_human())
        return f"{self.runs} runs, {len(self.suppliers)} suppliers, {bad} wanting a look"


def build(reports: Iterable[RunReport]) -> Reliability:
    """Fold a window of runs into one reading per supplier."""
    seen: dict[str, Health] = {}
    stamps: list[datetime] = []
    runs = 0

    for report in reports:
        runs += 1
        stamps.append(report.started)
        touched: set[str] = set()
        for result in report.results:
            name = result.task.supplier
            health = seen.setdefault(name, Health(name=name))
            touched.add(name)
            _fold(health, result.outcome, result.attempts, result.offers, result.problems)
            if result.attempts > 0 and result.ok and not result.fresh:
                health.repeats += 1
        for name in touched:
            seen[name].runs += 1

    return Reliability(
        first=min(stamps) if stamps else None,
        last=max(stamps) if stamps else None,
        runs=runs,
        suppliers=[seen[name] for name in sorted(seen)],
    )


def _fold(health: Health, outcome: Outcome, attempts: int, offers: int, problems: int) -> None:
    """Add one result to a supplier's tally."""
    health.planned += 1
    health.offers += offers
    health.problems += problems
    if attempts > 0:
        health.attempted += 1
    if outcome is Outcome.OK:
        health.answered += 1
    elif outcome is Outcome.TRANSIENT:
        health.transient += 1
    elif outcome is Outcome.THROTTLED:
        health.throttled += 1
    elif outcome is Outcome.BLOCKED:
        health.blocked += 1
    elif outcome is Outcome.MALFORMED:
        health.malformed += 1
    elif outcome is Outcome.BUDGET:
        health.budget += 1
    else:
        health.refused += 1


def render(report: Reliability) -> str:
    """The report as the operator reads it at eight in the morning."""
    lines = [f"reliability over {report.runs} runs"]
    if report.first and report.last:
        lines.append(f"{report.first:%Y-%m-%d %H:%M} to {report.last:%Y-%m-%d %H:%M}")
    lines.append("")
    for health in report.suppliers:
        lines.append(str(health))
        lines.append(
            f"  planned {health.planned} called {health.attempted} "
            f"answered {health.answered} repeats {health.repeats}"
        )
        if health.throttled:
            lines.append(f"  throttled {health.throttled}, which is our pacing and not theirs")
        if health.budget:
            lines.append(f"  {health.budget} skipped on the budget")
        if health.problems:
            lines.append(f"  {health.problems} offers in the payloads did not parse")
    if not report.suppliers:
        lines.append("nothing ran")
    return "\n".join(lines)
