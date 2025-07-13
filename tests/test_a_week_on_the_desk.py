"""A week on the desk, driven entirely through the command line.

Every other test in this suite reaches into a module. This one does not: it
loads a tariff file, adds a watch, replays five days of collection, runs the
night, and reads the reports, all through the same commands the cron job
calls, against one database on disk.

That is the point. Every part below works and is tested, and the ways a desk
actually breaks are still all in the joins between them: a market key folded
one way in the schedule and another in the registry, a fare looked up by
airport where the tariff files cities, a mileage measured from a point with no
coordinates. Three of those got through the unit tests and were caught here or
by the command that exercises the same join.

The week is deliberately dull. Nothing exotic happens: one ticket, one market,
a fare that drops on the Wednesday and holds. If this test ever needs an
unusual itinerary to be interesting, the interesting thing belongs in a unit
test and not here.
"""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from decimal import Decimal

import pytest
from click.testing import CliRunner
from sqlalchemy import select

from gussetline.cli.main import cli
from gussetline.collect.archive import Archive
from gussetline.collect.fixedwidth import write_record
from gussetline.combinability import Combinability
from gussetline.config import Settings
from gussetline.load.fares import FARE_RECORD, HEADER_RECORD
from gussetline.load.rules import CHANGES, PENALTY, RULE_HEADER
from gussetline.money import Money
from gussetline.persistence.models import RecommendationRow, WatchRow
from gussetline.persistence.unitofwork import Database
from gussetline.rules.base import RuleSet
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty

MONDAY = date(2025, 8, 4)
DEPARTURE = date(2025, 9, 20)
SERIES = "FRA-NYC-2025-09-20-Y"
TICKET = "220-1234567890"

# What the market did over the week. Steady, then a drop that holds.
PRICES = {
    date(2025, 8, 4): "840.00",
    date(2025, 8, 5): "840.00",
    date(2025, 8, 6): "690.00",
    date(2025, 8, 7): "690.00",
    date(2025, 8, 8): "690.00",
}


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def home(tmp_path):
    return tmp_path


@pytest.fixture
def url(home):
    return f"sqlite+pysqlite:///{home / 'desk.sqlite3'}"


@pytest.fixture
def env(monkeypatch, home, url):
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(home / "raw"))
    return url


def run(runner, *args: str):
    reply = runner.invoke(cli, list(args))
    if reply.exception is not None and not isinstance(reply.exception, SystemExit):
        raise reply.exception
    return reply


def fare_file(home) -> str:
    rows = [
        write_record(HEADER_RECORD, {"carrier": "LH", "filed_on": MONDAY, "sequence": 1}),
        write_record(
            FARE_RECORD,
            {
                "carrier": "LH",
                "origin": "FRA",
                "destination": "JFK",
                "fare_basis": "KLWAP7RT",
                "currency": "EUR",
                "amount": Decimal("420.00"),
                "owrt": "2",
                "global_indicator": "AT",
                "rule_number": "0100",
                "routing_number": "0011",
                "tariff_number": "001",
                "footnote": None,
                "cabin": "Y",
                "fare_type": "P",
                "effective": date(2025, 1, 1),
                "discontinue": date(2025, 12, 31),
                "first_travel": date(2025, 1, 1),
                "last_travel": date(2025, 12, 31),
                "action": "A",
            },
        ),
    ]
    path = home / "lh-fares.dat"
    path.write_text("\n".join(rows), encoding="ascii")
    return str(path)


def rule_file(home) -> str:
    rows = [
        write_record(
            RULE_HEADER, {"carrier": "LH", "number": "0100", "tariff": "001", "action": "A"}
        ),
        write_record(
            PENALTY,
            {
                "action": "C",
                "permitted": "Y",
                "currency": "EUR",
                "before": Decimal("50.00"),
                "after": Decimal("100.00"),
                "applies": "T",
                "no_show_forfeits": None,
            },
        ),
        write_record(
            CHANGES,
            {
                "permitted": "Y",
                "basis": "C",
                "residual": "R",
                "keep_breaks": None,
                "same_carrier": None,
                "once_only": None,
            },
        ),
    ]
    path = home / "lh-rules.dat"
    path.write_text("\n".join(rows), encoding="ascii")
    return str(path)


def a_ticket(url: str) -> None:
    """One ticket, put in the way the ticketing feed would put it."""
    from gussetline.desk.ticket import Coupon, Ticket
    from gussetline.persistence.deskmap import ticket_to_row

    ticket = Ticket(
        number=TICKET,
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        coupons=(
            Coupon(
                position=0,
                carrier="LH",
                flight_number="400",
                origin="FRA",
                destination="JFK",
                departure=datetime(2025, 9, 20, 10, 25),
                booking_class="K",
                fare_basis="KLWAP7RT",
            ),
        ),
        issued_on=date(2025, 5, 2),
        validating_carrier="LH",
        base_fare=Money.of("840.00", "EUR"),
        total_paid=Money.of("1090.83", "EUR"),
    )
    with Database(Settings(database_url=url)).unit() as work:
        work.session.add(ticket_to_row(ticket))
        work.combinability.save(Combinability(carrier="LH", fare_basis="KLWAP7RT", round_trip=True))
        work.commit()


def payload_for(amount: str) -> bytes:
    """What the offer feed sends, in the shape its own parser reads."""
    return json.dumps(
        {
            "offers": [
                {
                    "id": "1",
                    "price": {
                        "baseAmount": {"amount": amount, "currency": "EUR"},
                        "totalAmount": {"amount": amount, "currency": "EUR"},
                    },
                    "fareBasis": ["KLWAP7RT"],
                    "validatingCarrier": "LH",
                    "journeys": [
                        {
                            "segments": [
                                {
                                    "marketingCarrier": "LH",
                                    "flightNumber": "400",
                                    "origin": "FRA",
                                    "destination": "JFK",
                                    "departureTime": "2025-09-20T10:25:00",
                                    "arrivalTime": "2025-09-20T13:05:00",
                                    "bookingClass": "K",
                                    "cabin": "Y",
                                }
                            ]
                        }
                    ],
                }
            ]
        }
    ).encode()


def stock_the_archive(home) -> None:
    """Five days of payloads, so the week can be replayed without a network."""
    archive = Archive(home / "raw")
    for day, amount in PRICES.items():
        archive.put(
            payload_for(amount),
            "ndc",
            "/v2/offers",
            datetime(day.year, day.month, day.day, 6, 0),
            context={"market": "FRA-NYC", "departure": DEPARTURE.isoformat()},
        )


def open_recommendations(url: str) -> list[tuple[Decimal, str]]:
    with Database(Settings(database_url=url)).unit() as work:
        rows = work.session.scalars(
            select(RecommendationRow).where(RecommendationRow.state == "open")
        ).all()
        return [(row.benefit_amount, row.currency) for row in rows]


def test_a_week_on_the_desk(runner, env, home):
    # Monday morning. Nothing is set up.
    assert "schema built" in run(runner, "create-schema").output

    # The tariff arrives and is loaded.
    assert "loaded 1 fares" in run(runner, "load", "fares", fare_file(home)).output
    assert "kept 1 rules" in run(runner, "load", "rules", rule_file(home)).output

    # The ticketing feed puts a ticket in, and the desk starts watching it.
    a_ticket(env)
    added = run(runner, "watch", "add", TICKET, "--on", MONDAY.isoformat())
    assert f"watching {SERIES}" in added.output
    # The threshold came off the filed change fee and not off a default.
    assert "a drop has to beat 75.00 EUR" in added.output

    # Five mornings of collection, replayed out of the archive so the test
    # never touches a supplier.
    stock_the_archive(home)
    for offset in range(5):
        day = (MONDAY + timedelta(days=offset)).isoformat()
        assert "1 tasks" in run(runner, "collect", "replay", "--on", day).output

    # The desk polls for real on the Friday and keeps the run.
    _collect_for_real(runner)

    # Friday night. The drop has held since Wednesday, so it is worth telling
    # somebody about.
    night = run(runner, "night", "--on", "2025-08-08")
    assert "raised 1 worth 100.00 EUR" in night.output
    assert open_recommendations(env) == [(Decimal("100.00"), "EUR")]

    # Saturday morning, the three reports.
    savings = run(runner, "report", "savings", "--since", "2025-08-01", "--until", "2025-08-09")
    assert "found     100.00 EUR" in savings.output
    assert "taken     0 EUR" in savings.output
    assert "open      1" in savings.output

    reliability = run(runner, "report", "reliability")
    assert "ndc        sound" in reliability.output

    coverage = run(runner, "report", "coverage", "--on", "2025-08-09")
    assert "priced    1" in coverage.output
    assert "rate      1.00" in coverage.output

    # An agent takes it, and the savings report moves.
    _take_it(env)
    settled = run(runner, "report", "savings", "--since", "2025-08-01", "--until", "2025-08-09")
    assert "taken     100.00 EUR" in settled.output
    assert "take rate 1.00 over 1 decided" in settled.output


def _collect_for_real(runner) -> None:
    """One real run, so the reliability report has something to read.

    Replays deliberately do not write a run: nothing was asked of anybody, and
    a reliability report built over them would say a supplier answered on a
    day it was never called. So the week has to make one call that counts, and
    what is stubbed is the socket rather than the run.

    The stub replaces the name the command module holds and not the one the
    rig module defines, because the command imported it at module load and
    patching the far side would change nothing at all.
    """
    from gussetline.cli import collect as command
    from gussetline.collect.http import Response

    real = command.live_fetch

    def stubbed(rig, client_for=None):
        def fetch(supplier, task):
            return Response(status=200, body=payload_for("690.00"), url="/v2/offers")

        return fetch

    command.live_fetch = stubbed
    try:
        reply = runner.invoke(cli, ["collect", "run", "--on", "2025-08-08"])
        assert reply.exit_code == 0, reply.output
        assert "kept as run" in reply.output
    finally:
        command.live_fetch = real


def _take_it(url: str) -> None:
    """An agent settles the open recommendation, the way the API does."""
    with Database(Settings(database_url=url)).unit() as work:
        row = work.session.scalars(
            select(RecommendationRow).where(RecommendationRow.state == "open")
        ).one()
        row.state = "taken"
        row.decided_on = date(2025, 8, 9)
        row.decided_by = "anna"
        work.commit()


def test_the_week_leaves_the_watch_live_because_nobody_reissued_yet(runner, env, home):
    run(runner, "create-schema")
    run(runner, "load", "fares", fare_file(home))
    run(runner, "load", "rules", rule_file(home))
    a_ticket(env)
    run(runner, "watch", "add", TICKET, "--on", MONDAY.isoformat())
    stock_the_archive(home)
    for offset in range(5):
        run(runner, "collect", "replay", "--on", (MONDAY + timedelta(days=offset)).isoformat())
    run(runner, "night", "--on", "2025-08-08")

    with Database(Settings(database_url=env)).unit() as work:
        rows = work.session.scalars(select(WatchRow)).all()
        assert [(row.active, row.retired_reason) for row in rows] == [(True, "")]


def test_the_same_night_run_twice_does_not_raise_the_number_twice(runner, env, home):
    run(runner, "create-schema")
    run(runner, "load", "fares", fare_file(home))
    run(runner, "load", "rules", rule_file(home))
    a_ticket(env)
    run(runner, "watch", "add", TICKET, "--on", MONDAY.isoformat())
    stock_the_archive(home)
    for offset in range(5):
        run(runner, "collect", "replay", "--on", (MONDAY + timedelta(days=offset)).isoformat())

    run(runner, "night", "--on", "2025-08-08")
    second = run(runner, "night", "--on", "2025-08-08")
    assert "wrote 0 recommendations" in second.output
    assert len(open_recommendations(env)) == 1


def test_the_desk_reports_what_it_holds_after_the_week(runner, env, home):
    run(runner, "create-schema")
    run(runner, "load", "fares", fare_file(home))
    run(runner, "load", "rules", rule_file(home))
    a_ticket(env)
    run(runner, "watch", "add", TICKET, "--on", MONDAY.isoformat())
    stock_the_archive(home)
    run(runner, "collect", "replay", "--on", MONDAY.isoformat())

    check = run(runner, "check")
    assert "fares         1" in check.output
    assert "combinability 1" in check.output
    assert "observations  1" in check.output


def test_a_rule_that_forbids_a_change_ends_the_week_with_a_retired_watch(runner, env, home):
    run(runner, "create-schema")
    run(runner, "load", "fares", fare_file(home))
    a_ticket(env)

    with Database(Settings(database_url=env)).unit() as work:
        work.rules.save(
            RuleSet(
                carrier="LH",
                number="0100",
                tariff="1",
                provisions=(
                    VoluntaryChange(
                        permitted=False,
                        basis=RepricingBasis.CURRENT,
                        residual=Residual.REFUNDABLE,
                    ),
                    Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR")),
                ),
            )
        )
        work.commit()

    run(runner, "watch", "add", TICKET, "--on", MONDAY.isoformat())
    stock_the_archive(home)
    for offset in range(5):
        run(runner, "collect", "replay", "--on", (MONDAY + timedelta(days=offset)).isoformat())

    night = run(runner, "night", "--on", "2025-08-08")
    assert "does not permit a change" in night.output
    assert open_recommendations(env) == []

    with Database(Settings(database_url=env)).unit() as work:
        rows = work.session.scalars(select(WatchRow)).all()
        assert [row.active for row in rows] == [False]
