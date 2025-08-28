"""The agent's worklist, which is the one screen anybody looks at all day.

It reads and never writes. Taking a recommendation is a write and it already
has an endpoint of its own on the watches router, and putting the two together
would mean the screen an agent refreshes every thirty seconds is a route that
can change something.

The whole queue comes back in one response and is not paged. A desk has a few
hundred open recommendations, an agent works the top of the list, and paging
would let them page past the urgent ones on the first screen. The limit is
there for a caller that wants the top ten, not to make the response fit.

The scoring lives a layer down. This route reads the rows, hands them to the
queue, and turns what comes back into JSON, which is all a route should ever
be doing.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from gussetline.api import security
from gussetline.api.deps import get_unit
from gussetline.api.schemas import MoneyOut, PlaceOut, QueueOut
from gussetline.desk import queue as worklist
from gussetline.desk.recommend import Recommendation
from gussetline.desk.watch import Observation
from gussetline.persistence.deskmap import observation_row_to_reading, row_to_recommendation
from gussetline.persistence.models import ObservationRow, RecommendationRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork

router = APIRouter(prefix="/queue", tags=["queue"], dependencies=[Depends(security.reader)])

# How many readings per series the market check looks back over. The same
# handful the night run uses, for the same reason.
READINGS = 8


@router.get("", response_model=QueueOut)
def read_queue(
    work: UnitOfWork = Depends(get_unit),
    on: date | None = Query(default=None, description="Read it as of this day."),
    limit: int = Query(default=200, ge=1, le=1000),
) -> QueueOut:
    """The open recommendations, in the order they should be worked."""
    today = on or date.today()
    rows = _open_rows(work)
    made = [row_to_recommendation(r, r.watch.ticket.number, r.watch.series_key) for r in rows]
    departures = {row.watch.series_key: row.watch.departure for row in rows}
    histories = _histories(work, set(departures))

    places = worklist.build(made, today, departures, histories)
    currency = made[0].currency if made else "EUR"
    return QueueOut(
        as_of=today,
        open=len(places),
        urgent=len(worklist.urgent(places)),
        worth=MoneyOut(amount=str(worklist.worth_of(places, currency).amount), currency=currency),
        places=[_place_out(place) for place in places[:limit]],
    )


def _open_rows(work: UnitOfWork) -> list[RecommendationRow]:
    return list(
        work.session.scalars(
            select(RecommendationRow)
            .options(selectinload(RecommendationRow.watch).selectinload(WatchRow.ticket))
            .where(RecommendationRow.state == "open")
        ).all()
    )


def _histories(work: UnitOfWork, series: set[str]) -> dict[str, list[Observation]]:
    """The last few readings per series, for the market check.

    One query per series rather than one for all of them, because the limit
    is per series and a single query would have to window over them. A few
    hundred small indexed reads beat one clever one that nobody can read.
    """
    found: dict[str, list[Observation]] = {}
    for key in sorted(series):
        rows = work.session.scalars(
            select(ObservationRow)
            .where(ObservationRow.series_key == key)
            .order_by(ObservationRow.last_seen.desc())
            .limit(READINGS)
        ).all()
        found[key] = [observation_row_to_reading(row) for row in reversed(rows)]
    return found


def _place_out(place: worklist.Place) -> PlaceOut:
    made: Recommendation = place.recommendation
    return PlaceOut(
        ticket_number=made.ticket_number,
        series_key=made.series_key,
        raised_on=made.raised_on,
        expires_on=made.expires_on,
        benefit=MoneyOut(amount=str(made.benefit.amount), currency=made.currency),
        score=str(place.score),
        days_left=place.days_left,
        days_out=place.days_out,
        urgent=place.urgent,
        fare_moved=place.fare_moved,
        why=place.why(),
    )
