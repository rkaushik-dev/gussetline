"""Watches and the recommendations they raise.

The only writes in the whole surface are here, and they are both decisions a
person makes: take a recommendation or kill it. Neither changes a number, they
change a state and record who did it, which is the shape every write in this
service has.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query

from gussetline.api.deps import get_unit
from gussetline.api.schemas import DecisionIn, MoneyOut, RecommendationOut, WatchOut
from gussetline.errors import ConflictError, NotFoundError
from gussetline.persistence.models import RecommendationRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork

router = APIRouter(prefix="/watches", tags=["watches"])


def watch_out(row: WatchRow) -> WatchOut:
    return WatchOut(
        series_key=row.series_key,
        ticket_number=row.ticket.number,
        origin=row.origin,
        destination=row.destination,
        departure=row.departure,
        cabin=row.cabin,
        threshold=MoneyOut(amount=str(row.threshold_amount), currency=row.threshold_currency),
        active=row.active,
        retired_reason=row.retired_reason,
        checks=row.checks,
        last_checked=row.last_checked,
    )


def recommendation_out(row: RecommendationRow) -> RecommendationOut:
    return RecommendationOut(
        ticket_number=row.watch.ticket.number,
        series_key=row.watch.series_key,
        raised_on=row.raised_on,
        state=row.state,
        paid=MoneyOut(amount=str(row.paid_amount), currency=row.currency),
        new_fare=MoneyOut(amount=str(row.new_fare_amount), currency=row.currency),
        penalty=MoneyOut(amount=str(row.penalty_amount), currency=row.currency),
        benefit=MoneyOut(amount=str(row.benefit_amount), currency=row.currency),
        residual=row.residual,
        decided_on=row.decided_on,
        decided_by=row.decided_by,
    )


@router.get("", response_model=list[WatchOut])
def list_watches(
    on: date | None = Query(default=None),
    work: UnitOfWork = Depends(get_unit),
) -> list[WatchOut]:
    """Every live watch, closest departure first."""
    return [watch_out(row) for row in work.watches.live(on or date.today())]


@router.get("/ticket/{number}", response_model=list[WatchOut])
def watches_for_ticket(number: str, work: UnitOfWork = Depends(get_unit)) -> list[WatchOut]:
    found = work.watches.for_ticket(number)
    if not found:
        raise NotFoundError("no watches on that ticket", number=number)
    return [watch_out(row) for row in found]


def _recommendation(work: UnitOfWork, identifier: int) -> RecommendationRow:
    row = work.session.get(RecommendationRow, identifier)
    if row is None:
        raise NotFoundError("no such recommendation", id=identifier)
    return row


@router.get("/recommendations/{identifier}", response_model=RecommendationOut)
def read_recommendation(identifier: int, work: UnitOfWork = Depends(get_unit)) -> RecommendationOut:
    return recommendation_out(_recommendation(work, identifier))


@router.post("/recommendations/{identifier}/take", response_model=RecommendationOut)
def take_recommendation(
    identifier: int,
    decision: DecisionIn,
    work: UnitOfWork = Depends(get_unit),
) -> RecommendationOut:
    """The desk made the reissue. The only write that ends in a saving."""
    return _settle(work, identifier, "taken", decision)


@router.post("/recommendations/{identifier}/kill", response_model=RecommendationOut)
def kill_recommendation(
    identifier: int,
    decision: DecisionIn,
    work: UnitOfWork = Depends(get_unit),
) -> RecommendationOut:
    """The desk looked and said no. Recorded, and not a failure."""
    return _settle(work, identifier, "killed", decision)


def _settle(
    work: UnitOfWork, identifier: int, state: str, decision: DecisionIn
) -> RecommendationOut:
    row = _recommendation(work, identifier)
    if row.state != "open":
        raise ConflictError(
            "this recommendation has already been settled",
            state=row.state,
            by=row.decided_by,
        )
    row.state = state
    row.decided_on = date.today()
    row.decided_by = decision.by
    row.note = decision.note
    work.commit()
    return recommendation_out(row)
