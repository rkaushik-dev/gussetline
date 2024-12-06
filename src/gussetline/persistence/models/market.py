"""What the collectors saw, which is the table that grows.

Every offer from every poll lands here, so this is the only part of the schema
where the row count matters. Nine thousand watched itineraries at two polls a
week with a dozen offers each is a bit over a million rows a year, which is
nothing for a database and everything for a query with no index on it.

Two decisions follow from that.

The series key is stored on the row rather than derived at read time. It is
redundant with the market and the date and it is the column every report
groups by, and computing it in the query means no index can help.

And the fingerprint is unique per series. The same offer seen twice does not
make a second row, it moves the last seen stamp on the first one, which is
what keeps the table at a million rows a year instead of nine.
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


class ObservationRow(Base, TimestampMixin):
    """One priced itinerary as one supplier described it."""

    __tablename__ = "observations"
    __table_args__ = (
        UniqueConstraint("series_key", "fingerprint"),
        Index("ix_observations_series_seen", "series_key", "last_seen"),
        Index("ix_observations_market", "origin", "destination", "departure"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    series_key: Mapped[str] = mapped_column(String(48), index=True)
    fingerprint: Mapped[str] = mapped_column(String(32), index=True)
    source: Mapped[str] = mapped_column(String(16), index=True)
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[date] = mapped_column(Date)
    cabin: Mapped[str] = mapped_column(String(1), default="Y")
    fare_bases: Mapped[str] = mapped_column(String(128), default="")
    base_amount: Mapped[Decimal] = mapped_column(Amount)
    base_currency: Mapped[str] = mapped_column(String(3))
    tax_amount: Mapped[Decimal] = mapped_column(Amount)
    surcharge_amount: Mapped[Decimal] = mapped_column(Amount)
    total_amount: Mapped[Decimal] = mapped_column(Amount, index=True)
    quoted_total: Mapped[Decimal | None] = mapped_column(Amount, nullable=True)
    seats: Mapped[int | None] = mapped_column(Integer, nullable=True)
    bag_pieces: Mapped[int | None] = mapped_column(Integer, nullable=True)
    refundable: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    validating_carrier: Mapped[str] = mapped_column(String(2), default="")
    raw_digest: Mapped[str] = mapped_column(String(32), default="", index=True)
    first_seen: Mapped[datetime] = mapped_column(Stamp)
    last_seen: Mapped[datetime] = mapped_column(Stamp, index=True)
    sightings: Mapped[int] = mapped_column(Integer, default=1)

    sectors: Mapped[list[SectorRow]] = relationship(
        back_populates="observation", cascade="all, delete-orphan", order_by="SectorRow.position"
    )
    taxes: Mapped[list[TaxRow]] = relationship(
        back_populates="observation", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return f"<Observation {self.series_key} {self.total_amount} {self.base_currency}>"


class SectorRow(Base):
    """One flown sector of an observed itinerary."""

    __tablename__ = "observation_sectors"
    __table_args__ = (UniqueConstraint("observation_id", "position"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    observation_id: Mapped[int] = mapped_column(ForeignKey("observations.id"), index=True)
    position: Mapped[int] = mapped_column(Integer)
    marketing_carrier: Mapped[str] = mapped_column(String(2))
    operating_carrier: Mapped[str] = mapped_column(String(2), default="")
    flight_number: Mapped[str] = mapped_column(String(5))
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[datetime] = mapped_column(Stamp)
    arrival: Mapped[datetime] = mapped_column(Stamp)
    day_change: Mapped[int] = mapped_column(Integer, default=0)
    booking_class: Mapped[str] = mapped_column(String(1))
    cabin: Mapped[str] = mapped_column(String(1), default="Y")

    observation: Mapped[ObservationRow] = relationship(back_populates="sectors")

    def __repr__(self) -> str:
        return (
            f"<Sector {self.marketing_carrier}{self.flight_number} {self.origin}{self.destination}>"
        )


class TaxRow(Base):
    """One tax or carrier surcharge on an observed price."""

    __tablename__ = "observation_taxes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    observation_id: Mapped[int] = mapped_column(ForeignKey("observations.id"), index=True)
    code: Mapped[str] = mapped_column(String(3))
    amount: Mapped[Decimal] = mapped_column(Amount)
    currency: Mapped[str] = mapped_column(String(3))
    point: Mapped[str] = mapped_column(String(3), default="")
    carrier_imposed: Mapped[bool] = mapped_column(Boolean, default=False)

    observation: Mapped[ObservationRow] = relationship(back_populates="taxes")

    def __repr__(self) -> str:
        return f"<Tax {self.code} {self.amount}>"


class QuoteRow(Base, TimestampMixin):
    """A calendar quote, which has no flights and cannot be audited."""

    __tablename__ = "quotes"
    __table_args__ = (
        UniqueConstraint("origin", "destination", "departure", "observed_on"),
        Index("ix_quotes_market", "origin", "destination", "departure"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[date] = mapped_column(Date)
    observed_on: Mapped[date] = mapped_column(Date, index=True)
    amount: Mapped[Decimal] = mapped_column(Amount)
    currency: Mapped[str] = mapped_column(String(3))
    carrier: Mapped[str] = mapped_column(String(2), default="")
    seats: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sold_out: Mapped[bool] = mapped_column(Boolean, default=False)
    raw_digest: Mapped[str] = mapped_column(String(32), default="")

    def __repr__(self) -> str:
        return f"<Quote {self.origin}{self.destination} {self.departure} {self.amount}>"
