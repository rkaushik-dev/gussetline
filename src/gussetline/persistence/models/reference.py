"""Reference tables, which are the ones the desk edits by hand.

Airports, carriers and rates of exchange. They change slowly, they are small
enough to load whole, and they are the only tables an analyst is allowed to
write to directly, which is why every one of them carries a source column
saying where the row came from. A rate keyed in by hand and a rate loaded from
the quarterly file are not the same evidence, and an audit that quotes one has
to be able to say which.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import Boolean, Date, Float, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from gussetline.persistence.base import Amount, Base, TimestampMixin


class AirportRow(Base, TimestampMixin):
    __tablename__ = "airports"

    code: Mapped[str] = mapped_column(String(3), primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    city: Mapped[str] = mapped_column(String(3), index=True)
    country: Mapped[str] = mapped_column(String(2), index=True)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    utc_offset_minutes: Mapped[int] = mapped_column(Integer)
    source: Mapped[str] = mapped_column(String(16), default="shipped")

    def __repr__(self) -> str:
        return f"<Airport {self.code} in {self.city}>"


class CarrierRow(Base, TimestampMixin):
    __tablename__ = "carriers"

    code: Mapped[str] = mapped_column(String(2), primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    country: Mapped[str] = mapped_column(String(2))
    alliance: Mapped[str] = mapped_column(String(16), default="none")
    low_cost: Mapped[bool] = mapped_column(Boolean, default=False)
    source: Mapped[str] = mapped_column(String(16), default="shipped")

    def __repr__(self) -> str:
        return f"<Carrier {self.code}>"


class InterlineRow(Base, TimestampMixin):
    """One agreement, stored once with the codes in sorted order."""

    __tablename__ = "interline"
    __table_args__ = (UniqueConstraint("left_carrier", "right_carrier"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    left_carrier: Mapped[str] = mapped_column(String(2), index=True)
    right_carrier: Mapped[str] = mapped_column(String(2), index=True)
    signed_on: Mapped[date | None] = mapped_column(Date, nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="paper")

    def __repr__(self) -> str:
        return f"<Interline {self.left_carrier}/{self.right_carrier}>"


class RateRow(Base, TimestampMixin):
    """One currency's rate from one date, in one of the two tables."""

    __tablename__ = "rates"
    __table_args__ = (UniqueConstraint("table_name", "currency", "effective"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    table_name: Mapped[str] = mapped_column(String(8), index=True)
    currency: Mapped[str] = mapped_column(String(3), index=True)
    effective: Mapped[date] = mapped_column(Date, index=True)
    units_per_nuc: Mapped[Decimal] = mapped_column(Amount)
    source: Mapped[str] = mapped_column(String(16), default="quarterly")

    def __repr__(self) -> str:
        return f"<Rate {self.table_name} {self.currency} from {self.effective}>"


class MileageRow(Base, TimestampMixin):
    """A published ticketed point mileage, keyed the way the tariff keys it."""

    __tablename__ = "mileages"
    __table_args__ = (UniqueConstraint("origin", "destination", "indicator"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    origin: Mapped[str] = mapped_column(String(3), index=True)
    destination: Mapped[str] = mapped_column(String(3), index=True)
    indicator: Mapped[str] = mapped_column(String(2), default="")
    miles: Mapped[int] = mapped_column(Integer)
    source: Mapped[str] = mapped_column(String(16), default="published")

    def __repr__(self) -> str:
        return f"<Mileage {self.origin}-{self.destination} {self.miles}>"
