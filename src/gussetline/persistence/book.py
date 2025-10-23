"""Reading the desk's open book, which three callers were each doing alone.

The night run, the queue command and the worklist endpoint all needed the same
three things: the recommendations still open, the departure behind each of
them, and the last few readings on their series. All three wrote it out, and
the three had already drifted in the way that matters most: two of them loaded
the ticket eagerly and one did not, so the third emitted a query per row and
nobody noticed until the book had two hundred rows in it.

Everything here loads what it hands back. A recommendation reaches its ticket
through its watch, and a caller that gets a row without that relationship
loaded either trips over a detached instance or quietly issues a query per row.
Neither is something a caller should have to know about, so it is not their
decision.

The readings are optional and separate. The night run wants a handful per
series and the queue wants the same handful, but a savings report over a year
wants none at all, and loading them for it would be several thousand queries
for something nobody reads.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import selectinload

from gussetline.desk.recommend import Recommendation
from gussetline.desk.watch import Observation
from gussetline.persistence.deskmap import observation_row_to_reading, row_to_recommendation
from gussetline.persistence.models import (
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import UnitOfWork

# How many readings per series a caller gets when it asks for them. The same
# handful everywhere, because two callers reading different depths of history
# answer the market moved question differently on the same data.
READINGS = 8


@dataclass
class Book:
    """The open recommendations and what a caller needs to rank them."""

    recommendations: list[Recommendation] = field(default_factory=list)
    departures: dict[str, date] = field(default_factory=dict)
    histories: dict[str, list[Observation]] = field(default_factory=dict)

    @property
    def series(self) -> list[str]:
        return sorted(self.departures)

    def __len__(self) -> int:
        return len(self.recommendations)

    def __str__(self) -> str:
        return f"{len(self.recommendations)} open over {len(self.departures)} series"


def open_book(work: UnitOfWork, with_readings: bool = False) -> Book:
    """Every recommendation still open, with its ticket and watch already in.

    ``with_readings`` costs a query per series and buys the market moved
    check. The night run and the worklist want it; a report that only adds up
    money does not.
    """
    rows = list(
        work.session.scalars(
            select(RecommendationRow)
            .options(
                selectinload(RecommendationRow.watch)
                .selectinload(WatchRow.ticket)
                .selectinload(TicketRow.coupons)
            )
            .where(RecommendationRow.state == "open")
            .order_by(RecommendationRow.raised_on)
        ).all()
    )

    book = Book(
        recommendations=[
            row_to_recommendation(row, row.watch.ticket.number, row.watch.series_key)
            for row in rows
        ],
        departures={row.watch.series_key: row.watch.departure for row in rows},
    )
    if with_readings:
        book.histories = readings_for(work, book.series)
    return book


def readings_for(work: UnitOfWork, series: list[str]) -> dict[str, list[Observation]]:
    """The last few readings per series, oldest first, one entry per poll.

    Oldest first because everything that reads a history reads the tail of
    it, and one query per series rather than one for all of them because the
    limit is per series and a single query would have to window over them.

    Per poll and not per row, which is the part that matters. A stored
    observation is a price: the table is keyed on content, so a market that
    sat at the same number for three mornings is one row with a sighting
    count of three, and handing back one reading tells a hysteresis the price
    has been seen once. That is the difference between a drop that has held
    for three days and one seen this morning, and every caller here wants the
    first reading.
    """
    found: dict[str, list[Observation]] = {}
    for key in series:
        rows = work.session.scalars(
            select(ObservationRow)
            .where(ObservationRow.series_key == key)
            .order_by(ObservationRow.last_seen.desc())
            .limit(READINGS)
        ).all()
        polls: list[Observation] = []
        for row in reversed(rows):
            polls.extend([observation_row_to_reading(row)] * max(row.sightings, 1))
        found[key] = polls[-READINGS:]
    return found


def raised_between(work: UnitOfWork, first: date, last: date) -> list[Recommendation]:
    """Every recommendation raised in a window, whatever became of it.

    On the day it was raised and not the day it was settled, because a
    recommendation belongs to the month the desk found it in. A report keyed
    on the settlement moves rows between months every time an agent works
    through a backlog.
    """
    rows = work.session.scalars(
        select(RecommendationRow)
        .options(selectinload(RecommendationRow.watch).selectinload(WatchRow.ticket))
        .where(RecommendationRow.raised_on >= first, RecommendationRow.raised_on <= last)
        .order_by(RecommendationRow.raised_on)
    ).all()
    return [
        row_to_recommendation(row, row.watch.ticket.number, row.watch.series_key) for row in rows
    ]
