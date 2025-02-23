"""One error shape, so a client never has to guess which kind it got.

Every failure that leaves this service looks the same: a code, a sentence, and
the details the domain attached. The code is the one from the error taxonomy
and not an invented HTTP-flavoured name, because the desk's own screens log it
and a code that changes when the transport changes is a code nobody can grep
for.

The status is derived from the exception class in exactly one table below.
Deriving it at each raise site is how a not found ends up as a five hundred on
one route and a four hundred on another, and the client that has to handle
both stops trusting either.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from gussetline.api.security import Forbidden, Unauthorised
from gussetline.errors import (
    BudgetExhausted,
    ConfigError,
    ConflictError,
    ConstructionError,
    GussetlineError,
    NotFoundError,
    SupplierBlocked,
    SupplierError,
    TariffError,
    ValidationError,
)

# The one place an exception becomes a status code.
STATUS_FOR: tuple[tuple[type[GussetlineError], int], ...] = (
    (Unauthorised, 401),
    (Forbidden, 403),
    (NotFoundError, 404),
    (ConflictError, 409),
    (ValidationError, 422),
    (TariffError, 422),
    (ConstructionError, 422),
    (BudgetExhausted, 429),
    (SupplierBlocked, 502),
    (SupplierError, 502),
    (ConfigError, 500),
)


def status_for(exc: GussetlineError) -> int:
    """The status one of our errors becomes. Anything unmapped is a 500."""
    for kind, status in STATUS_FOR:
        if isinstance(exc, kind):
            return status
    return 500


def body_for(exc: GussetlineError, request_id: str = "") -> dict[str, Any]:
    payload = exc.as_dict()
    if request_id:
        payload["request_id"] = request_id
    return payload


def install(app: FastAPI) -> None:
    """Wire the handler so no route has to catch anything itself."""

    @app.exception_handler(GussetlineError)
    async def _handle(request: Request, exc: GussetlineError) -> JSONResponse:
        request_id = getattr(request.state, "request_id", "")
        return JSONResponse(
            status_code=status_for(exc),
            content=body_for(exc, request_id),
            headers={"X-Request-Id": request_id} if request_id else None,
        )
