from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.collect.archive import Archive
from gussetline.collect.breaker import BreakerBoard
from gussetline.collect.http import Response
from gussetline.collect.limits import Limiter
from gussetline.collect.registry import Outcome, default_registry
from gussetline.collect.run import Collector, Result, RunReport
from gussetline.collect.schedule import Market, Schedule
from gussetline.errors import MalformedPayload, SupplierBlocked, SupplierUnavailable
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown

TODAY = date(2025, 5, 28)
NOW = datetime(2025, 5, 28, 9, 0)


class Clock:
    def __init__(self):
        self.seconds = 0.0

    def monotonic(self) -> float:
        return self.seconds

    def now(self) -> datetime:
        return NOW


def an_offer(amount="490.83"):
    return Offer(
        source="gds",
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-06-04 10:25", "2025-06-04 13:05")]
        ),
        breakdown=PriceBreakdown(Money.of(amount, "EUR")),
        fare_bases=("KLWAP7RT",),
        observed_at=NOW,
    )


def parsers(offers=None, raises=None):
    def parse(payload, seen):
        if raises is not None:
            raise raises
        return list(offers if offers is not None else [an_offer()])

    return {"gds": parse, "ndc": parse}


@pytest.fixture
def collector(tmp_path):
    clock = Clock()
    made = Collector(
        registry=default_registry(),
        limiter=Limiter(clock.monotonic, default_burst=10, default_quota=100),
        breakers=BreakerBoard(clock.monotonic, threshold=2),
        archive=Archive(tmp_path / "raw"),
        now=clock.now,
    )
    made.clock = clock
    return made


def plan(count=1, supplier="gds"):
    schedule = Schedule()
    for day in range(count):
        schedule.follow(Market("FRA", "JFK"), date(2025, 6, 4 + day))
    return schedule, schedule.plan(TODAY, supplier, budget=count)


def answering(body=b"payload", status=200, attempts=1):
    def fetch(supplier, task):
        return Response(status=status, body=body, attempts=attempts, url=supplier.path)

    return fetch


def test_a_clean_run_records_every_task(collector):
    _, tasks = plan(3)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert len(report) == 3
    assert report.by_outcome() == {"ok": 3}
    assert report.offers == 3
    assert report.calls == 3


def test_the_payload_is_archived_before_it_is_parsed(collector):
    _, tasks = plan(1)
    report = collector.run(tasks, TODAY, answering(b"the bytes"), parsers())
    digest = report.results[0].digest
    assert collector.archive.get(digest) == b"the bytes"


def test_the_market_and_date_ride_along_on_the_archive_row(collector):
    _, tasks = plan(1)
    collector.run(tasks, TODAY, answering(), parsers())
    row = collector.archive.manifest()[0]
    assert row["context"] == {"market": "FRA-NYC", "departure": "2025-06-04"}


def test_a_payload_we_already_hold_is_marked_not_fresh(collector):
    _, tasks = plan(2)
    report = collector.run(tasks, TODAY, answering(b"same"), parsers())
    assert [r.fresh for r in report.results] == [True, False]


def test_a_transport_failure_is_recorded_and_counts_against_the_breaker(collector):
    def failing(supplier, task):
        raise SupplierUnavailable("read timeout")

    _, tasks = plan(3)
    report = collector.run(tasks, TODAY, failing, parsers())
    # Two failures trip the breaker, so the third task never gets a call.
    assert report.by_outcome() == {"transient": 2, "blocked": 1}
    assert report.skipped_breaker == 1


def test_a_blocked_supplier_is_recorded_as_blocked(collector):
    def refused(supplier, task):
        raise SupplierBlocked("403 forever")

    _, tasks = plan(1)
    report = collector.run(tasks, TODAY, refused, parsers())
    assert report.results[0].outcome is Outcome.BLOCKED


def test_a_non_two_hundred_answer_is_archived_and_counted_as_transient(collector):
    _, tasks = plan(1)
    report = collector.run(tasks, TODAY, answering(b"gateway timeout", 504), parsers())
    result = report.results[0]
    assert result.outcome is Outcome.TRANSIENT
    assert "status 504" in result.reason
    assert collector.archive.get(result.digest) == b"gateway timeout"


def test_a_parse_failure_does_not_count_against_the_breaker(collector):
    # The supplier answered. It is our reader that could not cope, and
    # tripping the breaker on that would stop a working supplier.
    _, tasks = plan(3)
    report = collector.run(tasks, TODAY, answering(), parsers(raises=MalformedPayload("not json")))
    assert report.by_outcome() == {"malformed": 3}
    assert report.skipped_breaker == 0


def test_a_supplier_with_no_parser_is_refused_rather_than_crashing(collector):
    _, tasks = plan(1)
    report = collector.run(tasks, TODAY, answering(), {})
    assert report.results[0].outcome is Outcome.REFUSED
    assert report.results[0].reason == "no parser registered"


def test_the_quota_stops_the_run_and_says_so(tmp_path):
    clock = Clock()
    collector = Collector(
        registry=default_registry(),
        limiter=Limiter(clock.monotonic, default_burst=10, default_quota=2),
        breakers=BreakerBoard(clock.monotonic),
        archive=Archive(tmp_path / "raw"),
        now=clock.now,
    )
    _, tasks = plan(4)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert report.by_outcome() == {"ok": 2, "budget": 2}
    assert report.skipped_budget == 2
    assert report.results[2].reason == "quota"


def test_the_rate_limit_stops_a_burst_and_says_so(tmp_path):
    clock = Clock()
    collector = Collector(
        registry=default_registry(),
        limiter=Limiter(clock.monotonic, default_burst=1, default_quota=100),
        breakers=BreakerBoard(clock.monotonic),
        archive=Archive(tmp_path / "raw"),
        now=clock.now,
    )
    _, tasks = plan(3)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert report.by_outcome() == {"ok": 1, "budget": 2}
    assert report.results[1].reason == "rate"


def test_a_skipped_task_never_reaches_the_supplier(collector):
    seen = []

    def counting(supplier, task):
        seen.append(task)
        return Response(200, b"payload", url=supplier.path)

    collector.breakers.for_supplier("gds").failed()
    collector.breakers.for_supplier("gds").failed()
    _, tasks = plan(2)
    collector.run(tasks, TODAY, counting, parsers())
    assert seen == []


def test_the_schedule_learns_the_cheapest_price_from_the_run(collector):
    schedule, tasks = plan(1)
    collector.run(
        tasks,
        TODAY,
        answering(),
        parsers(offers=[an_offer("490.83"), an_offer("420.00")]),
        schedule,
    )
    series = schedule.get(Market("FRA", "JFK"), date(2025, 6, 4))
    assert series is not None
    assert series.last_amount == "420.00 EUR"
    assert series.last_polled == TODAY


def test_a_second_run_at_the_same_price_makes_the_series_quiet(collector):
    schedule, tasks = plan(1)
    collector.run(tasks, TODAY, answering(), parsers(), schedule)
    collector.run(tasks, date(2025, 5, 30), answering(), parsers(), schedule)
    series = schedule.get(Market("FRA", "JFK"), date(2025, 6, 4))
    assert series is not None
    assert series.quiet_polls == 1


def test_a_run_with_no_offers_leaves_the_series_price_alone(collector):
    schedule, tasks = plan(1)
    collector.run(tasks, TODAY, answering(), parsers(), schedule)
    collector.run(tasks, date(2025, 5, 30), answering(), parsers(offers=[]), schedule)
    series = schedule.get(Market("FRA", "JFK"), date(2025, 6, 4))
    assert series is not None
    assert series.last_amount == "490.83 EUR"


def test_the_run_costs_what_the_calls_that_went_out_cost(collector):
    _, tasks = plan(2)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert report.cost(collector.registry) == Decimal("0.08")


def test_a_skipped_task_costs_nothing(tmp_path):
    clock = Clock()
    collector = Collector(
        registry=default_registry(),
        limiter=Limiter(clock.monotonic, default_burst=10, default_quota=1),
        breakers=BreakerBoard(clock.monotonic),
        archive=Archive(tmp_path / "raw"),
        now=clock.now,
    )
    _, tasks = plan(3)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert report.cost(collector.registry) == Decimal("0.04")


def test_failures_can_be_listed_on_their_own(collector):
    _, tasks = plan(2)
    report = collector.run(tasks, TODAY, answering(), parsers(raises=MalformedPayload("not json")))
    assert len(report.failures()) == 2


def test_a_result_reads_back_in_one_line(collector):
    _, tasks = plan(1)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert str(report.results[0]) == "gds FRA-NYC 2025-06-04 1 offers"


def test_a_failed_result_reads_back_with_its_reason():
    from gussetline.collect.schedule import Task

    task = Task("gds", Market("FRA", "JFK"), date(2025, 6, 4), 7)
    assert str(Result(task, Outcome.BUDGET, reason="quota")).endswith("budget quota")


def test_a_report_reads_back_with_its_counts(collector):
    _, tasks = plan(2)
    report = collector.run(tasks, TODAY, answering(), parsers())
    assert str(report) == "2 tasks, 2 offers, 0 skipped on the breaker, 0 on the budget"


def test_an_empty_run_is_a_report_with_nothing_in_it(collector):
    report = collector.run([], TODAY, answering(), parsers())
    assert len(report) == 0
    assert report.by_outcome() == {}
    assert report.cost(collector.registry) == Decimal(0)


def test_a_report_knows_when_it_started():
    report = RunReport(started=NOW)
    assert report.started == NOW
