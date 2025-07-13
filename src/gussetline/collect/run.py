"""One collection run, which is where all the parts meet.

The order of the checks matters and it is cheapest first. The breaker is a
field read, the budget is arithmetic, and the call is a second of network, so
they go in that order and a dead supplier costs nothing to skip. Getting that
backwards is how a run against an outage still takes forty minutes.

Everything that happens is recorded, including the nothings. A task that was
skipped because the quota was spent is not the same as one that was never
planned, and only the record can tell them apart afterwards. The reliability
report and the coverage report are both built out of this and out of nothing
else, so a run that silently drops a task takes a hole in both with it.

The fetch is passed in. That is what lets a whole day of collection run in a
test in a second, and it is also what lets the desk replay a day out of the
archive against a fixed parser without touching a supplier.

What happens to the offers is passed in too. This used to count them and drop
them, which made every part of the collector testable and the whole of it
useless: a run that parses nine hundred offers and stores none of them leaves
the audit and the night with nothing to read. The sink is optional because a
plan or a dry run genuinely wants the counting and not the writing.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal

from gussetline.collect.archive import Archive
from gussetline.collect.breaker import BreakerBoard
from gussetline.collect.harvest import Adapter, Harvest
from gussetline.collect.http import Response
from gussetline.collect.limits import Limiter
from gussetline.collect.registry import Outcome, Registry, Supplier, classify
from gussetline.collect.schedule import Schedule, Task
from gussetline.errors import GussetlineError

Fetch = Callable[[Supplier, Task], Response]
Parse = Adapter

# What to do with the offers a parse produced. Called once per answered task,
# inside the run, so a failure writing them fails the task rather than the run.
Sink = Callable[[Task, Harvest], None]


@dataclass(frozen=True, slots=True)
class Result:
    """What one task did."""

    task: Task
    outcome: Outcome
    offers: int = 0
    digest: str = ""
    fresh: bool = True
    attempts: int = 0
    problems: int = 0
    reason: str = ""

    @property
    def ok(self) -> bool:
        return self.outcome is Outcome.OK

    def __str__(self) -> str:
        if self.ok:
            return f"{self.task} {self.offers} offers"
        return f"{self.task} {self.outcome.value} {self.reason}".strip()


@dataclass
class RunReport:
    """Everything a run did, including the nothings."""

    started: datetime
    results: list[Result] = field(default_factory=list)
    skipped_breaker: int = 0
    skipped_budget: int = 0

    @property
    def offers(self) -> int:
        return sum(r.offers for r in self.results)

    @property
    def calls(self) -> int:
        return sum(1 for r in self.results if r.attempts > 0)

    def by_outcome(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for result in self.results:
            counts[result.outcome.value] = counts.get(result.outcome.value, 0) + 1
        return counts

    def failures(self) -> list[Result]:
        return [r for r in self.results if not r.ok]

    @property
    def problems(self) -> int:
        """Payload level problems, which used to be dropped on the floor."""
        return sum(r.problems for r in self.results)

    def cost(self, registry: Registry) -> Decimal:
        """What the run spent, counting only the calls that went out."""
        total = Decimal(0)
        for result in self.results:
            if result.attempts > 0:
                total += registry.get(result.task.supplier).cost_per_call
        return total

    def __len__(self) -> int:
        return len(self.results)

    def __str__(self) -> str:
        return (
            f"{len(self.results)} tasks, {self.offers} offers, "
            f"{self.skipped_breaker} skipped on the breaker, "
            f"{self.skipped_budget} on the budget"
        )


class Collector:
    """Runs a plan against the suppliers, politely and once."""

    def __init__(
        self,
        registry: Registry,
        limiter: Limiter,
        breakers: BreakerBoard,
        archive: Archive,
        *,
        now: Callable[[], datetime],
    ) -> None:
        self.registry = registry
        self.limiter = limiter
        self.breakers = breakers
        self.archive = archive
        self._now = now

    def run(
        self,
        tasks: list[Task],
        today: date,
        fetch: Fetch,
        parsers: dict[str, Parse],
        schedule: Schedule | None = None,
        sink: Sink | None = None,
    ) -> RunReport:
        """Work through a plan, recording what every task did."""
        report = RunReport(started=self._now())

        for task in tasks:
            supplier = self.registry.get(task.supplier)

            if not self.breakers.allow(supplier.name):
                report.skipped_breaker += 1
                report.results.append(Result(task, Outcome.BLOCKED, reason="breaker is open"))
                continue

            if not self.limiter.allow(supplier.host, today):
                budget = self.limiter.budget_for(supplier.host)
                report.skipped_budget += 1
                report.results.append(Result(task, Outcome.BUDGET, reason=budget.last_reason))
                continue

            report.results.append(self._one(supplier, task, today, fetch, parsers, schedule, sink))

        return report

    def _one(
        self,
        supplier: Supplier,
        task: Task,
        today: date,
        fetch: Fetch,
        parsers: dict[str, Parse],
        schedule: Schedule | None,
        sink: Sink | None = None,
    ) -> Result:
        seen = self._now()
        try:
            response = fetch(supplier, task)
        except GussetlineError as exc:
            self.breakers.failed(supplier.name)
            return Result(task, classify(exc), reason=exc.message, attempts=1)

        sighting = self.archive.put(
            response.body,
            supplier.name,
            response.url or supplier.path,
            seen,
            status=response.status,
            context={"market": task.market.key, "departure": task.departure.isoformat()},
        )

        if not response.ok:
            self.breakers.failed(supplier.name)
            return Result(
                task,
                Outcome.TRANSIENT,
                digest=sighting.digest,
                fresh=sighting.fresh,
                attempts=response.attempts,
                reason=f"status {response.status}",
            )

        parser = parsers.get(supplier.name)
        if parser is None:
            self.breakers.succeeded(supplier.name)
            return Result(
                task,
                Outcome.REFUSED,
                digest=sighting.digest,
                fresh=sighting.fresh,
                attempts=response.attempts,
                reason="no parser registered",
            )

        try:
            harvest = parser(response.body, seen)
        except GussetlineError as exc:
            self.breakers.succeeded(supplier.name)
            return Result(
                task,
                classify(exc),
                digest=sighting.digest,
                fresh=sighting.fresh,
                attempts=response.attempts,
                reason=exc.message,
            )

        self.breakers.succeeded(supplier.name)
        if schedule is not None:
            series = schedule.get(task.market, task.departure)
            if series is not None:
                series.record(today, harvest.cheapest_amount())
        if sink is not None:
            sink(task, harvest)

        return Result(
            task,
            Outcome.OK,
            offers=len(harvest),
            problems=len(harvest.problems),
            digest=sighting.digest,
            fresh=sighting.fresh,
            attempts=response.attempts,
        )
