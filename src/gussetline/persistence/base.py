"""The declarative base, and the naming convention that makes migrations dull.

Every constraint is named by a rule rather than by the database. Without that,
SQLite invents a name, PostgreSQL invents a different one, and a migration
that drops a constraint works on the machine it was written on and fails on
the one it is deployed to. With it, the name is a function of the table and
the columns and both databases agree.

Amounts are stored as strings and not as numerics. That looks wrong and it is
deliberate. SQLite has no decimal type and will hand back a float, which loses
the third decimal on a Kuwaiti dinar and the hundredth on everything else. A
string round trips exactly through every database the desk has been asked to
run on, and the money type is doing the arithmetic anyway.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import DateTime, MetaData, String, TypeDecorator
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from gussetline.money import Money

NAMING = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING)


class Amount(TypeDecorator[Decimal]):
    """A Decimal stored as text, because floats lose fares.

    ``cache_ok`` is set because the type carries no per instance state, and
    without it SQLAlchemy declines to cache any statement that uses it, which
    on the observation table is most of them.
    """

    impl = String(24)
    cache_ok = True

    def process_bind_param(self, value: Decimal | None, dialect: object) -> str | None:
        if value is None:
            return None
        return str(value)

    def process_result_value(self, value: str | None, dialect: object) -> Decimal | None:
        if value is None:
            return None
        return Decimal(value)


class Stamp(TypeDecorator[datetime]):
    """A timestamp stored without a zone, because everything here is UTC.

    Any aware value is converted before it is written rather than refused,
    so a caller that has a zone on its clock does not have to know that this
    layer does not.
    """

    impl = DateTime()
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: object) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is not None:
            return value.astimezone(UTC).replace(tzinfo=None)
        return value

    def process_result_value(self, value: datetime | None, dialect: object) -> datetime | None:
        return value


def money_columns(prefix: str, nullable: bool = False) -> tuple[Mapped[Decimal], Mapped[str]]:
    """An amount and its currency, which never travel apart.

    Two columns and not one, because a single string holding both cannot be
    summed or ordered by the database, and the reports do both.
    """
    return (
        mapped_column(f"{prefix}_amount", Amount, nullable=nullable),
        mapped_column(f"{prefix}_currency", String(3), nullable=nullable),
    )


def to_money(amount: Decimal | None, currency: str | None) -> Money | None:
    if amount is None or not currency:
        return None
    return Money(amount, currency)


class TimestampMixin:
    """When a row was written and last changed."""

    created_at: Mapped[datetime] = mapped_column(
        Stamp, default=lambda: datetime.now(UTC).replace(tzinfo=None)
    )
    updated_at: Mapped[datetime] = mapped_column(
        Stamp,
        default=lambda: datetime.now(UTC).replace(tzinfo=None),
        onupdate=lambda: datetime.now(UTC).replace(tzinfo=None),
    )
