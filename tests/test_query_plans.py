"""The queries the desk runs often, checked against the plan the engine picks.

Every index in the schema was added for a query somebody had in mind, and none
of them says which. A year later the query has been rewritten, the index is
dead weight on every insert, and the query it was supposed to serve is a full
scan that nobody notices because the test database has nine rows in it.

So this asks SQLite what it would actually do. `EXPLAIN QUERY PLAN` names the
index it chose, or says it is scanning, and that is the only check here: the
hot queries use an index and it is the one they were given.

The numbers matter more than they look. The observations table grows by
several million rows a year and the night run reads it once per watch, so a
scan there is the difference between a run that finishes before the desk opens
and one that does not. The fares table is small and read constantly, which is
the other shape of the same problem.

This is not a benchmark. Timings on a laptop under a test runner say nothing,
and a test that fails when the machine is busy is a test somebody deletes.
The plan is a fact about the query and it does not move.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import select, text

from gussetline.persistence.models import (
    FareRow,
    LoadRow,
    ObservationRow,
    RecommendationRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import in_memory

TODAY = date(2025, 12, 1)


@pytest.fixture
def database():
    made = in_memory()
    with made.unit() as work:
        _stock(work)
        work.commit()
    return made


def _stock(work) -> None:
    """Enough rows that the planner has something to think about."""
    load = LoadRow(
        carrier="LH",
        filename="plans.dat",
        digest="0" * 32,
        loaded_at=datetime(2025, 1, 1, 6, 0),
        effective_from=date(2025, 1, 1),
    )
    work.session.add(load)
    work.session.flush()

    for index in range(200):
        work.session.add(
            FareRow(
                load_id=load.id,
                carrier="LH" if index % 2 else "UA",
                origin="FRA",
                destination="NYC" if index % 3 else "CHI",
                fare_basis=f"K{index:04d}",
                amount=Decimal("420.00"),
                currency="EUR",
                owrt="2",
                global_indicator="AT",
                rule_number="0100",
                routing_number="0011",
                tariff_number="1",
                cabin="Y",
                fare_type="published",
                effective=date(2025, 1, 1),
                discontinue=date(2025, 12, 31),
                first_travel=date(2025, 1, 1),
                last_travel=date(2025, 12, 31),
            )
        )

    for index in range(200):
        work.session.add(
            ObservationRow(
                series_key=f"FRA-NYC-2026-0{index % 9 + 1}-01-Y",
                source="gds",
                fingerprint=f"{index:032d}",
                origin="FRA",
                destination="JFK",
                departure=date(2026, index % 9 + 1, 1),
                cabin="Y",
                base_amount=Decimal("700.00"),
                base_currency="EUR",
                tax_amount=Decimal("0.00"),
                surcharge_amount=Decimal("0.00"),
                total_amount=Decimal("700.00"),
                first_seen=datetime(2025, 11, 1, 6, 0),
                last_seen=datetime(2025, 11, index % 28 + 1, 6, 0),
            )
        )


def plan_for(work, statement) -> str:
    """What SQLite says it would do, as one string to look for a name in."""
    compiled = statement.compile(work.session.bind, compile_kwargs={"literal_binds": True})
    rows = work.session.execute(text(f"EXPLAIN QUERY PLAN {compiled}")).all()
    return " | ".join(str(row[-1]) for row in rows)


def test_the_night_run_reads_a_series_by_its_own_index(database):
    # Several million rows a year, read once per watch. A scan here is the
    # difference between a run that finishes before the desk opens and one
    # that does not.
    with database.unit() as work:
        plan = plan_for(
            work,
            select(ObservationRow)
            .where(ObservationRow.series_key == "FRA-NYC-2026-01-01-Y")
            .order_by(ObservationRow.last_seen.desc())
            .limit(8),
        )
    assert "ix_observations_series_seen" in plan
    assert "SCAN observations" not in plan


def test_the_market_lookup_uses_the_market_index(database):
    with database.unit() as work:
        plan = plan_for(
            work,
            select(ObservationRow).where(
                ObservationRow.origin == "FRA",
                ObservationRow.destination == "JFK",
                ObservationRow.departure == date(2026, 1, 1),
            ),
        )
    assert "ix_observations_market" in plan


def test_a_fare_lookup_uses_the_index_it_was_given(database):
    # Small and read constantly, which is the other shape of the same
    # problem: a scan is cheap once and not cheap on every component.
    with database.unit() as work:
        plan = plan_for(
            work,
            select(FareRow).where(
                FareRow.carrier == "LH",
                FareRow.origin == "FRA",
                FareRow.destination == "NYC",
            ),
        )
    assert "ix_fares" in plan
    assert "SCAN fares" not in plan


def test_a_fare_lookup_with_a_sale_window_uses_the_wider_index(database):
    with database.unit() as work:
        plan = plan_for(
            work,
            select(FareRow).where(
                FareRow.carrier == "LH",
                FareRow.origin == "FRA",
                FareRow.destination == "NYC",
                FareRow.effective <= TODAY,
                FareRow.discontinue >= TODAY,
            ),
        )
    assert "ix_fares_lookup" in plan


def test_a_fare_by_basis_still_uses_an_index(database):
    # Added for the night run, which gets from a ticket's fare basis to its
    # rule and has no market to narrow on.
    with database.unit() as work:
        plan = plan_for(
            work,
            select(FareRow).where(FareRow.carrier == "LH", FareRow.fare_basis == "K0001"),
        )
    assert "ix_fares_basis" in plan, plan
    assert "SCAN fares" not in plan


def test_the_open_book_uses_the_state_index(database):
    with database.unit() as work:
        plan = plan_for(work, select(RecommendationRow).where(RecommendationRow.state == "open"))
    assert "ix_recommendations_state" in plan


def test_the_live_watches_use_the_live_index(database):
    with database.unit() as work:
        plan = plan_for(
            work,
            select(WatchRow).where(WatchRow.active, WatchRow.departure >= TODAY),
        )
    assert "ix_watches_live" in plan


def test_the_series_index_covers_the_ordering_and_not_only_the_filter(database):
    # An index that filters but does not order leaves a sort behind it, and
    # the night run's query is a filter plus an order plus a limit.
    with database.unit() as work:
        plan = plan_for(
            work,
            select(ObservationRow)
            .where(ObservationRow.series_key == "FRA-NYC-2026-01-01-Y")
            .order_by(ObservationRow.last_seen.desc()),
        )
    assert "USE TEMP B-TREE" not in plan, plan


def test_every_index_in_the_schema_is_named_after_what_it_is_for(database):
    # A named index can be looked for in a plan. An automatic one cannot,
    # and the whole file above is looking for names.
    with database.unit() as work:
        rows = work.session.execute(
            text("SELECT name FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL")
        ).all()
    names = [str(row[0]) for row in rows if not str(row[0]).startswith("sqlite_")]
    assert names
    assert all(name.startswith(("ix_", "uq_", "pk_")) for name in names), names
