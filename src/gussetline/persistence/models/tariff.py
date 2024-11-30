"""The tariff tables, which are loaded and never edited.

A fare filing is replaced whole rather than updated, because the tariff sends
a complete file and a partial update leaves rows nobody can account for. So
every row carries the load it came in on, and a load is a row of its own with
a stamp on it. Retiring a load means deleting the fares that name it, and
proving what a ticket was priced against means reading the load that was
current on the ticketing date.

The alternative, updating rows in place, was tried first. It works until a
supplier disputes a price from four months ago and the desk cannot say what
was in the tariff that day.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    Date,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from gussetline.persistence.base import Amount, Base, Stamp, TimestampMixin


class LoadRow(Base, TimestampMixin):
    """One tariff file, and when it was taken in."""

    __tablename__ = "tariff_loads"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    carrier: Mapped[str] = mapped_column(String(2), index=True)
    filename: Mapped[str] = mapped_column(String(128))
    digest: Mapped[str] = mapped_column(String(32), index=True)
    loaded_at: Mapped[datetime] = mapped_column(Stamp, index=True)
    effective_from: Mapped[date] = mapped_column(Date, index=True)
    row_count: Mapped[int] = mapped_column(Integer, default=0)
    retired: Mapped[bool] = mapped_column(Boolean, default=False, index=True)

    fares: Mapped[list[FareRow]] = relationship(back_populates="load", cascade="all, delete-orphan")

    def __repr__(self) -> str:
        return f"<Load {self.carrier} {self.filename} {self.row_count} rows>"


class FareRow(Base, TimestampMixin):
    """One published fare as the tariff filed it."""

    __tablename__ = "fares"
    __table_args__ = (
        Index("ix_fares_market", "carrier", "origin", "destination"),
        Index("ix_fares_lookup", "carrier", "origin", "destination", "effective"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    load_id: Mapped[int] = mapped_column(ForeignKey("tariff_loads.id"), index=True)
    carrier: Mapped[str] = mapped_column(String(2))
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    fare_basis: Mapped[str] = mapped_column(String(16))
    amount: Mapped[Decimal] = mapped_column(Amount)
    currency: Mapped[str] = mapped_column(String(3))
    owrt: Mapped[str] = mapped_column(String(1))
    global_indicator: Mapped[str] = mapped_column(String(2))
    rule_number: Mapped[str] = mapped_column(String(8))
    routing_number: Mapped[str] = mapped_column(String(8))
    tariff_number: Mapped[str] = mapped_column(String(4), default="1")
    footnote: Mapped[str] = mapped_column(String(4), default="")
    cabin: Mapped[str] = mapped_column(String(1), default="Y")
    fare_type: Mapped[str] = mapped_column(String(16), default="published")
    effective: Mapped[date] = mapped_column(Date)
    discontinue: Mapped[date] = mapped_column(Date)
    first_travel: Mapped[date] = mapped_column(Date)
    last_travel: Mapped[date] = mapped_column(Date)

    load: Mapped[LoadRow] = relationship(back_populates="fares")

    def __repr__(self) -> str:
        return f"<Fare {self.carrier} {self.origin}{self.destination} {self.fare_basis}>"


class RuleRow(Base, TimestampMixin):
    """A rule and its provisions, kept as filed text plus what we parsed."""

    __tablename__ = "rules"
    __table_args__ = (UniqueConstraint("carrier", "number", "tariff_number"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    load_id: Mapped[int | None] = mapped_column(
        ForeignKey("tariff_loads.id"), index=True, nullable=True
    )
    carrier: Mapped[str] = mapped_column(String(2), index=True)
    number: Mapped[str] = mapped_column(String(8), index=True)
    tariff_number: Mapped[str] = mapped_column(String(4), default="1")
    filed_text: Mapped[str] = mapped_column(String(8192), default="")

    provisions: Mapped[list[ProvisionRow]] = relationship(
        back_populates="rule", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return f"<Rule {self.carrier} {self.number}>"


class ProvisionRow(Base, TimestampMixin):
    """One category inside a rule, stored as the fields we read out of it.

    The values are a small set of named columns rather than a blob, because
    the reports ask questions like which fares want a Saturday night, and a
    blob answers that only by parsing every row.
    """

    __tablename__ = "provisions"
    __table_args__ = (Index("ix_provisions_category", "rule_id", "category"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    rule_id: Mapped[int] = mapped_column(ForeignKey("rules.id"), index=True)
    category: Mapped[int] = mapped_column(Integer)
    kind: Mapped[str] = mapped_column(String(32))
    number_one: Mapped[int | None] = mapped_column(Integer, nullable=True)
    number_two: Mapped[int | None] = mapped_column(Integer, nullable=True)
    text_one: Mapped[str] = mapped_column(String(64), default="")
    text_two: Mapped[str] = mapped_column(String(64), default="")
    amount: Mapped[Decimal | None] = mapped_column(Amount, nullable=True)
    currency: Mapped[str] = mapped_column(String(3), default="")
    flag_one: Mapped[bool] = mapped_column(Boolean, default=False)
    flag_two: Mapped[bool] = mapped_column(Boolean, default=False)

    rule: Mapped[RuleRow] = relationship(back_populates="provisions")

    def __repr__(self) -> str:
        return f"<Provision cat {self.category} {self.kind}>"


class SurchargeRow(Base, TimestampMixin):
    """A filed Q surcharge."""

    __tablename__ = "surcharges"
    __table_args__ = (Index("ix_surcharges_market", "carrier", "origin", "destination"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    load_id: Mapped[int | None] = mapped_column(
        ForeignKey("tariff_loads.id"), index=True, nullable=True
    )
    carrier: Mapped[str] = mapped_column(String(2))
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    amount: Mapped[Decimal] = mapped_column(Amount)
    currency: Mapped[str] = mapped_column(String(3))
    scope: Mapped[str] = mapped_column(String(16), default="component")
    reason: Mapped[str] = mapped_column(String(4), default="Q")
    directional: Mapped[bool] = mapped_column(Boolean, default=False)
    first_travel: Mapped[date] = mapped_column(Date)
    last_travel: Mapped[date] = mapped_column(Date)

    def __repr__(self) -> str:
        return f"<Surcharge {self.reason} {self.origin}{self.destination}>"


class CombinabilityRow(Base, TimestampMixin):
    """What one fare may be joined to."""

    __tablename__ = "combinability"
    __table_args__ = (UniqueConstraint("carrier", "fare_basis"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    load_id: Mapped[int | None] = mapped_column(
        ForeignKey("tariff_loads.id"), index=True, nullable=True
    )
    carrier: Mapped[str] = mapped_column(String(2), index=True)
    fare_basis: Mapped[str] = mapped_column(String(16), index=True)
    round_trip: Mapped[bool] = mapped_column(Boolean, default=False)
    circle_trip: Mapped[bool] = mapped_column(Boolean, default=False)
    open_jaw: Mapped[bool] = mapped_column(Boolean, default=False)
    double_open_jaw: Mapped[bool] = mapped_column(Boolean, default=False)
    end_on_end: Mapped[bool] = mapped_column(Boolean, default=False)
    same_carrier_only: Mapped[bool] = mapped_column(Boolean, default=True)
    same_rule_only: Mapped[bool] = mapped_column(Boolean, default=False)
    side_trip: Mapped[bool] = mapped_column(Boolean, default=False)

    def __repr__(self) -> str:
        return f"<Combinability {self.carrier} {self.fare_basis}>"


class RoutingRow(Base, TimestampMixin):
    """One permitted hop inside a routing map."""

    __tablename__ = "routings"
    __table_args__ = (Index("ix_routings_map", "carrier", "number"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    load_id: Mapped[int | None] = mapped_column(
        ForeignKey("tariff_loads.id"), index=True, nullable=True
    )
    carrier: Mapped[str] = mapped_column(String(2))
    number: Mapped[str] = mapped_column(String(8))
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    carriers: Mapped[str] = mapped_column(String(64), default="")
    bidirectional: Mapped[bool] = mapped_column(Boolean, default=True)

    def __repr__(self) -> str:
        return f"<Routing {self.carrier} {self.number} {self.origin}-{self.destination}>"
