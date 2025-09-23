"""Running a collection across several workers without being rude about it.

A night of collection is nine hundred tasks, each of them a second of waiting
on somebody else's server. Doing them one at a time takes fifteen minutes of
almost nothing happening. Doing them all at once gets the desk blocked.

So the pool is capped per host and not globally. Two suppliers can be asked at
the same time, because they are two companies and neither knows about the
other. One supplier cannot be asked six times at once, because that is what a
supplier means when they say they blocked us for aggressive polling.

Everything shared is locked, and the two shared things are the two that decide
whether a call goes out at all. A token bucket is a read, a subtraction and a
write, and six workers doing that at once spend a budget of a thousand about
eleven hundred times. A breaker is worse: the whole point of it is that the
fifth failure stops the sixth call, and six workers each holding their own
count never reach five.

The results come back in the order the plan asked for them and not the order
they finished. A run report is read next to yesterday's, and one that shuffles
its own rows on every run cannot be compared with anything.

Nothing here is async. The work is blocking network calls behind a library
that is blocking, threads are what that wants, and an async rewrite of the
whole collection stack to save six threads would be a week spent to make one
number in a profile smaller.
"""

from __future__ import annotations

import threading
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date

from gussetline.collect.breaker import BreakerBoard
from gussetline.collect.limits import Limiter
from gussetline.collect.registry import Outcome, Registry
from gussetline.collect.run import Collector, Fetch, Parse, Result, RunReport, Sink
from gussetline.collect.schedule import Schedule, Task
from gussetline.errors import ValidationError

# How many calls one host takes at a time. Two is polite and it is most of the
# speed: the waiting is the supplier's and one extra in flight covers it.
PER_HOST = 2

# How many workers in total, whatever the hosts say.
WORKERS = 8


class Guarded:
    """A limiter and a breaker board that several workers may share.

    Wraps rather than subclasses, because the things being guarded are used
    unguarded everywhere else and a lock inside them would cost every single
    threaded caller for the benefit of this one.
    """

    def __init__(self, limiter: Limiter, breakers: BreakerBoard) -> None:
        self._limiter = limiter
        self._breakers = breakers
        self._lock = threading.Lock()

    def allow(self, host: str, today: date) -> bool:
        with self._lock:
            return self._limiter.allow(host, today)

    def budget_reason(self, host: str) -> str:
        with self._lock:
            return self._limiter.budget_for(host).last_reason

    def permits(self, supplier: str) -> bool:
        with self._lock:
            return self._breakers.allow(supplier)

    def failed(self, supplier: str) -> None:
        with self._lock:
            self._breakers.failed(supplier)

    def succeeded(self, supplier: str) -> None:
        with self._lock:
            self._breakers.succeeded(supplier)


@dataclass
class Plan:
    """A run's tasks, grouped by the host they will be asked of."""

    by_host: dict[str, list[tuple[int, Task]]] = field(default_factory=dict)

    def add(self, position: int, host: str, task: Task) -> None:
        self.by_host.setdefault(host, []).append((position, task))

    def hosts(self) -> list[str]:
        return sorted(self.by_host)

    def __len__(self) -> int:
        return sum(len(found) for found in self.by_host.values())


def plan_by_host(registry: Registry, tasks: list[Task]) -> Plan:
    """Group a run's tasks under the host each will be asked of."""
    plan = Plan()
    for position, task in enumerate(tasks):
        plan.add(position, registry.get(task.supplier).host, task)
    return plan


def run(
    collector: Collector,
    tasks: list[Task],
    today: date,
    fetch: Fetch,
    parsers: dict[str, Parse],
    schedule: Schedule | None = None,
    sink: Sink | None = None,
    *,
    workers: int = WORKERS,
    per_host: int = PER_HOST,
) -> RunReport:
    """Work through a plan across several workers, politely and once.

    Takes a collector rather than building one, because everything it holds
    is already assembled by the rig and a second copy here would be a second
    archive root and a second breaker board.
    """
    if workers < 1 or per_host < 1:
        raise ValidationError("a pool needs at least one worker", workers=workers)
    if not tasks:
        return RunReport(started=collector.now())

    plan = plan_by_host(collector.registry, tasks)
    gates = {host: threading.Semaphore(per_host) for host in plan.hosts()}
    guarded = Guarded(collector.limiter, collector.breakers)
    started = collector.now()

    holder: dict[int, Result] = {}
    skipped_breaker = 0
    skipped_budget = 0
    counting = threading.Lock()

    def one(position: int, task: Task) -> None:
        nonlocal skipped_breaker, skipped_budget
        supplier = collector.registry.get(task.supplier)

        if not guarded.permits(supplier.name):
            with counting:
                skipped_breaker += 1
            holder[position] = Result(task, Outcome.BLOCKED, reason="breaker is open")
            return

        if not guarded.allow(supplier.host, today):
            with counting:
                skipped_budget += 1
            holder[position] = Result(
                task, Outcome.BUDGET, reason=guarded.budget_reason(supplier.host)
            )
            return

        with gates[supplier.host]:
            holder[position] = collector.run_one(
                supplier, task, today, fetch, parsers, schedule, sink
            )

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="collect") as pool:
        waiting: list[Future[None]] = [
            pool.submit(one, position, task)
            for host in plan.hosts()
            for position, task in plan.by_host[host]
        ]
        for future in waiting:
            future.result()

    # Back in the order the plan asked for. A report read next to yesterday's
    # cannot shuffle its own rows.
    return RunReport(
        started=started,
        results=[holder[position] for position in sorted(holder)],
        skipped_breaker=skipped_breaker,
        skipped_budget=skipped_budget,
    )


def widest(plan: Plan, per_host: int = PER_HOST) -> int:
    """How many workers a plan could possibly keep busy.

    More than this and the extra threads sit on a semaphore doing nothing,
    which is a thread pool that looks busy in a stack dump and is not.
    """
    return min(len(plan.hosts()) * per_host, len(plan))
