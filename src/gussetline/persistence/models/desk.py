"""The desk's own tables, which is everything that has a person behind it.

Tickets, the watches on them, the recommendations a watch produced, and what
the desk did about each one. This is the only part of the schema where rows
are edited after they are written, because a recommendation moves through
states and a ticket gets reissued.

The recommendation carries a copy of the numbers rather than a pointer to the
observation it came from. That is denormalised on purpose. An observation is a
reading of a market at a moment and the market moves; a recommendation is a
statement the desk made to a customer and it has to still say the same thing
in a year when the observation has been rolled off. Losing that distinction
means a savings report that changes every time it is run.
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


class TicketRow(Base, TimestampMixin):
    """One issued ticket, as the desk holds it."""

    __tablename__ = "tickets"
    __table_args__ = (Index("ix_tickets_market", "origin", "destination", "departure"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    number: Mapped[str] = mapped_column(String(16), unique=True)
    record_locator: Mapped[str] = mapped_column(String(8), index=True)
    passenger_name: Mapped[str] = mapped_column(String(64))
    passenger_type: Mapped[str] = mapped_column(String(3), default="ADT")
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[date] = mapped_column(Date, index=True)
    issued_on: Mapped[date] = mapped_column(Date, index=True)
    validating_carrier: Mapped[str] = mapped_column(String(2))
    fare_calculation: Mapped[str] = mapped_column(String(512), default="")
    base_amount: Mapped[Decimal] = mapped_column(Amount)
    base_currency: Mapped[str] = mapped_column(String(3))
    total_amount: Mapped[Decimal] = mapped_column(Amount)
    country_of_sale: Mapped[str] = mapped_column(String(2), default="")
    void: Mapped[bool] = mapped_column(Boolean, default=False)
    reissued_from: Mapped[str] = mapped_column(String(16), default="")

    coupons: Mapped[list[CouponRow]] = relationship(
        back_populates="ticket", cascade="all, delete-orphan", order_by="CouponRow.position"
    )
    watches: Mapped[list[WatchRow]] = relationship(back_populates="ticket")

    def __repr__(self) -> str:
        return f"<Ticket {self.number} {self.origin}{self.destination}>"


class CouponRow(Base):
    """One flight coupon, which is what actually gets flown or reissued."""

    __tablename__ = "coupons"
    __table_args__ = (UniqueConstraint("ticket_id", "position"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("tickets.id"), index=True)
    position: Mapped[int] = mapped_column(Integer)
    carrier: Mapped[str] = mapped_column(String(2))
    flight_number: Mapped[str] = mapped_column(String(5))
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[datetime] = mapped_column(Stamp)
    booking_class: Mapped[str] = mapped_column(String(1))
    fare_basis: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(8), default="OK")
    flown: Mapped[bool] = mapped_column(Boolean, default=False)

    ticket: Mapped[TicketRow] = relationship(back_populates="coupons")

    def __repr__(self) -> str:
        return f"<Coupon {self.position} {self.carrier}{self.flight_number} {self.status}>"


class WatchRow(Base, TimestampMixin):
    """A standing instruction to keep looking at one ticket's market."""

    __tablename__ = "watches"
    __table_args__ = (Index("ix_watches_live", "active", "departure"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("tickets.id"), index=True)
    series_key: Mapped[str] = mapped_column(String(48), index=True)
    origin: Mapped[str] = mapped_column(String(3))
    destination: Mapped[str] = mapped_column(String(3))
    departure: Mapped[date] = mapped_column(Date)
    cabin: Mapped[str] = mapped_column(String(1), default="Y")
    threshold_amount: Mapped[Decimal] = mapped_column(Amount)
    threshold_currency: Mapped[str] = mapped_column(String(3))
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    retired_reason: Mapped[str] = mapped_column(String(64), default="")
    last_checked: Mapped[datetime | None] = mapped_column(Stamp, nullable=True)
    checks: Mapped[int] = mapped_column(Integer, default=0)

    ticket: Mapped[TicketRow] = relationship(back_populates="watches")
    recommendations: Mapped[list[RecommendationRow]] = relationship(
        back_populates="watch", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        state = "live" if self.active else f"retired, {self.retired_reason}"
        return f"<Watch {self.series_key} {state}>"


class RecommendationRow(Base, TimestampMixin):
    """A reissue the desk was told was worth doing, and what happened.

    Every number is copied rather than joined, because this row has to still
    read the same in a year when the observation behind it has rolled off.
    """

    __tablename__ = "recommendations"
    __table_args__ = (Index("ix_recommendations_state", "state", "raised_on"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    watch_id: Mapped[int] = mapped_column(ForeignKey("watches.id"), index=True)
    raised_on: Mapped[date] = mapped_column(Date, index=True)
    # No index=True here. The naming convention would call it
    # ix_recommendations_state, which is the name the composite index above
    # already has, and SQLite refuses the second one at create time.
    state: Mapped[str] = mapped_column(String(16), default="open")
    paid_amount: Mapped[Decimal] = mapped_column(Amount)
    new_fare_amount: Mapped[Decimal] = mapped_column(Amount)
    penalty_amount: Mapped[Decimal] = mapped_column(Amount)
    benefit_amount: Mapped[Decimal] = mapped_column(Amount)
    currency: Mapped[str] = mapped_column(String(3))
    residual: Mapped[str] = mapped_column(String(16), default="forfeited")
    fare_bases: Mapped[str] = mapped_column(String(128), default="")
    observation_fingerprint: Mapped[str] = mapped_column(String(32), default="")
    decided_on: Mapped[date | None] = mapped_column(Date, nullable=True)
    decided_by: Mapped[str] = mapped_column(String(32), default="")
    note: Mapped[str] = mapped_column(String(256), default="")

    watch: Mapped[WatchRow] = relationship(back_populates="recommendations")

    def __repr__(self) -> str:
        return f"<Recommendation {self.state} worth {self.benefit_amount} {self.currency}>"


class RunRow(Base, TimestampMixin):
    """One collection run, kept for the reliability report."""

    __tablename__ = "runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    started_at: Mapped[datetime] = mapped_column(Stamp, index=True)
    finished_at: Mapped[datetime | None] = mapped_column(Stamp, nullable=True)
    tasks: Mapped[int] = mapped_column(Integer, default=0)
    calls: Mapped[int] = mapped_column(Integer, default=0)
    offers: Mapped[int] = mapped_column(Integer, default=0)
    skipped_breaker: Mapped[int] = mapped_column(Integer, default=0)
    skipped_budget: Mapped[int] = mapped_column(Integer, default=0)
    cost_amount: Mapped[Decimal] = mapped_column(Amount, default=Decimal(0))
    cost_currency: Mapped[str] = mapped_column(String(3), default="EUR")

    results: Mapped[list[RunResultRow]] = relationship(
        back_populates="run", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return f"<Run {self.started_at:%Y-%m-%d %H:%M} {self.tasks} tasks>"


class RunResultRow(Base):
    """What one task in a run did, including the ones that did nothing."""

    __tablename__ = "run_results"
    __table_args__ = (Index("ix_run_results_outcome", "run_id", "outcome"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    run_id: Mapped[int] = mapped_column(ForeignKey("runs.id"), index=True)
    supplier: Mapped[str] = mapped_column(String(16), index=True)
    market: Mapped[str] = mapped_column(String(8))
    departure: Mapped[date] = mapped_column(Date)
    outcome: Mapped[str] = mapped_column(String(16))
    offers: Mapped[int] = mapped_column(Integer, default=0)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    digest: Mapped[str] = mapped_column(String(32), default="")
    reason: Mapped[str] = mapped_column(String(128), default="")

    # Both of these are readings the reliability report cannot get anywhere
    # else. A payload we already held says the supplier is serving a cache
    # back at us, and offers inside a payload that would not parse say the
    # feed has moved under our parser. Neither shows in a status code.
    fresh: Mapped[bool] = mapped_column(Boolean, default=True)
    problems: Mapped[int] = mapped_column(Integer, default=0)

    run: Mapped[RunRow] = relationship(back_populates="results")

    def __repr__(self) -> str:
        return f"<Result {self.supplier} {self.market} {self.outcome}>"
