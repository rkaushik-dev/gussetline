from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner

from gussetline.cli.main import cli
from gussetline.collect.registry import Outcome
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Task
from gussetline.config import Settings
from gussetline.persistence.models import CouponRow, RecommendationRow, TicketRow, WatchRow
from gussetline.persistence.unitofwork import Database

TODAY = "2025-07-30"
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


def a_watch(work, series=SERIES, departure=DEPARTURE, last=None, active=True) -> WatchRow:
    ticket = TicketRow(
        number=f"220-{series[-8:]}",
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        origin="FRA",
        destination="JFK",
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
            destination="JFK",
            departure=datetime(departure.year, departure.month, departure.day, 10, 25),
            booking_class="K",
            fare_basis="KLWAP7RT",
        )
    )
    watch = WatchRow(
        series_key=series,
        origin="FRA",
        destination="JFK",
        departure=departure,
        threshold_amount=Decimal("50.00"),
        threshold_currency="EUR",
        last_checked=last,
        active=active,
    )
    ticket.watches.append(watch)
    work.session.add(ticket)
    work.session.flush()
    return watch


def a_recommendation(work, watch: WatchRow, raised: date, state="open", benefit="90.00") -> None:
    work.session.add(
        RecommendationRow(
            watch_id=watch.id,
            raised_on=raised,
            state=state,
            paid_amount=Decimal("840.00"),
            new_fare_amount=Decimal("700.00"),
            penalty_amount=Decimal("50.00"),
            benefit_amount=Decimal(benefit),
            currency="EUR",
            residual="refundable",
            decided_by="anna" if state in ("taken", "killed") else "",
            decided_on=raised if state != "open" else None,
        )
    )


def a_run(work, started: datetime, *outcomes: Outcome, supplier="gds") -> None:
    results = [
        Result(
            task=Task(
                supplier=supplier,
                market=Market("FRA", "JFK"),
                departure=DEPARTURE,
                days_out=30,
            ),
            outcome=outcome,
            attempts=0 if outcome is Outcome.BLOCKED else 1,
            offers=6 if outcome is Outcome.OK else 0,
        )
        for outcome in outcomes
    ]
    work.runs.record(RunReport(started=started, results=results))


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_the_report_group_lists_its_three_commands(runner, env):
    reply = run(runner, "report", "--help")
    assert "savings" in reply.output
    assert "reliability" in reply.output
    assert "coverage" in reply.output


def test_savings_on_an_empty_desk_is_all_zeroes(runner, env):
    reply = run(runner, "report", "savings", "--until", TODAY)
    assert reply.exit_code == 0
    assert "found      0 EUR" in reply.output
    assert "raised     0" in reply.output


def test_savings_counts_what_was_found_taken_and_missed(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 7, 20), "taken", "90.00")
        a_recommendation(work, watch, date(2025, 7, 21), "expired", "70.00")
        a_recommendation(work, watch, date(2025, 7, 22), "killed", "40.00")
        work.commit()

    reply = run(runner, "report", "savings", "--until", TODAY)
    assert "found      200.00 EUR" in reply.output
    assert "taken      90.00 EUR" in reply.output
    assert "missed     70.00 EUR" in reply.output


def test_savings_is_keyed_on_the_day_it_was_raised(runner, env):
    # A recommendation belongs to the month the desk found it in. Keying on
    # the settlement moves rows between months when an agent clears a backlog.
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 6, 1), "taken")
        a_recommendation(work, watch, date(2025, 7, 20), "taken")
        work.commit()

    reply = run(runner, "report", "savings", "--since", "2025-07-01", "--until", TODAY)
    assert "raised    1" in reply.output


def test_savings_defaults_to_the_last_month(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date.today(), "taken")
        work.commit()

    reply = run(runner, "report", "savings")
    assert "raised    1" in reply.output


def test_savings_groups_by_month_and_by_agent(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 7, 20), "taken")
        work.commit()

    reply = run(runner, "report", "savings", "--until", TODAY)
    assert "by month" in reply.output
    assert "2025-07" in reply.output
    assert "anna" in reply.output


def test_reliability_on_an_empty_desk_reads_as_nothing_ran(runner, env):
    reply = run(runner, "report", "reliability")
    assert reply.exit_code == 0
    assert "nothing ran" in reply.output


def test_reliability_reads_the_stored_runs(runner, env):
    with database(env).unit() as work:
        a_run(work, datetime.now(), Outcome.OK, Outcome.OK, Outcome.OK)
        work.commit()

    reply = run(runner, "report", "reliability")
    assert "gds        sound" in reply.output
    assert "planned 3 called 3 answered 3" in reply.output


def test_a_supplier_that_needs_a_look_makes_the_report_exit_one(runner, env):
    with database(env).unit() as work:
        a_run(work, datetime.now(), Outcome.OK, Outcome.BLOCKED)
        work.commit()

    reply = run(runner, "report", "reliability")
    assert reply.exit_code == 1
    assert "unfit" in reply.output


def test_reliability_can_be_asked_for_a_number_of_runs_instead(runner, env):
    with database(env).unit() as work:
        for day in range(1, 6):
            a_run(work, datetime(2025, 7, day, 6, 0), Outcome.OK)
        work.commit()

    reply = run(runner, "report", "reliability", "--runs", "2")
    assert "reliability over 2 runs" in reply.output


def test_coverage_on_an_empty_desk_has_nothing_to_cover(runner, env):
    reply = run(runner, "report", "coverage", "--on", TODAY)
    assert reply.exit_code == 0
    assert "followed  0" in reply.output


def test_a_watch_nobody_polled_is_a_dark_gap(runner, env):
    with database(env).unit() as work:
        a_watch(work)
        work.commit()

    reply = run(runner, "report", "coverage", "--on", TODAY)
    assert reply.exit_code == 1
    assert "dark      1" in reply.output
    assert "gaps, worst first" in reply.output


def test_a_retired_watch_is_not_a_gap(runner, env):
    # Counting one makes the coverage figure fall every time the desk
    # correctly stops watching something.
    with database(env).unit() as work:
        a_watch(work, active=False)
        work.commit()

    reply = run(runner, "report", "coverage", "--on", TODAY)
    assert reply.exit_code == 0
    assert "followed  0" in reply.output


def test_a_polled_watch_is_covered(runner, env):
    with database(env).unit() as work:
        a_watch(work, last=datetime(2025, 7, 29, 6, 0))
        a_run(work, datetime(2025, 7, 29, 6, 0), Outcome.OK)
        work.commit()

    reply = run(runner, "report", "coverage", "--on", TODAY)
    assert "priced    1" in reply.output
    assert reply.exit_code == 0


def test_the_number_of_gaps_listed_can_be_capped(runner, env):
    with database(env).unit() as work:
        for day in range(1, 6):
            a_watch(work, series=f"FRA-NYC-2025-09-0{day}-Y", departure=date(2025, 9, day))
        work.commit()

    reply = run(runner, "report", "coverage", "--on", TODAY, "--show", "2")
    assert "showing 2 of 5" in reply.output


def test_none_of_the_reports_writes_anything(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 7, 20), "open")
        a_run(work, datetime(2025, 7, 29, 6, 0), Outcome.OK)
        work.commit()

    run(runner, "report", "savings", "--until", TODAY)
    run(runner, "report", "reliability")
    run(runner, "report", "coverage", "--on", TODAY)

    with database(env).unit() as work:
        assert work.runs.count() == 1
        from sqlalchemy import select

        rows = work.session.scalars(select(RecommendationRow)).all()
        assert [row.state for row in rows] == ["open"]


def test_the_queue_command_shows_up_in_the_group(runner, env):
    assert "queue" in run(runner, "report", "--help").output


def test_an_empty_desk_has_nothing_open(runner, env):
    reply = run(runner, "report", "queue", "--on", TODAY)
    assert reply.exit_code == 0
    assert "nothing open" in reply.output


def test_the_queue_lists_the_open_recommendations_with_what_they_are_worth(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 7, 29), "open", "90.00")
        work.commit()

    reply = run(runner, "report", "queue", "--on", TODAY)
    assert "1 open" in reply.output
    assert "worth 90.00 EUR" in reply.output


def test_the_queue_leaves_out_what_is_already_settled(runner, env):
    with database(env).unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, date(2025, 7, 29), "taken")
        work.commit()

    assert "nothing open" in run(runner, "report", "queue", "--on", TODAY).output


def test_a_smaller_one_about_to_expire_comes_first(runner, env):
    with database(env).unit() as work:
        near = a_watch(work, series="FRA-NYC-2025-08-02-Y", departure=date(2025, 8, 2))
        far = a_watch(work, series="FRA-NYC-2026-03-01-Y", departure=date(2026, 3, 1))
        a_recommendation(work, near, date(2025, 7, 28), "open", "90.00")
        a_recommendation(work, far, date(2025, 7, 30), "open", "200.00")
        work.commit()

    out = run(runner, "report", "queue", "--on", TODAY).output
    assert out.index("90.00 EUR") < out.index("200.00 EUR")


def test_the_urgent_flag_narrows_the_list(runner, env):
    with database(env).unit() as work:
        far = a_watch(work, series="FRA-NYC-2026-03-01-Y", departure=date(2026, 3, 1))
        a_recommendation(work, far, date(2025, 7, 30), "open", "200.00")
        work.commit()

    assert "nothing open" in run(runner, "report", "queue", "--on", TODAY, "--urgent").output
