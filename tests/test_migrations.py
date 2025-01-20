"""The migrations have to build the schema the models describe.

Autogenerate is a draft. It misses a type change, it invents a constraint
name when the convention is not loaded, and it silently skips a table nobody
imported into the metadata. So the suite runs the migrations against an empty
database and compares the result to the metadata, and any difference is a
failure with the difference named.

This is the check that catches the commit where somebody adds a column and
forgets the migration, which is otherwise found in production.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect

from gussetline.persistence.base import Base

ROOT = Path(__file__).resolve().parent.parent


def alembic_config(url: str) -> Config:
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


@pytest.fixture
def migrated(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'migrated.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    command.upgrade(alembic_config(url), "head")
    engine = create_engine(url)
    yield engine
    engine.dispose()


def test_the_migrated_schema_matches_the_models(migrated):
    with migrated.connect() as connection:
        context = MigrationContext.configure(connection)
        differences = compare_metadata(context, Base.metadata)
    assert differences == []


def test_every_table_in_the_metadata_was_created(migrated):
    made = set(inspect(migrated).get_table_names())
    wanted = set(Base.metadata.tables)
    assert wanted - made == set()


def test_the_alembic_version_table_is_there(migrated):
    assert "alembic_version" in inspect(migrated).get_table_names()


def test_a_downgrade_takes_every_table_back_out(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'roundtrip.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.downgrade(config, "base")
    engine = create_engine(url)
    try:
        left = set(inspect(engine).get_table_names()) - {"alembic_version"}
        assert left == set()
    finally:
        engine.dispose()


def test_the_migration_can_be_run_twice_without_complaint(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'twice.sqlite3'}"
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.upgrade(config, "head")
    engine = create_engine(url)
    try:
        assert "fares" in inspect(engine).get_table_names()
    finally:
        engine.dispose()


def test_the_named_constraints_survive_the_migration(migrated):
    inspector = inspect(migrated)
    names = {i["name"] for i in inspector.get_indexes("observations")}
    assert "ix_observations_series_seen" in names
    assert "ix_observations_market" in names


def test_the_foreign_keys_are_created_with_their_names(migrated):
    keys = inspect(migrated).get_foreign_keys("coupons")
    assert keys
    assert keys[0]["referred_table"] == "tickets"
