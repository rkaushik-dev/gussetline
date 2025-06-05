"""The unit of work, which owns the transaction so nothing else has to.

One session, one transaction, one exit. A caller opens it, uses the
repositories hanging off it, and either commits or does not, and the context
manager rolls back on the way out if nobody did. Rolling back on exit rather
than committing is the choice worth stating: a run that raises halfway should
leave the database as it was, and the alternative default has written half a
collection more than once.

The engine is made here too, with the pragma SQLite needs to enforce a
foreign key at all. Without it the cascade deletes in the schema are
decoration and an orphaned coupon survives its ticket.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from gussetline.config import Settings
from gussetline.persistence.base import Base
from gussetline.persistence.repositories import (
    AirportRepository,
    CarrierRepository,
    CombinabilityRepository,
    ObservationRepository,
    QuoteRepository,
    RuleRepository,
    RunRepository,
    TariffRepository,
    WatchRepository,
)


def make_engine(settings: Settings, echo: bool = False) -> Engine:
    """An engine with the pragma SQLite needs to mean what the schema says.

    An in memory database also needs a static pool. Without one every
    connection gets its own empty database, so a schema built on the first
    connection is invisible to the second, and anything that opens a session
    on another thread reports that no table exists. That is the shape of the
    bug the http tests found: the schema was there and the reader was looking
    at a different database of the same name.
    """
    if settings.in_memory:
        engine = create_engine(
            settings.database_url,
            echo=echo,
            future=True,
            poolclass=StaticPool,
            connect_args={"check_same_thread": False},
        )
    else:
        engine = create_engine(settings.database_url, echo=echo, future=True)

    if settings.is_sqlite:

        @event.listens_for(engine, "connect")
        def _enforce_keys(connection: Any, record: Any) -> None:
            cursor = connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

    return engine


def create_schema(engine: Engine) -> None:
    """Build the whole schema. For tests and for a first run, not for deploys."""
    Base.metadata.create_all(engine)


def drop_schema(engine: Engine) -> None:
    Base.metadata.drop_all(engine)


class UnitOfWork:
    """One transaction and every repository that shares it."""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.airports = AirportRepository(session)
        self.carriers = CarrierRepository(session)
        self.tariff = TariffRepository(session)
        self.observations = ObservationRepository(session)
        self.watches = WatchRepository(session)
        self.quotes = QuoteRepository(session)
        self.runs = RunRepository(session)
        self.combinability = CombinabilityRepository(session)
        self.rules = RuleRepository(session)
        self.committed = False

    def commit(self) -> None:
        self.session.commit()
        self.committed = True

    def rollback(self) -> None:
        self.session.rollback()

    def flush(self) -> None:
        self.session.flush()

    def __enter__(self) -> UnitOfWork:
        return self

    def __exit__(self, *exc: object) -> None:
        if not self.committed:
            self.rollback()


class Database:
    """The engine and the session factory, made once per process."""

    def __init__(self, settings: Settings, echo: bool = False) -> None:
        self.settings = settings
        self.engine = make_engine(settings, echo=echo)
        self._sessions = sessionmaker(bind=self.engine, expire_on_commit=False)

    def create_schema(self) -> None:
        create_schema(self.engine)

    @contextmanager
    def unit(self) -> Iterator[UnitOfWork]:
        """A transaction that rolls back unless somebody commits it."""
        session = self._sessions()
        work = UnitOfWork(session)
        try:
            yield work
            if not work.committed:
                work.rollback()
        except Exception:
            work.rollback()
            raise
        finally:
            session.close()

    def dispose(self) -> None:
        self.engine.dispose()

    def __str__(self) -> str:
        return f"database at {self.settings.database_url}"


def in_memory() -> Database:
    """A throwaway database with the schema already built."""
    database = Database(Settings().with_database("sqlite+pysqlite:///:memory:"))
    database.create_schema()
    return database
