"""Liveness and readiness, which are not the same question.

Liveness says the process is up. Readiness says it can reach the database,
which is what a load balancer should be asking, and answering the first when
the second was meant is how a pool of processes with no database behind them
stays in rotation.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import text

from gussetline.api.deps import get_settings, get_unit
from gussetline.config import Settings
from gussetline.persistence.unitofwork import UnitOfWork

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    """Up. Says nothing about whether anything else works."""
    return {"status": "up"}


@router.get("/ready")
def ready(
    response: Response,
    work: UnitOfWork = Depends(get_unit),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    """Up and able to reach storage."""
    try:
        work.session.execute(text("select 1"))
    except Exception as exc:
        response.status_code = 503
        return {"status": "not ready", "reason": type(exc).__name__}
    return {
        "status": "ready",
        "currency": settings.selling_currency,
        "airports": work.airports.count(),
        "observations": work.observations.count(),
    }
