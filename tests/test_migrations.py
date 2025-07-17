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


def test_every_revision_has_a_parent_except_the_first():
    """A branch in the history is a merge nobody will notice until deploy."""
    from alembic.script import ScriptDirectory

    scripts = ScriptDirectory(str(ROOT / "migrations"))
    heads = scripts.get_heads()
    assert len(heads) == 1, f"the history has branched: {heads}"


def test_the_revisions_run_in_the_order_they_are_numbered():
    from alembic.script import ScriptDirectory

    scripts = ScriptDirectory(str(ROOT / "migrations"))
    walked = [revision.revision for revision in scripts.walk_revisions()]
    assert walked == sorted(walked, reverse=True)


def test_every_revision_says_how_to_go_back():
    """A downgrade that is a bare pass is a deploy nobody can undo.

    Autogenerate writes both directions and it is the down one that gets
    emptied out, usually while somebody is getting a migration to apply.
    The test is deliberately crude: it reads the source and looks for a
    downgrade that does nothing, because there is no way to ask alembic
    whether a function it can call is going to do anything.
    """
    from alembic.script import ScriptDirectory

    scripts = ScriptDirectory(str(ROOT / "migrations"))
    empty = []
    for revision in scripts.walk_revisions():
        source = Path(revision.path).read_text(encoding="utf-8")
        body = source.split("def downgrade()", 1)[-1]
        lines = [
            line.strip()
            for line in body.splitlines()[1:]
            if line.strip() and not line.strip().startswith(("#", '"""'))
        ]
        if lines in ([], ["pass"]):
            empty.append(revision.revision)
    assert empty == [], f"these cannot be undone: {empty}"


def test_the_chain_reaches_base_from_the_head_without_a_gap():
    """One line back to the beginning. A missing parent is a silent skip."""
    from alembic.script import ScriptDirectory

    scripts = ScriptDirectory(str(ROOT / "migrations"))
    walked = list(scripts.walk_revisions())
    parents = [revision.down_revision for revision in walked]
    assert parents[-1] is None
    assert all(parent is not None for parent in parents[:-1])

    known = {revision.revision for revision in walked}
    assert all(parent in known for parent in parents[:-1])


def test_no_two_revisions_share_an_identifier():
    from alembic.script import ScriptDirectory

    scripts = ScriptDirectory(str(ROOT / "migrations"))
    seen = [revision.revision for revision in scripts.walk_revisions()]
    assert len(seen) == len(set(seen))
