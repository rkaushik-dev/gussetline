from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.collect.harvest import Harvest, nothing
from gussetline.collect.http import Response
from gussetline.collect.registry import Capability, Outcome, Registry, Supplier
from gussetline.collect.rig import build, live_fetch, replay_fetch
from gussetline.collect.schedule import Market, Task
from gussetline.config import Settings
from gussetline.errors import SupplierUnavailable

TODAY = date(2025, 7, 20)
AUGUST = date(2025, 8, 24)
FRA_JFK = Market("FRA", "JFK")


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(archive_root=tmp_path / "raw", daily_quota=4, rate_per_second=100.0)


@pytest.fixture
def rig(settings):
    return build(settings, clock=FakeClock(), now=lambda: datetime(2025, 7, 20, 6, 0))


def task(supplier="gds", market=FRA_JFK, departure=AUGUST) -> Task:
    return Task(supplier=supplier, market=market, departure=departure, days_out=35)


def harvest_of(body: bytes, seen: datetime) -> Harvest:
    return nothing(f"read {len(body)} bytes", source="test")


def test_a_rig_carries_every_part_a_run_needs(rig):
    assert len(rig.registry) == 3
    assert rig.archive.root.exists()
    assert rig.collector() is not None


def test_every_supplier_host_is_budgeted_before_anything_runs(rig):
    # A supplier that is completely down still has to respect its budget, and
    # the settings command has to be able to print what a run would allow.
    assert rig.limiter.known_hosts() == ["fares.example", "gateway.example", "offers.example"]


def test_the_budgets_come_off_the_settings(rig, settings):
    budget = rig.limiter.budget_for("gateway.example")
    assert budget.quota.limit == settings.daily_quota


def test_a_rig_reads_back_with_what_it_holds(rig):
    assert str(rig) == "3 suppliers, 0 parsers, quota 4 a day"


def test_a_parser_can_be_registered_after_the_fact(rig):
    rig.register("gds", harvest_of)
    assert "gds" in rig.parsers
    assert str(rig).endswith("1 parsers, quota 4 a day")


def test_a_replay_of_an_empty_archive_finds_nothing(rig):
    fetch = replay_fetch(rig)
    with pytest.raises(SupplierUnavailable):
        fetch(rig.registry.get("gds"), task())


def test_a_stored_payload_comes_back_without_touching_a_supplier(rig):
    rig.archive.put(
        b"the payload",
        "gds",
        "/pricing",
        datetime(2025, 7, 19, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    reply = replay_fetch(rig)(rig.registry.get("gds"), task())
    assert reply.ok
    assert reply.body == b"the payload"
    assert reply.url.startswith("replay://gds")


def test_the_newest_stored_payload_is_the_one_replayed(rig):
    for day, body in ((17, b"old"), (19, b"newer"), (18, b"middle")):
        rig.archive.put(
            body,
            "gds",
            "/pricing",
            datetime(2025, 7, day, 6, 0),
            context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
        )
    assert replay_fetch(rig)(rig.registry.get("gds"), task()).body == b"newer"


def test_a_replay_keeps_the_suppliers_apart(rig):
    rig.archive.put(
        b"from the gateway",
        "gds",
        "/pricing",
        datetime(2025, 7, 19, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    fetch = replay_fetch(rig)
    with pytest.raises(SupplierUnavailable):
        fetch(rig.registry.get("ndc"), task(supplier="ndc"))


def test_a_replay_can_be_narrowed_to_one_supplier(rig):
    for name in ("gds", "ndc"):
        rig.archive.put(
            name.encode(),
            name,
            "/x",
            datetime(2025, 7, 19, 6, 0),
            context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
        )
    fetch = replay_fetch(rig, supplier_only="ndc")
    assert fetch(rig.registry.get("ndc"), task(supplier="ndc")).body == b"ndc"
    with pytest.raises(SupplierUnavailable):
        fetch(rig.registry.get("gds"), task())


def test_a_payload_stored_with_no_market_on_it_is_not_replayable(rig):
    rig.archive.put(b"orphan", "gds", "/pricing", datetime(2025, 7, 19, 6, 0))
    with pytest.raises(SupplierUnavailable):
        replay_fetch(rig)(rig.registry.get("gds"), task())


def test_a_whole_run_can_be_replayed_out_of_the_archive(rig):
    rig.archive.put(
        b"a payload",
        "gds",
        "/pricing",
        datetime(2025, 7, 19, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    report = rig.collector().run(
        [task()],
        TODAY,
        replay_fetch(rig),
        {"gds": harvest_of},
    )
    assert len(report) == 1
    assert report.results[0].ok


def test_a_replayed_market_nobody_collected_is_recorded_and_not_raised(rig):
    report = rig.collector().run(
        [task(), task(market=Market("MUC", "ORD"))],
        TODAY,
        replay_fetch(rig),
        {"gds": harvest_of},
    )
    assert report.by_outcome() == {"transient": 2}
    assert report.failures()[0].reason == "nothing stored for that market"


def test_a_live_fetch_asks_the_supplier_for_the_market_and_the_date(rig):
    asked: list[tuple[str, dict]] = []

    class Fake:
        def get(self, path, params=None, headers=None):
            asked.append((path, dict(params or {})))
            return Response(status=200, body=b"{}", url=path)

    fetch = live_fetch(rig, client_for=lambda supplier: Fake())
    fetch(rig.registry.get("gds"), task())
    path, params = asked[0]
    assert path == "/pricing"
    assert params["origin"] == "FRA"
    assert params["destination"] == "NYC"
    assert params["departure"] == "2025-08-24"


def test_a_live_fetch_makes_one_client_for_each_supplier(rig):
    made: list[str] = []

    class Fake:
        def get(self, path, params=None, headers=None):
            return Response(status=200, body=b"{}", url=path)

    def client_for(supplier):
        made.append(supplier.name)
        return Fake()

    fetch = live_fetch(rig, client_for=client_for)
    fetch(rig.registry.get("gds"), task())
    fetch(rig.registry.get("gds"), task(departure=date(2025, 9, 1)))
    fetch(rig.registry.get("ndc"), task(supplier="ndc"))
    assert made == ["gds", "ndc"]


def test_a_rig_can_be_built_over_a_registry_of_our_own(settings):
    registry = Registry()
    registry.add(
        Supplier(name="only", host="one.example", capabilities=frozenset({Capability.SHOP}))
    )
    made = build(settings, registry=registry, clock=FakeClock())
    assert made.registry.names() == ["only"]
    assert made.limiter.known_hosts() == ["one.example"]


def test_the_quota_still_stops_a_replay(rig):
    rig.archive.put(
        b"a payload",
        "gds",
        "/pricing",
        datetime(2025, 7, 19, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    tasks = [task() for _ in range(6)]
    report = rig.collector().run(tasks, TODAY, replay_fetch(rig), {"gds": harvest_of})
    assert report.skipped_budget == 2
    assert report.by_outcome()[Outcome.OK.value] == 4
