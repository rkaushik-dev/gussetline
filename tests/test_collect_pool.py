from __future__ import annotations

import threading
import time
from datetime import date, datetime

import pytest

from gussetline.collect import pool
from gussetline.collect.harvest import Harvest, nothing
from gussetline.collect.http import Response
from gussetline.collect.registry import Capability, Outcome, Registry, Supplier
from gussetline.collect.rig import build
from gussetline.collect.schedule import Market, Task
from gussetline.config import Settings
from gussetline.errors import SupplierUnavailable, ValidationError

TODAY = date(2025, 10, 6)
AUGUST = date(2025, 11, 10)


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def rig(tmp_path):
    # A burst wide enough that the token bucket is never what stops a run.
    # The clock is frozen, so the bucket never refills and a narrow burst
    # would make every test here a test of the burst.
    settings = Settings(
        archive_root=tmp_path / "raw",
        daily_quota=1000,
        rate_per_second=1000.0,
        burst=200,
    )
    return build(settings, clock=FakeClock(), now=lambda: datetime(2025, 10, 6, 6, 0))


def task(supplier="gds", market=None, day=1) -> Task:
    return Task(
        supplier=supplier,
        market=market or Market("FRA", "JFK"),
        departure=date(2025, 11, day),
        days_out=35,
    )


def parses(body: bytes, seen: datetime) -> Harvest:
    return nothing("read", source="test")


def answering(delay: float = 0.0):
    def fetch(supplier, task):
        if delay:
            time.sleep(delay)
        return Response(status=200, body=b"{}", url=supplier.path)

    return fetch


def failing():
    def fetch(supplier, task):
        raise SupplierUnavailable("the far end is down", supplier=supplier.name)

    return fetch


def test_a_run_with_nothing_in_it_does_nothing(rig):
    report = pool.run(rig.collector(), [], TODAY, answering(), {"gds": parses})
    assert len(report) == 0


def test_every_task_comes_back(rig):
    tasks = [task(day=day) for day in range(1, 8)]
    report = pool.run(rig.collector(), tasks, TODAY, answering(), {"gds": parses})
    assert len(report) == 7
    assert report.by_outcome() == {"ok": 7}, [str(r) for r in report.failures()]


def test_the_results_come_back_in_the_order_the_plan_asked_for(rig):
    # A report read next to yesterday's cannot shuffle its own rows.
    tasks = [task(day=day) for day in range(1, 8)]
    report = pool.run(rig.collector(), tasks, TODAY, answering(0.005), {"gds": parses})
    assert [r.task.departure.day for r in report.results] == list(range(1, 8))


def test_the_same_plan_gives_the_same_report_twice(rig):
    tasks = [task(day=day) for day in range(1, 6)]
    once = pool.run(rig.collector(), tasks, TODAY, answering(), {"gds": parses})
    again = pool.run(rig.collector(), tasks, TODAY, answering(), {"gds": parses})
    assert [r.task.departure for r in once.results] == [r.task.departure for r in again.results]


def test_one_host_is_never_asked_more_than_the_cap_at_once(rig):
    # What a supplier means when they say they blocked us for aggressive
    # polling.
    live = 0
    most = 0
    lock = threading.Lock()

    def fetch(supplier, task):
        nonlocal live, most
        with lock:
            live += 1
            most = max(most, live)
        time.sleep(0.01)
        with lock:
            live -= 1
        return Response(status=200, body=b"{}", url=supplier.path)

    tasks = [task(day=day) for day in range(1, 10)]
    pool.run(rig.collector(), tasks, TODAY, fetch, {"gds": parses}, per_host=2, workers=8)
    assert most <= 2


def test_two_hosts_are_asked_at_the_same_time(rig):
    # Two companies, neither of which knows about the other.
    live: dict[str, int] = {}
    together = threading.Event()
    lock = threading.Lock()

    def fetch(supplier, task):
        with lock:
            live[supplier.host] = live.get(supplier.host, 0) + 1
            if len(live) > 1:
                together.set()
        time.sleep(0.02)
        return Response(status=200, body=b"{}", url=supplier.path)

    tasks = [
        task(supplier="gds"),
        task(supplier="calendar", market=Market("MUC", "ORD")),
    ]
    pool.run(
        rig.collector(),
        tasks,
        TODAY,
        fetch,
        {"gds": parses, "calendar": parses},
        per_host=1,
        workers=4,
    )
    assert together.is_set()


def test_the_budget_is_spent_once_per_call_and_not_more(rig, tmp_path):
    # A read, a subtraction and a write. Six workers doing that at once spend
    # a budget of a thousand about eleven hundred times.
    settings = Settings(
        archive_root=tmp_path / "raw", daily_quota=5, rate_per_second=1000.0, burst=200
    )
    small = build(settings, clock=FakeClock(), now=lambda: datetime(2025, 10, 6, 6, 0))
    tasks = [task(day=day) for day in range(1, 21)]
    report = pool.run(small.collector(), tasks, TODAY, answering(0.002), {"gds": parses})
    assert sum(1 for r in report.results if r.ok) == 5
    assert report.skipped_budget == 15


def test_a_breaker_that_opens_stops_the_workers_that_have_not_gone_yet(rig, tmp_path):
    # The whole point of a breaker is that the fifth failure stops the sixth
    # call, and six workers each holding their own count never reach five.
    settings = Settings(
        archive_root=tmp_path / "raw",
        daily_quota=1000,
        rate_per_second=1000.0,
        burst=200,
        breaker_threshold=3,
    )
    made = build(settings, clock=FakeClock(), now=lambda: datetime(2025, 10, 6, 6, 0))
    tasks = [task(day=day) for day in range(1, 21)]
    report = pool.run(
        made.collector(), tasks, TODAY, failing(), {"gds": parses}, per_host=1, workers=1
    )
    assert report.skipped_breaker > 0
    assert report.by_outcome()[Outcome.BLOCKED.value] == report.skipped_breaker


def test_a_pool_with_no_workers_is_refused(rig):
    with pytest.raises(ValidationError, match="at least one worker"):
        pool.run(rig.collector(), [task()], TODAY, answering(), {"gds": parses}, workers=0)


def test_a_pool_with_no_room_per_host_is_refused(rig):
    with pytest.raises(ValidationError, match="at least one worker"):
        pool.run(rig.collector(), [task()], TODAY, answering(), {"gds": parses}, per_host=0)


def test_a_plan_is_grouped_by_the_host_each_task_goes_to(rig):
    plan = pool.plan_by_host(
        rig.registry,
        [task(supplier="gds"), task(supplier="calendar", market=Market("MUC", "ORD"))],
    )
    assert plan.hosts() == ["fares.example", "gateway.example"]
    assert len(plan) == 2


def test_a_plan_knows_how_many_workers_it_could_keep_busy(rig):
    # More than this and the extra threads sit on a semaphore doing nothing.
    plan = pool.plan_by_host(rig.registry, [task(day=day) for day in range(1, 10)])
    assert pool.widest(plan, per_host=2) == 2

    both = pool.plan_by_host(
        rig.registry,
        [task(supplier="gds"), task(supplier="calendar", market=Market("MUC", "ORD"))],
    )
    assert pool.widest(both, per_host=2) == 2


def test_a_plan_of_one_task_keeps_one_worker_busy(rig):
    plan = pool.plan_by_host(rig.registry, [task()])
    assert pool.widest(plan, per_host=4) == 1


def test_the_offers_still_reach_the_sink(rig):
    kept: list[Task] = []
    tasks = [task(day=day) for day in range(1, 5)]
    pool.run(
        rig.collector(),
        tasks,
        TODAY,
        answering(),
        {"gds": parses},
        sink=lambda task, harvest: kept.append(task),
    )
    assert len(kept) == 4


def test_a_supplier_nobody_registered_is_refused_before_any_thread_starts(rig):
    with pytest.raises(ValidationError):
        pool.run(rig.collector(), [task(supplier="nobody")], TODAY, answering(), {})


def test_a_guarded_limiter_answers_the_same_way_the_plain_one_does(rig):
    guarded = pool.Guarded(rig.limiter, rig.breakers)
    assert guarded.permits("gds")
    assert guarded.allow("gateway.example", TODAY)
    guarded.failed("gds")
    guarded.succeeded("gds")
    assert guarded.permits("gds")


def test_a_registry_of_one_supplier_still_runs(tmp_path):
    registry = Registry()
    registry.add(
        Supplier(name="only", host="one.example", capabilities=frozenset({Capability.SHOP}))
    )
    settings = Settings(archive_root=tmp_path / "raw", rate_per_second=1000.0, burst=200)
    made = build(settings, registry=registry, clock=FakeClock())
    report = pool.run(
        made.collector(), [task(supplier="only")], TODAY, answering(), {"only": parses}
    )
    assert len(report) == 1
