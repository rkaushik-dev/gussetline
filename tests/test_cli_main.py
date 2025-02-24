from __future__ import annotations

import pytest
from click.testing import CliRunner

from gussetline.cli.main import (
    OK,
    OPERATOR_CAN_FIX,
    SOMEBODY_SHOULD_LOOK,
    cli,
    exit_code_for,
)
from gussetline.errors import (
    ConfigError,
    ConstructionError,
    NoFareFound,
    NotFoundError,
    SupplierBlocked,
    SupplierUnavailable,
    ValidationError,
)


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
