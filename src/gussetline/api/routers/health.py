"""Liveness, readiness and fitness, which are three different questions.

Liveness says the process is up. Readiness says it can reach the database,
which is what a load balancer should be asking, and answering the first when
the second was meant is how a pool of processes with no database behind them
stays in rotation.

Fitness is the third one and nothing asks it automatically, which is the point.
A desk can be up, reachable, and completely useless: no tariff loaded, no rules
behind the fares, nothing collected since Tuesday. Every one of those answers
readiness with a cheerful yes, because the database is right there and it is
empty.

So `/ready` stays cheap and boolean, because something polls it every few
seconds and it must not read a table. `/fit` costs several counts and a date
comparison, it is for a person or a morning check, and it says which of the
things a desk needs are actually there.

The counts on readiness were a mistake and they are gone. They read two tables
on every poll, which on a busy desk is a query every second for a number
nobody was looking at, and they made a broken desk look healthy: two hundred
thousand observations is a reassuring number whether or not any of them arrived
this month.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import func, select, text

from gussetline.api.deps import get_settings, get_unit
from gussetline.config import Settings
from gussetline.persistence.models import ObservationRow, RunRow
from gussetline.persistence.unitofwork import UnitOfWork

router = APIRouter(tags=["health"])

# How long the desk may go without collecting before somebody should look.
QUIET_DAYS = 2


@router.get("/health")
def health() -> dict[str, str]:
    """Up. Says nothing about whether anything else works."""
    return {"status": "up"}


@router.get("/ready")
def ready(response: Response, work: UnitOfWork = Depends(get_unit)) -> dict[str, str]:
    """Up and able to reach storage. Cheap, because something polls it."""
    try:
        work.session.execute(text("select 1"))
    except Exception as exc:
        response.status_code = 503
        return {"status": "not ready", "reason": type(exc).__name__}
    return {"status": "ready"}


@router.get("/fit")
def fit(
    response: Response,
    work: UnitOfWork = Depends(get_unit),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    """Whether this desk can actually do its job, and what is missing if not.

    Answers 503 when something a desk cannot work without is absent, so a
    morning check can be one request. Every finding names the command that
    fixes it, because the person reading this at seven is not the person who
    wrote it.
    """
    found = checks(work)
    missing = [name for name, (ok, _) in found.items() if not ok]
    if missing:
        response.status_code = 503

    return {
        "status": "fit" if not missing else "unfit",
        "currency": settings.selling_currency,
        "missing": missing,
        "checks": {name: {"ok": ok, "detail": detail} for name, (ok, detail) in found.items()},
    }


def checks(work: UnitOfWork) -> dict[str, tuple[bool, str]]:
    """What a desk needs, each with what to do about it.

    Public, because the command line asks the same questions and two copies
    of this list would answer differently within a month.

    Ordered the way somebody would fix them: without fares nothing prices, and
    a rule with no fare pointing at it is harmless, so the tariff comes first.
    """
    fares = work.tariff.count_fares()
    rules = work.rules.count()
    joins = work.combinability.count()
    latest = work.session.scalar(select(func.max(RunRow.started_at)))
    last_seen = work.session.scalar(select(func.max(ObservationRow.last_seen)))

    return {
        "tariff": (
            fares > 0,
            f"{fares} fares loaded" if fares else "nothing to price against, run load fares",
        ),
        "rules": (
            rules > 0,
            f"{rules} rules loaded" if rules else "no rule permits a change, run load rules",
        ),
        "combinability": (
            joins > 0,
            f"{joins} records" if joins else "every round trip refuses, run load combinability",
        ),
        "collecting": _recently(latest, "no run recorded, run collect run"),
        "observations": _recently(last_seen, "nothing collected, run collect run"),
    }


def _recently(when: datetime | None, missing: str) -> tuple[bool, str]:
    """Whether something happened recently enough to count as still happening.

    A desk that last collected in March is not collecting, however many rows
    it holds. The count is the reassuring number and the date is the true one.
    """
    if when is None:
        return (False, missing)
    quiet = (date.today() - when.date()).days
    if quiet > QUIET_DAYS:
        return (False, f"last was {when:%Y-%m-%d}, {quiet} days ago")
    return (True, f"last was {when:%Y-%m-%d}")


def quiet_before(today: date, days: int = QUIET_DAYS) -> date:
    """The day a desk has to have collected since. For the morning check."""
    return today - timedelta(days=days)
