from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from gussetline.cli.main import OPERATOR_CAN_FIX, cli
from gussetline.collect.fixedwidth import write_record
from gussetline.load.fares import FARE_RECORD, HEADER_RECORD, TRAILER_RECORD
from gussetline.load.rules import ADVANCE, MINIMUM, RULE_HEADER

FILED = date(2025, 3, 1)


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def on_disk(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"


@pytest.fixture
def desk(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    return on_disk


def fare_line(basis="KLWAP7RT", action="A", cabin="Y", amount="420.00") -> str:
    return write_record(
        FARE_RECORD,
        {
            "carrier": "LH",
            "origin": "FRA",
            "destination": "JFK",
            "fare_basis": basis,
            "currency": "EUR",
            "amount": Decimal(amount),
            "owrt": "2",
            "global_indicator": "AT",
            "rule_number": "0100",
            "routing_number": "0011",
            "tariff_number": "001",
            "footnote": None,
            "cabin": cabin,
            "fare_type": "P",
            "effective": date(2025, 1, 1),
            "discontinue": date(2025, 12, 31),
            "first_travel": date(2025, 1, 1),
            "last_travel": date(2025, 12, 31),
            "action": action,
        },
    )


def fare_file(tmp_path, *lines, name="lh.dat", count=None) -> str:
    rows = [write_record(HEADER_RECORD, {"carrier": "LH", "filed_on": FILED, "sequence": 41})]
    rows.extend(lines or [fare_line()])
    if count is not None:
        rows.append(write_record(TRAILER_RECORD, {"records": count}))
    path = tmp_path / name
    path.write_text("\n".join(rows), encoding="ascii")
    return str(path)


def rule_file(tmp_path, *lines, name="lh-rules.dat") -> str:
    rows = list(lines) or [
        write_record(
            RULE_HEADER, {"carrier": "LH", "number": "0100", "tariff": "001", "action": "A"}
        ),
        write_record(
            ADVANCE, {"reservation_days": 7, "ticketing_after": None, "ticketing_before": None}
        ),
    ]
    path = tmp_path / name
    path.write_text("\n".join(rows), encoding="ascii")
    return str(path)


def test_the_load_group_shows_up_in_the_help(runner):
    reply = runner.invoke(cli, ["--help"])
    assert "load" in reply.output


def test_the_load_group_lists_its_two_commands(runner):
    reply = runner.invoke(cli, ["load", "--help"])
    assert "fares" in reply.output
    assert "rules" in reply.output


def test_a_clean_fare_file_loads(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(), fare_line(basis="MLWAP7RT"))
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path])
    assert reply.exit_code == 0
    assert "loaded 2 fares" in reply.output


def test_the_fares_reach_the_database(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(), fare_line(basis="MLWAP7RT"))
    runner.invoke(cli, ["--database", desk, "load", "fares", path])
    reply = runner.invoke(cli, ["--database", desk, "check"])
    assert "fares         2" in reply.output


def test_the_same_bytes_are_refused_the_second_time(runner, desk, tmp_path):
    # Transfers retry. Loading the file twice doubles a market's fares and
    # the duplicate then wins a selection it should never have been in.
    path = fare_file(tmp_path, fare_line())
    assert runner.invoke(cli, ["--database", desk, "load", "fares", path]).exit_code == 0
    again = runner.invoke(cli, ["--database", desk, "load", "fares", path])
    assert again.exit_code != 0
    assert "already loaded" in again.output


def test_the_same_bytes_can_be_forced_through_when_somebody_means_it(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line())
    runner.invoke(cli, ["--database", desk, "load", "fares", path])
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path, "--again"])
    assert reply.exit_code == 0


def test_a_different_file_with_the_same_name_is_not_the_same_bytes(runner, desk, tmp_path):
    first = fare_file(tmp_path, fare_line(), name="lh.dat")
    runner.invoke(cli, ["--database", desk, "load", "fares", first])
    second = fare_file(tmp_path, fare_line(basis="MLWAP7RT"), name="lh.dat")
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", second])
    assert reply.exit_code == 0


def test_a_dry_run_says_what_would_happen_and_writes_nothing(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line())
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path, "--dry-run"])
    assert reply.exit_code == 0
    assert "would be 1 added" in reply.output
    assert "fares         0" in runner.invoke(cli, ["--database", desk, "check"]).output


def test_a_dry_run_does_not_claim_the_digest(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line())
    runner.invoke(cli, ["--database", desk, "load", "fares", path, "--dry-run"])
    assert runner.invoke(cli, ["--database", desk, "load", "fares", path]).exit_code == 0


def test_a_file_with_a_bad_record_loads_the_rest_and_asks_for_a_look(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(), fare_line(basis="MLWAP7RT", cabin="Q"))
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path])
    assert reply.exit_code == OPERATOR_CAN_FIX
    assert "loaded 1 fares" in reply.output


def test_the_bad_records_are_printed_with_their_line_numbers(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(cabin="Q"))
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path])
    assert "line 2" in reply.output


def test_a_trailer_that_disagrees_is_said_out_loud(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(), count=9)
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", path])
    assert "the trailer says 9 records" in reply.output


def test_a_quiet_run_still_says_what_went_wrong(runner, desk, tmp_path):
    path = fare_file(tmp_path, fare_line(cabin="Q"))
    reply = runner.invoke(cli, ["--database", desk, "--quiet", "load", "fares", path])
    assert "line 2" in reply.output
    assert "loaded" not in reply.output


def test_a_file_that_is_not_there_is_a_usage_error(runner, desk, tmp_path):
    reply = runner.invoke(cli, ["--database", desk, "load", "fares", str(tmp_path / "nope.dat")])
    assert reply.exit_code == 2


def test_a_rule_file_reads_and_counts_its_categories(runner, desk, tmp_path):
    path = rule_file(tmp_path)
    reply = runner.invoke(cli, ["--database", desk, "load", "rules", path])
    assert reply.exit_code == 0
    assert "1 rules, 0 deleted, 0 problems" in reply.output
    assert "category  5  1" in reply.output


def test_a_rule_file_with_orphans_says_so(runner, desk, tmp_path):
    path = rule_file(
        tmp_path,
        write_record(MINIMUM, {"days": 3, "weekday": None}),
        write_record(
            RULE_HEADER, {"carrier": "LH", "number": "0100", "tariff": "001", "action": "A"}
        ),
    )
    reply = runner.invoke(cli, ["--database", desk, "load", "rules", path])
    assert "1 category records had no rule above them" in reply.output
    assert reply.exit_code == OPERATOR_CAN_FIX


def test_a_rule_dry_run_says_would_be(runner, desk, tmp_path):
    path = rule_file(tmp_path)
    reply = runner.invoke(cli, ["--database", desk, "load", "rules", path, "--dry-run"])
    assert "would be: 1 rules loaded" in reply.output
