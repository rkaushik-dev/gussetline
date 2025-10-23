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

from gussetline.api import security
from gussetline.api.deps import get_unit
from gussetline.api.schemas import MoneyOut, PlaceOut, QueueOut
from gussetline.desk import queue as worklist
from gussetline.desk.recommend import Recommendation
from gussetline.persistence.book import open_book
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
    book = open_book(work, with_readings=True)
    made = book.recommendations

    places = worklist.build(made, today, book.departures, book.histories)
    currency = made[0].currency if made else "EUR"
    return QueueOut(
        as_of=today,
        open=len(places),
        urgent=len(worklist.urgent(places)),
        worth=MoneyOut(amount=str(worklist.worth_of(places, currency).amount), currency=currency),
        places=[_place_out(place) for place in places[:limit]],
    )


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
