"""The application factory, which takes its dependencies rather than making them.

Nothing here reaches for a global. The settings and the database are passed
in, which is what lets a test build the whole application against an in memory
database in one line, and it is also what makes the two deployments the desk
runs, one against the live database and one against last night's copy, a
configuration difference rather than a code branch.

Every request gets an id. It goes on the response header, into the error body
and into anything the request logs, so a screenshot of a failure from the desk
is enough to find the request in the log without asking what time it was.
"""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, Response

from gussetline.api import errors
from gussetline.api.routers import health, observations, pricing, sources, watches
from gussetline.api.security import KeyRing
from gussetline.config import Settings
from gussetline.persistence.unitofwork import Database

TITLE = "gussetline"
VERSION = "0.1.0"


def create_app(settings: Settings, database: Database, keyring: KeyRing | None = None) -> FastAPI:
    """Build the application around one settings object and one database."""
    app = FastAPI(
        title=TITLE,
        version=VERSION,
        summary="Fare audit and reprice desk",
        docs_url="/docs",
        openapi_url="/openapi.json",
    )

    app.state.settings = settings
    app.state.database = database
    # No ring means no authentication, which is what a test and a laptop want.
    app.state.keyring = keyring

    @app.middleware("http")
    async def _request_id(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        given = request.headers.get("X-Request-Id", "")
        request.state.request_id = given or uuid.uuid4().hex[:16]
        response = await call_next(request)
        response.headers["X-Request-Id"] = request.state.request_id
        return response

    errors.install(app)

    app.include_router(health.router)
    app.include_router(observations.router)
    app.include_router(pricing.router)
    app.include_router(sources.router)
    app.include_router(watches.router)

    return app


def development_app() -> FastAPI:
    """An application against whatever the environment says, for uvicorn."""
    from gussetline.config import from_env

    settings = from_env()
    return create_app(settings, Database(settings))
