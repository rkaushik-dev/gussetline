"""Repositories, which are the only place a query is written.

Everything above this layer asks for objects and gets objects. That is what
lets the pricing engine be tested against a dictionary and run against a
million row table without noticing, and it is what stops a query appearing in
a report module where nobody will ever index it.

The observation repository is the one with real behaviour in it. Saving an
offer that has been seen before is not an insert, it is a stamp move on the
row that is already there, and getting that wrong is the difference between a
table that grows by a million rows a year and one that grows by nine.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from gussetline.carriers import Carrier
from gussetline.errors import NotFoundError
from gussetline.geography import Airport
from gussetline.offers import Offer
from gussetline.persistence.mappers import (
    airport_to_row,
    carrier_to_row,
    fare_to_row,
    offer_to_row,
    row_to_airport,
    row_to_carrier,
    row_to_fare,
    row_to_offer,
)
from gussetline.persistence.models import (
    AirportRow,
    CarrierRow,
    FareRow,
    LoadRow,
    ObservationRow,
    QuoteRow,
    TicketRow,
    WatchRow,
)
from gussetline.tariff import Fare


class AirportRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, airport: Airport) -> AirportRow:
        row = self.session.get(AirportRow, airport.code)
        if row is None:
            row = airport_to_row(airport)
            self.session.add(row)
        return row

    def get(self, code: str) -> Airport:
        row = self.session.get(AirportRow, code.upper())
        if row is None:
            raise NotFoundError("no such airport", code=code)
        return row_to_airport(row)

    def in_city(self, city: str) -> list[Airport]:
        rows = self.session.scalars(
            select(AirportRow).where(AirportRow.city == city.upper()).order_by(AirportRow.code)
        ).all()
        return [row_to_airport(r) for r in rows]

    def count(self) -> int:
        return self.session.scalar(select(func.count()).select_from(AirportRow)) or 0


class CarrierRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, carrier: Carrier) -> CarrierRow:
        row = self.session.get(CarrierRow, carrier.code)
        if row is None:
            row = carrier_to_row(carrier)
            self.session.add(row)
        return row

    def get(self, code: str) -> Carrier:
        row = self.session.get(CarrierRow, code.upper())
        if row is None:
            raise NotFoundError("no such carrier", code=code)
        return row_to_carrier(row)

    def all(self) -> list[Carrier]:
        rows = self.session.scalars(select(CarrierRow).order_by(CarrierRow.code)).all()
        return [row_to_carrier(r) for r in rows]


class TariffRepository:
    """Fares, grouped by the load they came in on."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def open_load(
        self, carrier: str, filename: str, digest: str, loaded_at: datetime, effective: date
    ) -> LoadRow:
        load = LoadRow(
            carrier=carrier.upper(),
            filename=filename,
            digest=digest,
            loaded_at=loaded_at,
            effective_from=effective,
        )
        self.session.add(load)
        self.session.flush()
        return load

    def add_fares(self, load: LoadRow, fares: list[Fare]) -> int:
        for fare in fares:
            load.fares.append(fare_to_row(fare, load.id))
        load.row_count = len(load.fares)
        return load.row_count

    def search(
        self, carrier: str, origin: str, destination: str, on: date | None = None
    ) -> list[Fare]:
        statement = select(FareRow).where(
            FareRow.carrier == carrier.upper(),
            FareRow.origin == origin.upper(),
            FareRow.destination == destination.upper(),
        )
        if on is not None:
            statement = statement.where(FareRow.effective <= on, FareRow.discontinue >= on)
        rows = self.session.scalars(statement.order_by(FareRow.amount)).all()
        return [row_to_fare(r) for r in rows]

    def loads_for(self, carrier: str) -> list[LoadRow]:
        return list(
            self.session.scalars(
                select(LoadRow)
                .where(LoadRow.carrier == carrier.upper())
                .order_by(LoadRow.loaded_at.desc())
            ).all()
        )

    def retire(self, load: LoadRow) -> None:
        load.retired = True

    def count_fares(self) -> int:
        return self.session.scalar(select(func.count()).select_from(FareRow)) or 0


class ObservationRepository:
    """Where the collectors write, and the only place with an upsert in it."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, offer: Offer) -> ObservationRow:
        """Insert a new offer, or move the stamp on one we have seen before."""
        row, _ = self.upsert(offer)
        return row

    def upsert(self, offer: Offer) -> tuple[ObservationRow, bool]:
        """The row, and whether it had to be created.

        The caller is told rather than left to work it out from the row,
        because a row that has just been added has no defaults on it yet and
        reading the sighting count off it gives None until the next flush.
        """
        row = offer_to_row(offer)
        existing = self.session.scalars(
            select(ObservationRow).where(
                ObservationRow.series_key == row.series_key,
                ObservationRow.fingerprint == row.fingerprint,
            )
        ).one_or_none()
        if existing is None:
            row.sightings = 1
            self.session.add(row)
            return row, True
        existing.last_seen = max(existing.last_seen, offer.observed_at)
        existing.first_seen = min(existing.first_seen, offer.observed_at)
        existing.sightings += 1
        existing.seats = offer.seats
        return existing, False

    def save_all(self, offers: list[Offer]) -> tuple[int, int]:
        """Returns how many were new and how many were repeats."""
        new = repeats = 0
        for offer in offers:
            _, was_new = self.upsert(offer)
            if was_new:
                new += 1
            else:
                repeats += 1
        return new, repeats

    def series(self, series_key: str) -> list[Offer]:
        rows = self.session.scalars(
            select(ObservationRow)
            .where(ObservationRow.series_key == series_key)
            .options(selectinload(ObservationRow.sectors), selectinload(ObservationRow.taxes))
            .order_by(ObservationRow.total_amount)
        ).all()
        return [row_to_offer(r) for r in rows]

    def cheapest_in(self, series_key: str) -> Offer | None:
        found = self.series(series_key)
        return found[0] if found else None

    def seen_since(self, when: datetime) -> list[ObservationRow]:
        return list(
            self.session.scalars(
                select(ObservationRow)
                .where(ObservationRow.last_seen >= when)
                .order_by(ObservationRow.last_seen)
            ).all()
        )

    def count(self) -> int:
        return self.session.scalar(select(func.count()).select_from(ObservationRow)) or 0


class WatchRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def live(self, on: date) -> list[WatchRow]:
        """Every watch still worth checking, closest departure first."""
        return list(
            self.session.scalars(
                select(WatchRow)
                .where(WatchRow.active, WatchRow.departure >= on)
                .order_by(WatchRow.departure)
            ).all()
        )

    def for_ticket(self, number: str) -> list[WatchRow]:
        return list(
            self.session.scalars(
                select(WatchRow).join(TicketRow).where(TicketRow.number == number)
            ).all()
        )

    def retire_flown(self, on: date) -> int:
        """Close the watches whose departures have gone. Returns how many."""
        rows = self.session.scalars(
            select(WatchRow).where(WatchRow.active, WatchRow.departure < on)
        ).all()
        for row in rows:
            row.active = False
            row.retired_reason = "departure has gone"
        return len(rows)


class QuoteRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, row: QuoteRow) -> QuoteRow:
        existing = self.session.scalars(
            select(QuoteRow).where(
                QuoteRow.origin == row.origin,
                QuoteRow.destination == row.destination,
                QuoteRow.departure == row.departure,
                QuoteRow.observed_on == row.observed_on,
            )
        ).one_or_none()
        if existing is None:
            self.session.add(row)
            return row
        existing.amount = row.amount
        existing.sold_out = row.sold_out
        return existing

    def cheapest_between(
        self, origin: str, destination: str, first: date, last: date
    ) -> QuoteRow | None:
        return self.session.scalars(
            select(QuoteRow)
            .where(
                QuoteRow.origin == origin.upper(),
                QuoteRow.destination == destination.upper(),
                QuoteRow.departure >= first,
                QuoteRow.departure <= last,
                ~QuoteRow.sold_out,
            )
            .order_by(QuoteRow.amount)
        ).first()
