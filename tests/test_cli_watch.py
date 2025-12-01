from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner
from sqlalchemy import select

from gussetline.cli.main import cli
from gussetline.config import Settings
from gussetline.errors import NotFoundError, ValidationError
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.persistence.models import CouponRow, ObservationRow, TicketRow, WatchRow
from gussetline.persistence.unitofwork import Database
from gussetline.rules.base import RuleSet
from gussetline.rules.penalties import Action, Penalty
from gussetline.tariff import Fare, FareType, OwRt

TODAY = "2025-08-01"
DEPARTURE = date(2025, 9, 20)
SERIES = "FRA-NYC-2025-09-20-Y"


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def url(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"


@pytest.fixture
def env(monkeypatch, url, tmp_path):
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(tmp_path / "raw"))
    Database(Settings(database_url=url)).create_schema()
    return url


def database(url: str) -> Database:
    return Database(Settings(database_url=url))


def a_ticket(
    work, number="220-1234567890", departure=DEPARTURE, basis="KLWAP7RT", destination="JFK"
) -> TicketRow:
    ticket = TicketRow(
        number=number,
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        origin="FRA",
        destination=destination,
        departure=departure,
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_amount=Decimal("840.00"),
        base_currency="EUR",
        total_amount=Decimal("1090.83"),
    )
    ticket.coupons.append(
        CouponRow(
            position=0,
            carrier="LH",
            flight_number="400",
            origin="FRA",
            destination=destination,
            departure=datetime(departure.year, departure.month, departure.day, 10, 25),
            booking_class="K",
            fare_basis=basis,
        )
    )
    work.session.add(ticket)
    work.session.flush()
    return ticket


def a_fare_and_rule(work, fee="80.00", basis="KLWAP7RT") -> None:
    row = work.tariff.open_load(
        carrier="LH",
        filename="test.dat",
        digest="d" * 32,
        loaded_at=datetime(2025, 3, 1, 6, 0),
        effective=date(2025, 3, 1),
    )
    work.tariff.add_fares(
        row,
        [
            Fare(
                carrier="LH",
                origin="FRA",
                destination="JFK",
                fare_basis=basis,
                amount=Money.of("420.00", "EUR"),
                owrt=OwRt.ROUND_TRIP_HALF,
                global_indicator=GlobalIndicator.AT,
                rule_number="0100",
                routing_number="0011",
                tariff_number="1",
                fare_type=FareType.PUBLISHED,
                effective=date(2025, 1, 1),
                discontinue=date(2025, 12, 31),
                first_travel=date(2025, 1, 1),
                last_travel=date(2025, 12, 31),
            )
        ],
    )
    work.rules.save(
        RuleSet(
            carrier="LH",
            number="0100",
            tariff="1",
            provisions=(Penalty(action=Action.CHANGE, before_departure=Money.of(fee, "EUR")),),
        )
    )


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def watches(url: str) -> list[tuple[str, bool, Decimal, str]]:
    with database(url).unit() as work:
        rows = work.session.scalars(select(WatchRow)).all()
        return [
            (row.series_key, row.active, row.threshold_amount, row.retired_reason) for row in rows
        ]


def test_the_watch_group_lists_its_three_commands(runner, env):
    reply = run(runner, "watch", "--help")
    assert "add" in reply.output
    assert "list" in reply.output
    assert "retire" in reply.output


def test_a_ticket_becomes_a_watch(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert reply.exit_code == 0
    assert f"watching {SERIES}" in reply.output
    assert watches(env)[0][0] == SERIES


def test_the_series_key_is_built_from_cities_and_not_airports(runner, env):
    with database(env).unit() as work:
        a_ticket(work, destination="EWR")
        work.commit()

    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert watches(env)[0][0] == SERIES


def test_a_ticket_nobody_has_is_a_not_found(runner, env):
    reply = run(runner, "watch", "add", "220-0000000000", "--on", TODAY)
    assert reply.exit_code != 0
    assert isinstance(reply.exception, NotFoundError)


def test_a_departure_that_has_gone_is_refused_with_the_date_it_read(runner, env):
    # A ticket somebody typed the wrong year on. Accepting it puts a watch on
    # the schedule that will never be polled and never be looked at again.
    with database(env).unit() as work:
        a_ticket(work, departure=date(2025, 1, 5))
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert reply.exit_code != 0
    assert isinstance(reply.exception, ValidationError)
    assert "2025-01-05" in str(reply.exception)


def test_the_same_series_is_not_watched_twice(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()

    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    again = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert again.exit_code != 0
    assert "already watched" in again.output


def test_the_threshold_is_the_change_fee_plus_a_margin(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        a_fare_and_rule(work, fee="80.00")
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert "a drop has to beat 105.00 EUR" in reply.output


def test_a_bigger_fee_makes_a_bigger_threshold(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        a_fare_and_rule(work, fee="250.00")
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert "275.00 EUR" in reply.output


def test_with_no_rule_loaded_the_settings_figure_is_used(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert "50.00 EUR" in reply.output


def test_the_threshold_can_be_overridden_by_hand(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        a_fare_and_rule(work)
        work.commit()

    run(runner, "watch", "add", "220-1234567890", "--on", TODAY, "--threshold", "300.00")
    assert watches(env)[0][2] == Decimal("300.00")


def test_a_cabin_can_be_named(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()

    run(runner, "watch", "add", "220-1234567890", "--on", TODAY, "--cabin", "C")
    assert watches(env)[0][0].endswith("-C")


def test_a_cabin_nobody_sells_is_refused(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY, "--cabin", "Q")
    assert reply.exit_code != 0


def test_an_empty_desk_lists_nothing(runner, env):
    reply = run(runner, "watch", "list", "--on", TODAY)
    assert "nothing watched" in reply.output


def test_a_watch_lists_with_what_was_paid_and_what_was_seen(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)

    with database(env).unit() as work:
        work.session.add(
            ObservationRow(
                series_key=SERIES,
                source="gds",
                fingerprint="a" * 32,
                origin="FRA",
                destination="JFK",
                departure=DEPARTURE,
                cabin="Y",
                base_amount=Decimal("700.00"),
                base_currency="EUR",
                tax_amount=Decimal("0.00"),
                surcharge_amount=Decimal("0.00"),
                total_amount=Decimal("700.00"),
                first_seen=datetime(2025, 7, 30, 6, 0),
                last_seen=datetime(2025, 7, 30, 6, 0),
            )
        )
        work.commit()

    reply = run(runner, "watch", "list", "--on", TODAY)
    assert SERIES in reply.output
    assert "paid 840.00" in reply.output
    assert "cheapest 700.00 EUR" in reply.output


def test_a_watch_with_nothing_seen_says_so(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert "nothing seen" in run(runner, "watch", "list", "--on", TODAY).output


def test_retiring_records_that_a_person_said_so(runner, env):
    # A watch closed by a person and one closed because the departure went
    # are different readings of the desk.
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)

    reply = run(runner, "watch", "retire", SERIES)
    assert reply.exit_code == 0
    series, active, _, reason = watches(env)[0]
    assert not active
    assert reason == "closed by the desk"


def test_a_retired_watch_is_out_of_the_listing_unless_it_is_asked_for(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    run(runner, "watch", "retire", SERIES)

    assert "nothing watched" in run(runner, "watch", "list", "--on", TODAY).output
    everything = run(runner, "watch", "list", "--on", TODAY, "--all")
    assert "closed by the desk" in everything.output


def test_retiring_something_that_is_not_there_is_a_not_found(runner, env):
    reply = run(runner, "watch", "retire", "FRA-NYC-2099-01-01-Y")
    assert reply.exit_code != 0
    assert isinstance(reply.exception, NotFoundError)


def test_retiring_something_that_is_not_a_series_key_says_which_argument_is_wrong(runner, env):
    # A typo used to come back as no such watch, which sends somebody looking
    # in the database for a row that was never going to be there.
    reply = run(runner, "watch", "retire", "FRA-NYC-2099-1-1")
    assert reply.exit_code != 0
    assert isinstance(reply.exception, ValidationError)
    assert "not a series key" in str(reply.exception)


def test_retiring_twice_says_it_is_already_retired(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    run(runner, "watch", "retire", SERIES)

    again = run(runner, "watch", "retire", SERIES)
    assert again.exit_code != 0
    assert "already retired" in again.output


def test_a_retired_series_can_be_watched_again(runner, env):
    with database(env).unit() as work:
        a_ticket(work)
        work.commit()
    run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    run(runner, "watch", "retire", SERIES)

    reply = run(runner, "watch", "add", "220-1234567890", "--on", TODAY)
    assert reply.exit_code == 0
    assert len(watches(env)) == 2
