from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner
from sqlalchemy import select

from gussetline.cli.main import cli
from gussetline.config import Settings
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.persistence.models import (
    CouponRow,
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import Database
from gussetline.rules.base import RuleSet
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty
from gussetline.tariff import Fare, FareType, OwRt

TODAY = "2025-07-28"
DEPARTURE = date(2025, 8, 24)
SERIES = "FRA-NYC-2025-08-24-Y"


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


def make_ticket(work, number="220-1234567890", basis="KLWAP7RT") -> TicketRow:
    ticket = TicketRow(
        number=number,
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        origin="FRA",
        destination="JFK",
        departure=DEPARTURE,
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
            destination="JFK",
            departure=datetime(2025, 8, 24, 10, 25),
            booking_class="K",
            fare_basis=basis,
        )
    )
    work.session.add(ticket)
    work.session.flush()
    return ticket


def make_watch(work, ticket: TicketRow, series=SERIES) -> WatchRow:
    watch = WatchRow(
        ticket_id=ticket.id,
        series_key=series,
        origin="FRA",
        destination="JFK",
        departure=DEPARTURE,
        threshold_amount=Decimal("50.00"),
        threshold_currency="EUR",
    )
    work.session.add(watch)
    work.session.flush()
    return watch


def observe(work, amount: str, seen: datetime, series=SERIES, fingerprint="") -> None:
    work.session.add(
        ObservationRow(
            series_key=series,
            source="gds",
            fingerprint=fingerprint or f"{amount}-{seen:%d}".ljust(32, "x"),
            origin="FRA",
            destination="JFK",
            departure=DEPARTURE,
            cabin="Y",
            base_amount=Decimal(amount),
            base_currency="EUR",
            tax_amount=Decimal("0.00"),
            surcharge_amount=Decimal("0.00"),
            total_amount=Decimal(amount),
            first_seen=seen,
            last_seen=seen,
        )
    )


def file_rule(work, permitted=True, basis=RepricingBasis.CURRENT, residual=Residual.REFUNDABLE):
    work.rules.save(
        RuleSet(
            carrier="LH",
            number="0100",
            tariff="1",
            provisions=(
                VoluntaryChange(permitted=permitted, basis=basis, residual=residual),
                Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR")),
            ),
        )
    )


def file_fare(work, basis="KLWAP7RT", rule="0100"):
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
                rule_number=rule,
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


def a_desk(url: str, *, with_rule=True, with_fare=True, drops=("700.00", "700.00")) -> None:
    with database(url).unit() as work:
        ticket = make_ticket(work)
        make_watch(work, ticket)
        for index, amount in enumerate(drops):
            observe(work, amount, datetime(2025, 7, 26 + index, 6, 0))
        if with_fare:
            file_fare(work)
        if with_rule:
            file_rule(work)
        work.commit()


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def in_state(url: str, state: str = "open") -> list[tuple[Decimal, str]]:
    """The amounts on the recommendations in one state, read inside the session."""
    with database(url).unit() as work:
        rows = work.session.scalars(
            select(RecommendationRow).where(RecommendationRow.state == state)
        ).all()
        return [(row.benefit_amount, row.currency) for row in rows]


def test_the_night_command_shows_up_in_the_help(runner, env):
    assert "night" in run(runner, "--help").output


def test_a_desk_with_no_watches_says_so(runner, env):
    reply = run(runner, "night", "--on", TODAY)
    assert reply.exit_code == 0
    assert "no live watches" in reply.output


def test_a_drop_that_holds_raises_a_recommendation(runner, env):
    a_desk(env)
    reply = run(runner, "night", "--on", TODAY)
    assert reply.exit_code == 0
    assert "raised 1 worth 90.00 EUR" in reply.output
    assert "wrote 1 recommendations" in reply.output


def test_the_recommendation_reaches_the_database(runner, env):
    a_desk(env)
    run(runner, "night", "--on", TODAY)
    assert in_state(env) == [(Decimal("90.00"), "EUR")]


def test_a_dry_run_works_it_out_and_writes_nothing(runner, env):
    a_desk(env)
    reply = run(runner, "night", "--on", TODAY, "--dry-run")
    assert "raised 1 worth 90.00 EUR" in reply.output
    assert "dry run, nothing written" in reply.output
    assert in_state(env) == []


def test_a_ticket_with_no_rule_filed_is_left_alone_and_counted(runner, env):
    # Guessing that a change is permitted is how a desk quotes a reissue the
    # carrier refuses at the counter.
    a_desk(env, with_rule=False)
    reply = run(runner, "night", "--on", TODAY)
    assert "1 watches have no rule filed and were left alone" in reply.output
    assert in_state(env) == []


def test_a_fare_basis_that_has_left_the_tariff_finds_no_rule(runner, env):
    a_desk(env, with_fare=False)
    reply = run(runner, "night", "--on", TODAY)
    assert "have no rule filed" in reply.output


def test_a_fare_that_cannot_be_changed_retires_the_watch(runner, env):
    a_desk(env)
    with database(env).unit() as work:
        file_rule(work, permitted=False)
        work.commit()
    reply = run(runner, "night", "--on", TODAY)
    assert "retired FRA-NYC-2025-08-24-Y" in reply.output

    with database(env).unit() as work:
        rows = work.watches.live(date(2025, 7, 28))
    assert rows == []


def test_a_fare_that_reprices_historically_retires_the_watch_too(runner, env):
    a_desk(env)
    with database(env).unit() as work:
        file_rule(work, basis=RepricingBasis.HISTORICAL)
        work.commit()
    reply = run(runner, "night", "--on", TODAY)
    assert "reprices off the original ticketing date" in reply.output


def test_a_drop_too_small_to_be_worth_it_leaves_the_watch_alone(runner, env):
    a_desk(env, drops=("820.00", "820.00"))
    reply = run(runner, "night", "--on", TODAY)
    assert "not_worth_it" in reply.output
    assert in_state(env) == []

    with database(env).unit() as work:
        assert len(work.watches.live(date(2025, 7, 28))) == 1


def test_a_second_night_does_not_raise_the_same_number_twice(runner, env):
    a_desk(env)
    run(runner, "night", "--on", TODAY)
    reply = run(runner, "night", "--on", "2025-07-29")
    assert "wrote 0 recommendations" in reply.output
    assert len(in_state(env)) == 1


def test_a_better_fare_on_a_second_night_replaces_the_first(runner, env):
    a_desk(env)
    run(runner, "night", "--on", TODAY)
    with database(env).unit() as work:
        observe(work, "600.00", datetime(2025, 7, 29, 6, 0))
        observe(work, "600.00", datetime(2025, 7, 30, 6, 0))
        work.commit()

    reply = run(runner, "night", "--on", "2025-07-30")
    assert "wrote 1 recommendations" in reply.output
    assert in_state(env) == [(Decimal("190.00"), "EUR")]
    assert in_state(env, "superseded") == [(Decimal("90.00"), "EUR")]


def test_a_night_report_reads_back_with_the_counts(runner, env):
    a_desk(env)
    reply = run(runner, "night", "--on", TODAY)
    assert "2025-07-28: looked at 1" in reply.output
    assert "retired 0, expired 0" in reply.output


def test_a_quiet_night_says_nothing_when_there_is_nothing_to_say(runner, env):
    a_desk(env, drops=("820.00", "820.00"))
    reply = run(runner, "--quiet", "night", "--on", TODAY)
    assert reply.output.strip() == ""


def test_two_watches_are_both_looked_at(runner, env):
    a_desk(env)
    with database(env).unit() as work:
        other = make_ticket(work, number="220-9999999999")
        make_watch(work, other, series="FRA-NYC-2025-08-24-C")
        work.commit()
    reply = run(runner, "night", "--on", TODAY)
    assert "looked at 2" in reply.output
