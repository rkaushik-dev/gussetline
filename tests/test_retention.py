from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner
from sqlalchemy import select

from gussetline.cli.main import cli
from gussetline.collect.archive import Archive
from gussetline.collect.registry import Outcome
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Task
from gussetline.config import Settings
from gussetline.errors import ValidationError
from gussetline.persistence.models import (
    CouponRow,
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import Database
from gussetline.retention import Policy, default_policy, keepable

TODAY = date(2026, 1, 15)


def test_the_shipped_policy_keeps_each_kind_for_a_different_while():
    policy = default_policy()
    assert policy.observation_days > policy.run_days > policy.archive_days


def test_a_retention_of_nothing_is_refused():
    with pytest.raises(ValidationError, match="at least a day"):
        Policy(observation_days=0)


def test_keeping_payloads_longer_than_the_runs_that_explain_them_is_refused():
    # Disk with nothing to say about it.
    with pytest.raises(ValidationError, match="at least as long"):
        Policy(run_days=30, archive_days=90)


def test_a_policy_reads_back_in_one_line():
    assert str(Policy(400, 120, 90)) == "observations 400d, runs 120d, payloads 90d"


def test_the_cutoffs_are_worked_out_from_today():
    policy = Policy(400, 120, 90)
    assert policy.observations_before(TODAY) == date(2024, 12, 11)
    assert policy.runs_before(TODAY) == datetime(2025, 9, 17, 0, 0)
    assert policy.archive_before(TODAY) == datetime(2025, 10, 17, 0, 0)


def test_a_manifest_row_older_than_the_cutoff_does_not_survive():
    rows = [
        {"digest": "a" * 32, "seen_at": "2025-01-01T06:00:00"},
        {"digest": "b" * 32, "seen_at": "2026-01-01T06:00:00"},
    ]
    kept, named = keepable(rows, datetime(2025, 6, 1))
    assert len(kept) == 1
    assert named == {"b" * 32}


def test_a_payload_a_surviving_row_still_names_is_kept():
    # The same payload seen on thirty mornings is one blob and thirty rows.
    rows = [
        {"digest": "a" * 32, "seen_at": "2025-01-01T06:00:00"},
        {"digest": "a" * 32, "seen_at": "2026-01-01T06:00:00"},
    ]
    kept, named = keepable(rows, datetime(2025, 6, 1))
    assert len(kept) == 1
    assert named == {"a" * 32}


def test_a_row_with_no_stamp_on_it_is_kept_rather_than_guessed_at():
    kept, _ = keepable([{"digest": "a" * 32, "seen_at": ""}], datetime(2025, 6, 1))
    assert len(kept) == 1


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def home(tmp_path):
    return tmp_path


@pytest.fixture
def env(monkeypatch, home):
    url = f"sqlite+pysqlite:///{home / 'desk.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(home / "raw"))
    Database(Settings(database_url=url)).create_schema()
    return url


def database(url: str) -> Database:
    return Database(Settings(database_url=url))


def an_observation(work, departure: date, digest="a" * 32) -> None:
    work.session.add(
        ObservationRow(
            series_key=f"FRA-NYC-{departure.isoformat()}-Y",
            source="gds",
            fingerprint=digest,
            origin="FRA",
            destination="JFK",
            departure=departure,
            cabin="Y",
            base_amount=Decimal("700.00"),
            base_currency="EUR",
            tax_amount=Decimal("0.00"),
            surcharge_amount=Decimal("0.00"),
            total_amount=Decimal("700.00"),
            first_seen=datetime(2024, 1, 1, 6, 0),
            last_seen=datetime(2024, 1, 1, 6, 0),
        )
    )


def a_run(work, started: datetime) -> None:
    work.runs.record(
        RunReport(
            started=started,
            results=[
                Result(
                    task=Task(
                        supplier="gds",
                        market=Market("FRA", "JFK"),
                        departure=date(2025, 1, 1),
                        days_out=1,
                    ),
                    outcome=Outcome.OK,
                    attempts=1,
                )
            ],
        )
    )


def a_recommendation(work, raised: date) -> None:
    ticket = TicketRow(
        number="220-1234567890",
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        origin="FRA",
        destination="JFK",
        departure=date(2024, 6, 1),
        issued_on=date(2024, 2, 14),
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
            departure=datetime(2024, 6, 1, 10, 25),
            booking_class="K",
            fare_basis="KLWAP7RT",
        )
    )
    watch = WatchRow(
        series_key="FRA-NYC-2024-06-01-Y",
        origin="FRA",
        destination="JFK",
        departure=date(2024, 6, 1),
        threshold_amount=Decimal("50.00"),
        threshold_currency="EUR",
    )
    ticket.watches.append(watch)
    work.session.add(ticket)
    work.session.flush()
    work.session.add(
        RecommendationRow(
            watch_id=watch.id,
            raised_on=raised,
            state="taken",
            paid_amount=Decimal("840.00"),
            new_fare_amount=Decimal("700.00"),
            penalty_amount=Decimal("50.00"),
            benefit_amount=Decimal("90.00"),
            currency="EUR",
            residual="refundable",
        )
    )


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_a_dry_run_says_what_would_go_and_deletes_nothing(runner, env):
    with database(env).unit() as work:
        an_observation(work, date(2024, 6, 1))
        work.commit()

    reply = run(runner, "prune", "--on", TODAY.isoformat())
    assert reply.exit_code == 0
    assert "1 observations" in reply.output
    assert "nothing was deleted" in reply.output

    with database(env).unit() as work:
        assert work.observations.count() == 1


def test_applying_it_actually_deletes(runner, env):
    with database(env).unit() as work:
        an_observation(work, date(2024, 6, 1))
        work.commit()

    reply = run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert "nothing was deleted" not in reply.output

    with database(env).unit() as work:
        assert work.observations.count() == 0


def test_a_reading_for_a_departure_still_ahead_is_kept(runner, env):
    # Keyed on the departure and not on when the row was written.
    with database(env).unit() as work:
        an_observation(work, date(2026, 6, 1))
        work.commit()

    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    with database(env).unit() as work:
        assert work.observations.count() == 1


def test_a_recommendation_is_never_touched_however_old(runner, env):
    # The savings report reads them, and a report that changes when old rows
    # are deleted cannot be put in front of anybody.
    with database(env).unit() as work:
        a_recommendation(work, date(2023, 1, 1))
        work.commit()

    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    with database(env).unit() as work:
        assert len(work.session.scalars(select(RecommendationRow)).all()) == 1


def test_an_old_run_goes_and_a_recent_one_stays(runner, env):
    with database(env).unit() as work:
        a_run(work, datetime(2024, 1, 1, 6, 0))
        a_run(work, datetime(2026, 1, 1, 6, 0))
        work.commit()

    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    with database(env).unit() as work:
        assert work.runs.count() == 1


def test_an_old_payload_goes(runner, env, home):
    archive = Archive(home / "raw")
    archive.put(b"old", "gds", "/x", datetime(2024, 1, 1, 6, 0))
    assert archive.stored_count() == 1

    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert Archive(home / "raw").stored_count() == 0


def test_a_payload_a_recent_sighting_still_names_is_kept(runner, env, home):
    archive = Archive(home / "raw")
    archive.put(b"same", "gds", "/x", datetime(2024, 1, 1, 6, 0))
    archive.put(b"same", "gds", "/x", datetime(2026, 1, 1, 6, 0))

    reply = run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert "1 payloads still referenced" in reply.output
    assert Archive(home / "raw").stored_count() == 1


def test_the_old_sighting_leaves_the_manifest_even_when_the_payload_stays(runner, env, home):
    archive = Archive(home / "raw")
    archive.put(b"same", "gds", "/x", datetime(2024, 1, 1, 6, 0))
    archive.put(b"same", "gds", "/x", datetime(2026, 1, 1, 6, 0))

    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert len(Archive(home / "raw").manifest()) == 1


def test_a_recent_payload_stays(runner, env, home):
    Archive(home / "raw").put(b"new", "gds", "/x", datetime(2026, 1, 1, 6, 0))
    run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert Archive(home / "raw").stored_count() == 1


def test_the_retentions_can_be_named(runner, env):
    reply = run(
        runner,
        "prune",
        "--on",
        TODAY.isoformat(),
        "--observation-days",
        "10",
        "--run-days",
        "5",
        "--archive-days",
        "5",
    )
    assert "observations 10d, runs 5d, payloads 5d" in reply.output


def test_a_retention_that_makes_no_sense_is_refused(runner, env):
    reply = run(runner, "prune", "--on", TODAY.isoformat(), "--run-days", "5")
    assert reply.exit_code != 0
    assert isinstance(reply.exception, ValidationError)


def test_an_empty_desk_prunes_nothing_and_says_so(runner, env):
    reply = run(runner, "prune", "--on", TODAY.isoformat(), "--apply")
    assert "0 observations, 0 runs, 0 payloads" in reply.output
