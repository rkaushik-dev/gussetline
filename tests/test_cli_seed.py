from __future__ import annotations

from datetime import date

import pytest
from click.testing import CliRunner

from gussetline.cli.main import cli
from gussetline.config import Settings
from gussetline.persistence.unitofwork import Database

TODAY = "2025-11-03"


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def env(monkeypatch, tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(tmp_path / "raw"))
    Database(Settings(database_url=url)).create_schema()
    return url


def database(url: str) -> Database:
    return Database(Settings(database_url=url))


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_a_seeded_desk_holds_everything_the_other_commands_read(runner, env):
    reply = run(runner, "seed", "--on", TODAY)
    assert reply.exit_code == 0

    with database(env).unit() as work:
        assert work.tariff.count_fares() == 12
        assert work.rules.count() == 2
        # One record per fare basis, not per fare.
        assert work.combinability.count() == 3
        # Two markets, two distinct prices each. One row per price and not
        # one per morning, which is what a real run writes.
        assert work.observations.count() == 4


def test_it_says_what_to_try_next(runner, env):
    reply = run(runner, "seed", "--on", TODAY)
    assert "gussetline price" in reply.output
    assert "gussetline night" in reply.output


def test_a_seeded_desk_prices_a_round_trip(runner, env):
    run(runner, "seed", "--on", TODAY)
    reply = run(runner, "price", "FRA LH400 K 18DEC JFK LH401 K 28DEC FRA", "--on", TODAY)
    assert "base fare" in reply.output
    assert reply.exit_code == 0


def test_a_seeded_desk_raises_a_recommendation_on_the_first_night(runner, env):
    # The whole reason the readings end in a drop that holds.
    run(runner, "seed", "--on", TODAY)
    reply = run(runner, "night", "--on", TODAY)
    assert "raised 2" in reply.output


def test_a_seeded_desk_has_something_in_every_report(runner, env):
    run(runner, "seed", "--on", TODAY)
    run(runner, "night", "--on", TODAY)

    assert "found" in run(runner, "report", "savings", "--until", TODAY).output
    assert "2 open" in run(runner, "report", "queue", "--on", TODAY).output
    assert "followed  2" in run(runner, "report", "coverage", "--on", TODAY).output


def test_a_desk_that_already_holds_something_is_left_alone(runner, env):
    # Invented fares next to filed ones with nothing to tell them apart.
    run(runner, "seed", "--on", TODAY)
    again = run(runner, "seed", "--on", TODAY)
    assert again.exit_code != 0
    assert "already holds" in again.output
    assert "12 fares" in again.output


def test_it_can_be_forced_when_somebody_means_it(runner, env):
    run(runner, "seed", "--on", TODAY)
    assert run(runner, "seed", "--on", TODAY, "--force").exit_code == 0


def test_forcing_it_takes_out_what_the_last_seed_wrote_and_nothing_else(runner, env):
    # Anything else on the desk is somebody's real data, and forcing a seed
    # is not a licence to take it out.
    from decimal import Decimal

    from gussetline.persistence.models import TicketRow

    run(runner, "seed", "--on", TODAY)
    with database(env).unit() as work:
        work.session.add(
            TicketRow(
                number="220-9999999999",
                record_locator="REAL01",
                passenger_name="SOMEBODY/REAL",
                origin="FRA",
                destination="JFK",
                departure=date(2026, 1, 1),
                issued_on=date(2025, 1, 1),
                validating_carrier="LH",
                base_amount=Decimal("840.00"),
                base_currency="EUR",
                total_amount=Decimal("840.00"),
            )
        )
        work.commit()

    assert run(runner, "seed", "--on", TODAY, "--force").exit_code == 0

    with database(env).unit() as work:
        from sqlalchemy import select

        numbers = {row.number for row in work.session.scalars(select(TicketRow)).all()}
    assert "220-9999999999" in numbers
    assert len(numbers) == 3


def test_everything_it_writes_is_obviously_invented(runner, env):
    # Seed data that looks real ends up quoted in a meeting.
    run(runner, "seed", "--on", TODAY)
    with database(env).unit() as work:
        from sqlalchemy import select

        from gussetline.persistence.models import TicketRow

        tickets = work.session.scalars(select(TicketRow)).all()
        assert all(row.passenger_name == "TEST/PASSENGER" for row in tickets)
        assert all(row.record_locator.startswith("SEED") for row in tickets)


def test_the_departure_it_invents_is_far_enough_out_to_be_watched(runner, env):
    run(runner, "seed", "--on", TODAY)
    with database(env).unit() as work:
        departures = [row.departure for row in work.watches.live(date(2025, 11, 3))]
    assert len(departures) == 2
    assert all(departure > date(2025, 11, 3) for departure in departures)
