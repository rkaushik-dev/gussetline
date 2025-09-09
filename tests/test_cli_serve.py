from __future__ import annotations

import pytest
from click.testing import CliRunner

from gussetline.api.security import Role
from gussetline.cli.main import cli
from gussetline.cli.serve import KEYS_ENVIRONMENT, ring_from_environment
from gussetline.config import Settings
from gussetline.persistence.unitofwork import Database


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def env(monkeypatch, tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(tmp_path / "raw"))
    monkeypatch.delenv(KEYS_ENVIRONMENT, raising=False)
    Database(Settings(database_url=url)).create_schema()
    return url


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_the_two_commands_show_up_in_the_help(runner, env):
    out = run(runner, "--help").output
    assert "serve" in out
    assert "issue-key" in out


def test_a_key_is_shown_once_and_never_stored(runner, env):
    reply = run(runner, "issue-key", "anna", "--role", "agent")
    assert reply.exit_code == 0
    assert "anna as agent" in reply.output
    assert "that is the only time it will be shown" in reply.output


def test_a_key_comes_back_ready_to_paste_into_the_environment(runner, env):
    reply = run(runner, "issue-key", "screens")
    assert f"{KEYS_ENVIRONMENT}=screens:reader:" in reply.output


def test_two_keys_are_never_the_same(runner, env):
    first = run(runner, "issue-key", "anna").output
    second = run(runner, "issue-key", "anna").output
    assert first != second


def test_a_role_nobody_has_is_a_usage_error(runner, env):
    reply = run(runner, "issue-key", "anna", "--role", "admiral")
    assert reply.exit_code == 2


def test_no_keys_at_all_means_no_ring(monkeypatch):
    # Which is a service that lets everything through. A ring with nothing in
    # it would refuse everything, and the difference matters on a laptop.
    monkeypatch.delenv(KEYS_ENVIRONMENT, raising=False)
    assert ring_from_environment() is None


def test_an_empty_setting_means_no_ring_either(monkeypatch):
    monkeypatch.setenv(KEYS_ENVIRONMENT, "   ")
    assert ring_from_environment() is None


def test_a_ring_reads_its_keys_out_of_the_environment(monkeypatch):
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna:agent:secret-one,ops:operator:secret-two")
    ring = ring_from_environment()
    assert ring is not None
    assert len(ring) == 2
    found = ring.find("secret-one")
    assert found is not None
    assert found.name == "anna"
    assert found.role is Role.AGENT


def test_a_key_that_is_not_three_parts_is_refused_with_what_it_saw(monkeypatch):
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna:secret")
    with pytest.raises(Exception, match="name:role:secret"):
        ring_from_environment()


def test_a_key_with_an_empty_part_is_refused(monkeypatch):
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna::secret")
    with pytest.raises(Exception, match="name:role:secret"):
        ring_from_environment()


def test_a_role_the_service_does_not_have_is_refused_by_name(monkeypatch):
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna:admiral:secret")
    with pytest.raises(Exception, match="not a role"):
        ring_from_environment()


def test_reload_with_keys_set_is_refused_rather_than_quietly_unprotected(runner, env, monkeypatch):
    # The reloader re-imports in a child process and the ring is built in
    # memory at start up, so the child comes up with an empty one.
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna:agent:secret")
    reply = run(runner, "serve", "--reload")
    assert reply.exit_code != 0
    assert "reload cannot carry the keys" in reply.output


def test_serving_says_when_nothing_is_protecting_it(runner, env, monkeypatch):
    started: list[tuple[str, int]] = []

    class Fake:
        @staticmethod
        def run(app, host, port, log_level):
            started.append((host, port))

    monkeypatch.setitem(__import__("sys").modules, "uvicorn", Fake)
    reply = run(runner, "serve", "--port", "9001")
    assert reply.exit_code == 0
    assert "no keys are set" in reply.output
    assert "listening on 127.0.0.1:9001" in reply.output
    assert started == [("127.0.0.1", 9001)]


def test_serving_with_keys_says_how_many(runner, env, monkeypatch):
    class Fake:
        @staticmethod
        def run(app, host, port, log_level):
            return None

    monkeypatch.setitem(__import__("sys").modules, "uvicorn", Fake)
    monkeypatch.setenv(KEYS_ENVIRONMENT, "anna:agent:secret,ops:operator:other")
    reply = run(runner, "serve")
    assert "2 keys loaded" in reply.output
    assert "no keys are set" not in reply.output
