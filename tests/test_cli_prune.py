"""The retention command, which is the one with an expensive way to be wrong.

Everything else here can be run again. This deletes, so the tests that matter
are the ones about what it does not touch and about the dry run reporting the
same numbers the real run acts on.
"""

from __future__ import annotations

import gzip
from datetime import date, datetime
from decimal import Decimal

import pytest
from click.testing import CliRunner

from gussetline.cli.main import cli
from gussetline.collect.archive import PARTIAL, Archive
from gussetline.config import Settings
from gussetline.persistence.models import ObservationRow, RunRow
from gussetline.persistence.unitofwork import Database

TODAY = "2025-11-03"
OLD = datetime(2024, 1, 15, 6, 0)
RECENT = datetime(2025, 11, 1, 6, 0)
GONE = date(2024, 2, 1)
AHEAD = date(2026, 3, 1)
URL = "/search"


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def raw(tmp_path):
    return tmp_path / "raw"


@pytest.fixture
def env(monkeypatch, tmp_path, raw):
    url = f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(raw))
    Database(Settings(database_url=url)).create_schema()
    return url


def database(url: str) -> Database:
    return Database(Settings(database_url=url))


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def an_observation(work, seen: datetime, departure: date, market="FRA-NYC") -> None:
    """One reading. What decides whether it is pruned is the departure.

    Not when it was written. A row written in March about a December
    departure is still worth reading in November.
    """
    work.session.add(
        ObservationRow(
            series_key=f"{market}-{departure.isoformat()}-Y",
            source="gds",
            fingerprint=f"{seen:%Y%m%d%H%M}{market}".ljust(32, "0")[:32],
            origin="FRA",
            destination="JFK",
            departure=departure,
            cabin="Y",
            base_amount=Decimal("700.00"),
            base_currency="EUR",
            tax_amount=Decimal("0.00"),
            surcharge_amount=Decimal("0.00"),
            total_amount=Decimal("700.00"),
            first_seen=seen,
            last_seen=seen,
        )
    )


def a_run(work, started: datetime) -> None:
    work.session.add(RunRow(started_at=started, finished_at=started, tasks=1, calls=1))


def test_pruning_an_empty_desk_says_nothing_went(runner, env):
    reply = run(runner, "prune", "--on", TODAY)
    assert reply.exit_code == 0
    assert "0 observations, 0 runs" in reply.output


def test_a_dry_run_says_what_it_would_do_and_does_not_do_it(runner, env):
    with database(env).unit() as work:
        an_observation(work, OLD, GONE)
        work.commit()

    reply = run(runner, "prune", "--on", TODAY, "--observation-days", "30")
    assert "1 observations" in reply.output
    assert "nothing was deleted" in reply.output

    with database(env).unit() as work:
        assert work.observations.count() == 1


def test_applying_it_actually_deletes(runner, env):
    with database(env).unit() as work:
        an_observation(work, OLD, GONE)
        work.commit()

    reply = run(runner, "prune", "--on", TODAY, "--observation-days", "30", "--apply")
    assert "nothing was deleted" not in reply.output

    with database(env).unit() as work:
        assert work.observations.count() == 0


def test_a_reading_for_a_departure_still_ahead_is_left_alone(runner, env):
    # Keyed on the departure and not on when the row was written. Somebody
    # can still be asked what that fare was.
    with database(env).unit() as work:
        an_observation(work, OLD, AHEAD)
        work.commit()

    run(runner, "prune", "--on", TODAY, "--observation-days", "30", "--apply")
    with database(env).unit() as work:
        assert work.observations.count() == 1


def test_the_dry_run_counts_what_the_real_one_deletes(runner, env):
    # Two code paths would mean the dry run reports on something the real one
    # does not do, which is the one thing a dry run must never do.
    with database(env).unit() as work:
        an_observation(work, OLD, GONE)
        an_observation(work, OLD, GONE, market="FRA-LON")
        a_run(work, OLD)
        work.commit()

    window = ("--observation-days", "30", "--run-days", "30", "--archive-days", "30")
    dry = run(runner, "prune", "--on", TODAY, *window)
    wet = run(runner, "prune", "--on", TODAY, *window, "--apply")
    assert "2 observations, 1 runs" in dry.output
    assert "2 observations, 1 runs" in wet.output


def test_keeping_runs_for_less_time_than_the_payloads_they_explain_is_refused(runner, env):
    # A result row with no run to hang it off is unreadable, and a payload
    # whose run has gone is a sighting nobody can date.
    reply = run(runner, "prune", "--on", TODAY, "--run-days", "30")
    assert reply.exit_code != 0
    assert "at least as long" in str(reply.exception)


def test_the_policy_it_is_using_is_printed_before_it_acts(runner, env):
    assert "keeping" in run(runner, "prune", "--on", TODAY).output


def test_it_says_how_many_payloads_are_still_referenced(runner, env, raw):
    archive = Archive(raw)
    archive.put(b"a payload", "gds", URL, RECENT)
    reply = run(runner, "prune", "--on", TODAY, "--archive-days", "30")
    assert "1 payloads still referenced" in reply.output


def test_an_old_payload_goes_when_it_is_applied(runner, env, raw):
    archive = Archive(raw)
    sighting = archive.put(b"a payload", "gds", URL, OLD)
    run(runner, "prune", "--on", TODAY, "--archive-days", "30", "--apply")
    assert not archive.holds(sighting.digest)


def test_scrubbing_is_off_unless_it_is_asked_for(runner, env, raw):
    # It reads and decompresses the whole store, which on a year of
    # collecting is minutes rather than seconds.
    archive = Archive(raw)
    archive.put(b"a payload", "gds", URL, RECENT)
    assert "read back" not in run(runner, "prune", "--on", TODAY).output


def test_scrubbing_dry_says_how_many_payloads_do_not_read_back(runner, env, raw):
    archive = Archive(raw)
    bad = archive.put(b"a payload", "gds", URL, RECENT)
    archive._path_for(bad.digest).write_bytes(b"not gzip at all")

    reply = run(runner, "prune", "--on", TODAY, "--scrub")
    assert "1 payloads do not read back" in reply.output
    assert bad.digest in reply.output
    assert archive.holds(bad.digest)


def test_scrubbing_dry_on_a_healthy_store_says_none(runner, env, raw):
    Archive(raw).put(b"a payload", "gds", URL, RECENT)
    assert "0 payloads do not read back" in run(runner, "prune", "--on", TODAY, "--scrub").output


def test_scrubbing_applied_removes_what_cannot_be_read(runner, env, raw):
    archive = Archive(raw)
    bad = archive.put(b"a payload", "gds", URL, RECENT)
    archive._path_for(bad.digest).write_bytes(b"not gzip at all")

    reply = run(runner, "prune", "--on", TODAY, "--scrub", "--apply")
    assert "removed 1 unreadable payloads" in reply.output
    assert not archive.holds(bad.digest)


def test_scrubbing_applied_removes_an_interrupted_write(runner, env, raw):
    archive = Archive(raw)
    sighting = archive.put(b"a payload", "gds", URL, RECENT)
    blob = archive._path_for(sighting.digest)
    blob.with_name(blob.name + PARTIAL).write_bytes(b"half a payload")

    reply = run(runner, "prune", "--on", TODAY, "--scrub", "--apply")
    assert "1 unfinished writes" in reply.output
    assert archive.get(sighting.digest) == b"a payload"


def test_a_good_payload_survives_a_scrub_that_takes_out_a_bad_one(runner, env, raw):
    archive = Archive(raw)
    good = archive.put(b"a payload", "gds", URL, RECENT)
    bad = archive.put(b"another payload", "ndc", URL, RECENT)
    archive._path_for(bad.digest).write_bytes(gzip.compress(b"something else"))

    run(runner, "prune", "--on", TODAY, "--scrub", "--apply")
    assert archive.get(good.digest) == b"a payload"
    assert not archive.holds(bad.digest)
