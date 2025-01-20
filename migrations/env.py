"""Alembic's entry point, wired to our own settings and metadata.

The url comes from the settings object rather than from alembic.ini, so a
migration run and an application run cannot end up pointed at two different
databases. That has happened, and the symptom is a migration that reports
success against an empty file while the real database stays behind.
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import engine_from_config, pool

from gussetline.config import from_env
from gussetline.persistence.base import Base
from gussetline.persistence.models import *  # noqa: F401,F403

config = context.config
target_metadata = Base.metadata

settings = from_env()
config.set_main_option("sqlalchemy.url", settings.database_url)


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        render_as_batch=True,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
