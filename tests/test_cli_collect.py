from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner

from gussetline.cli.main import cli
from gussetline.collect.archive import Archive
from gussetline.config import Settings
from gussetline.persistence.models import TicketRow, WatchRow
from gussetline.persistence.unitofwork import Database

TODAY = "2025-07-21"
AUGUST = date(2025, 8, 24)


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
    return home


def base(*args: str) -> list[str]:
    return list(args)


def make_watch(url: str, origin="FRA", destination="JFK", departure=AUGUST, last=None) -> None:
    database = Database(Settings(database_url=url))
    database.create_schema()
    with database.unit() as work:
        ticket = TicketRow(
            number=f"220-{origin}{destination}",
            record_locator="ABC123",
            passenger_name="MUELLER/ANNA",
            origin=origin,
            destination=destination,
            departure=departure,
            issued_on=date(2025, 2, 14),
            validating_carrier="LH",
            base_amount=Decimal("840.00"),
            base_currency="EUR",
            total_amount=Decimal("1090.83"),
        )
        ticket.watches.append(
            WatchRow(
                series_key=f"{origin}-{destination}-{departure.isoformat()}-Y",
                origin=origin,
                destination=destination,
                departure=departure,
                threshold_amount=Decimal("50.00"),
                threshold_currency="EUR",
                last_checked=last,
            )
        )
        work.session.add(ticket)
        work.commit()


def test_the_collect_group_lists_its_three_commands(runner, env):
    reply = runner.invoke(cli, ["collect", "--help"])
    assert "plan" in reply.output
    assert "run" in reply.output
    assert "replay" in reply.output


def test_a_plan_against_an_empty_desk_plans_nothing(runner, env, url):
    Database(Settings(database_url=url)).create_schema()
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY])
    assert reply.exit_code == 0
    assert "0 series followed" in reply.output
    assert "0 tasks over 0 markets" in reply.output


def test_a_watch_becomes_a_series_and_a_task(runner, env, url):
    make_watch(url)
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY])
    assert "1 series followed, 1 due" in reply.output
    assert "1 tasks over 1 markets" in reply.output
    assert "FRA-NYC 2025-08-24" in reply.output


def test_the_last_check_is_carried_over_so_the_cadence_survives_a_restart(runner, env, url):
    # Without this every run after a restart thinks nothing was ever polled
    # and asks for the whole book on the first morning.
    make_watch(url, last=datetime(2025, 7, 20, 6, 0))
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY])
    assert "1 series followed, 0 due" in reply.output


def test_a_market_the_feed_covers_goes_to_the_feed_and_not_the_gateway(runner, env, url):
    # Both cover FRA to New York. The feed is free, so it gets the question.
    make_watch(url)
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY])
    assert "ndc FRA-NYC" in reply.output


def test_a_market_only_the_gateway_covers_goes_to_the_gateway(runner, env, url):
    make_watch(url, origin="LHR", destination="DXB")
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY])
    assert "gds LON-DXB" in reply.output


def test_the_supplier_can_be_pinned(runner, env, url):
    make_watch(url)
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY, "--supplier", "gds"])
    assert "gds FRA-NYC" in reply.output


def test_pinning_a_supplier_that_does_not_cover_the_market_plans_nothing(runner, env, url):
    make_watch(url, origin="LHR", destination="DXB")
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY, "--supplier", "ndc"])
    assert "0 tasks" in reply.output


def test_the_budget_caps_the_plan(runner, env, url):
    make_watch(url)
    make_watch(url, destination="ORD")
    make_watch(url, destination="SIN")
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY, "--budget", "2"])
    assert "2 tasks" in reply.output


def test_the_closest_departures_are_planned_first(runner, env, url):
    make_watch(url, destination="ORD", departure=date(2025, 12, 1))
    make_watch(url, destination="JFK", departure=date(2025, 7, 30))
    reply = runner.invoke(cli, ["collect", "plan", "--on", TODAY, "--budget", "1"])
    assert "2025-07-30" in reply.output
    assert "2025-12-01" not in reply.output


def test_a_dry_run_of_the_real_command_goes_nowhere(runner, env, url, monkeypatch):
    make_watch(url)
    monkeypatch.setenv("GUSSETLINE_DRY_RUN", "1")
    reply = runner.invoke(cli, ["collect", "run", "--on", TODAY])
    assert reply.exit_code == 0
    assert "dry run, nothing went out" in reply.output


def test_a_dry_run_keeps_no_run(runner, env, url, monkeypatch):
    make_watch(url)
    monkeypatch.setenv("GUSSETLINE_DRY_RUN", "1")
    runner.invoke(cli, ["collect", "run", "--on", TODAY])
    database = Database(Settings(database_url=url))
    with database.unit() as work:
        assert work.runs.count() == 0


def test_a_replay_of_an_empty_archive_reports_that_nothing_was_stored(runner, env, url):
    make_watch(url)
    reply = runner.invoke(cli, ["collect", "replay", "--on", TODAY])
    assert reply.exit_code == 0
    assert "nothing stored for that market" in reply.output


def test_a_replay_keeps_nothing_and_says_why(runner, env, url, home):
    make_watch(url)
    archive = Archive(home / "raw")
    archive.put(
        b"{}",
        "ndc",
        "/v2/offers",
        datetime(2025, 7, 20, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    reply = runner.invoke(cli, ["collect", "replay", "--on", TODAY])
    assert "not kept, because nothing was asked of anybody" in reply.output
    database = Database(Settings(database_url=url))
    with database.unit() as work:
        assert work.runs.count() == 0


def test_a_replay_reparses_the_stored_payload(runner, env, url, home):
    make_watch(url)
    payload = (
        b'{"offers": [{"id": "1", "total": {"amount": "700.00", "currency": "EUR"},'
        b' "fareBasis": "KLWAP7RT", "segments": [{"carrier": "LH", "number": "400",'
        b' "origin": "FRA", "destination": "JFK", "departure": "2025-08-24T10:25:00",'
        b' "arrival": "2025-08-24T13:05:00", "bookingClass": "K"}]}]}'
    )
    Archive(home / "raw").put(
        payload,
        "ndc",
        "/v2/offers",
        datetime(2025, 7, 20, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    reply = runner.invoke(cli, ["collect", "replay", "--on", TODAY])
    assert "ok" in reply.output
    assert "1 offers" in reply.output


def test_a_replay_looks_at_every_series_and_not_only_the_due_ones(runner, env, url, home):
    # The point of a replay is to reparse history, so a cadence that says a
    # market is not due today has nothing to do with it.
    make_watch(url, last=datetime(2025, 7, 20, 6, 0))
    Archive(home / "raw").put(
        b"{}",
        "ndc",
        "/v2/offers",
        datetime(2025, 7, 20, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    reply = runner.invoke(cli, ["collect", "replay", "--on", TODAY])
    assert "1 tasks" in reply.output


def test_the_cost_of_a_run_is_printed(runner, env, url, home):
    make_watch(url)
    Archive(home / "raw").put(
        b"{}",
        "ndc",
        "/v2/offers",
        datetime(2025, 7, 20, 6, 0),
        context={"market": "FRA-NYC", "departure": AUGUST.isoformat()},
    )
    reply = runner.invoke(cli, ["collect", "replay", "--on", TODAY])
    assert "cost 0" in reply.output
