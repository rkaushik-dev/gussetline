from __future__ import annotations

from datetime import date

import pytest
from click.testing import CliRunner

from gussetline.api.routers.health import checks as fitness
from gussetline.cli.main import (
    OK,
    OPERATOR_CAN_FIX,
    SOMEBODY_SHOULD_LOOK,
    cli,
    exit_code_for,
)
from gussetline.config import Settings
from gussetline.errors import (
    ConfigError,
    ConstructionError,
    NoFareFound,
    NotFoundError,
    SupplierBlocked,
    SupplierUnavailable,
    ValidationError,
)
from gussetline.persistence.unitofwork import Database


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def on_disk(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"


def test_the_three_codes_are_distinct():
    assert len({OK, OPERATOR_CAN_FIX, SOMEBODY_SHOULD_LOOK}) == 3


def test_something_the_operator_can_fix_is_a_one():
    assert exit_code_for(NotFoundError("no such ticket")) == OPERATOR_CAN_FIX
    assert exit_code_for(ValidationError("not a market")) == OPERATOR_CAN_FIX
    assert exit_code_for(NoFareFound("nothing filed")) == OPERATOR_CAN_FIX
    assert exit_code_for(ConfigError("not a number")) == OPERATOR_CAN_FIX


def test_something_they_cannot_is_a_two():
    assert exit_code_for(SupplierUnavailable("timeout")) == SOMEBODY_SHOULD_LOOK
    assert exit_code_for(SupplierBlocked("403")) == SOMEBODY_SHOULD_LOOK
    assert exit_code_for(ConstructionError("no through fare")) == SOMEBODY_SHOULD_LOOK
    assert exit_code_for(OSError("connection reset")) == SOMEBODY_SHOULD_LOOK


def test_the_help_lists_the_commands(runner):
    reply = runner.invoke(cli, ["--help"])
    assert reply.exit_code == 0
    assert "settings" in reply.output
    assert "create-schema" in reply.output
    assert "check" in reply.output


def test_the_short_help_flag_works_too(runner):
    assert runner.invoke(cli, ["-h"]).exit_code == 0


def test_settings_prints_what_the_run_would_use(runner, on_disk):
    reply = runner.invoke(cli, ["--database", on_disk, "settings"])
    assert reply.exit_code == 0
    assert "selling in EUR" in reply.output
    assert "quota      1000" in reply.output
    assert "dry run    no" in reply.output


def test_the_database_override_reaches_the_settings(runner, on_disk):
    reply = runner.invoke(cli, ["--database", on_disk, "settings"])
    assert "desk.sqlite3" in reply.output


def test_the_environment_is_read_when_nothing_overrides_it(runner, monkeypatch):
    monkeypatch.setenv("GUSSETLINE_SELLING_CURRENCY", "GBP")
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", "sqlite+pysqlite:///:memory:")
    reply = runner.invoke(cli, ["settings"])
    assert "selling in GBP" in reply.output


def test_a_setting_that_is_not_a_number_stops_the_run(runner, monkeypatch):
    monkeypatch.setenv("GUSSETLINE_DAILY_QUOTA", "lots")
    reply = runner.invoke(cli, ["settings"], standalone_mode=False)
    assert isinstance(reply.exception, ConfigError)
    assert exit_code_for(reply.exception) == OPERATOR_CAN_FIX


def test_create_schema_then_check_reads_an_empty_desk(runner, on_disk):
    assert runner.invoke(cli, ["--database", on_disk, "create-schema"]).exit_code == 0
    reply = runner.invoke(cli, ["--database", on_disk, "check"])
    assert reply.exit_code == 0
    assert "airports      0" in reply.output
    assert "observations  0" in reply.output


def test_counting_an_empty_desk_is_not_itself_a_failure(runner, on_disk):
    # The counts are a reading. An empty desk is what create-schema leaves,
    # and a command that fails there fails on the one morning it is expected.
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    assert runner.invoke(cli, ["--database", on_disk, "check"]).exit_code == OK


def test_asking_an_empty_desk_whether_it_is_fit_is_a_failure(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    assert reply.exit_code == OPERATOR_CAN_FIX
    assert "not fit" in reply.output


def test_the_fit_report_names_what_is_missing_and_how_to_fix_it(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    for command in ("load fares", "load rules", "load combinability", "collect run"):
        assert command in reply.output


def test_the_fit_report_marks_each_question_and_not_only_the_total(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    for question in ("tariff", "rules", "combinability", "collecting", "observations"):
        assert f"no  {question}" in reply.output


def test_the_counts_come_out_whether_or_not_the_desk_is_fit(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    assert "fares         0" in reply.output


def test_a_desk_with_something_in_it_answers_those_questions_yes(runner, on_disk):
    # Seeded as of today, because the two date questions ask whether the desk
    # is collecting now and not whether it ever did.
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    runner.invoke(cli, ["--database", on_disk, "seed", "--on", date.today().isoformat()])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    for question in ("tariff", "rules", "combinability", "observations"):
        assert f"ok  {question}" in reply.output


def test_a_desk_that_has_never_collected_is_not_fit_however_full_it_is(runner, on_disk):
    # A seeded desk holds fares, rules and a week of readings and has never
    # run a collection, which is exactly the state a count cannot see.
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    runner.invoke(cli, ["--database", on_disk, "seed", "--on", date.today().isoformat()])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    assert reply.exit_code == OPERATOR_CAN_FIX
    assert "not fit: collecting" in reply.output


def test_the_questions_are_the_ones_the_service_answers(runner, on_disk):
    # Two lists of what a desk needs would answer differently within a month,
    # and the one nobody looks at would be the wrong one.
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    reply = runner.invoke(cli, ["--database", on_disk, "check", "--fit"])
    with Database(Settings(database_url=on_disk)).unit() as work:
        asked = list(fitness(work))
    for question in asked:
        assert question in reply.output


def test_create_schema_says_so_unless_it_is_told_to_be_quiet(runner, on_disk):
    loud = runner.invoke(cli, ["--database", on_disk, "create-schema"])
    assert "schema built" in loud.output
    quiet = runner.invoke(cli, ["--database", on_disk, "--quiet", "create-schema"])
    assert quiet.output.strip() == ""


def test_check_against_a_database_with_no_schema_is_a_failure(runner, on_disk):
    reply = runner.invoke(cli, ["--database", on_disk, "check"])
    assert reply.exit_code != 0


def test_creating_the_schema_twice_is_not_an_error(runner, on_disk):
    runner.invoke(cli, ["--database", on_disk, "create-schema"])
    assert runner.invoke(cli, ["--database", on_disk, "create-schema"]).exit_code == 0


def test_a_command_that_does_not_exist_is_a_usage_error(runner):
    reply = runner.invoke(cli, ["nonsense"])
    assert reply.exit_code == 2
    assert "No such command" in reply.output


def test_the_version_is_reported(runner):
    reply = runner.invoke(cli, ["--version"])
    assert reply.exit_code == 0
    assert "0.1.0" in reply.output


def test_the_log_says_which_command_started(runner, on_disk):
    reply = runner.invoke(cli, ["--database", on_disk, "create-schema"])
    assert "event=command.started" in reply.output
    assert "command=create-schema" in reply.output


def test_every_line_of_one_command_shares_a_run_identifier(runner, on_disk):
    reply = runner.invoke(cli, ["--database", on_disk, "create-schema"])
    runs = {
        part
        for line in reply.output.splitlines()
        for part in line.split()
        if part.startswith("run=")
    }
    assert len(runs) == 1


def test_two_commands_do_not_share_one(runner, on_disk):
    first = runner.invoke(cli, ["--database", on_disk, "create-schema"]).output
    second = runner.invoke(cli, ["--database", on_disk, "check"]).output
    assert first.split("run=")[1][:12] != second.split("run=")[1][:12]


def test_quiet_means_quiet_on_the_log_as_well(runner, on_disk):
    # A cron run told to say nothing on success that still writes a line per
    # event fills the night shift's mail.
    reply = runner.invoke(cli, ["--database", on_disk, "--quiet", "create-schema"])
    assert "event=" not in reply.output


def test_the_level_can_still_be_named_when_somebody_wants_both(runner, on_disk):
    reply = runner.invoke(
        cli, ["--database", on_disk, "--quiet", "--log-level", "INFO", "create-schema"]
    )
    assert "event=command.started" in reply.output
    assert "schema built" not in reply.output
