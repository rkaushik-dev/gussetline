"""What a route is allowed to ask for.

Two things and no more: a unit of work, and the settings. A route that needs
anything else is a route with logic in it, and the logic belongs a layer down
where it can be tested without a client.
"""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Request

from gussetline.config import Settings
from gussetline.persistence.unitofwork import Database, UnitOfWork


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_database(request: Request) -> Database:
    return request.app.state.database


def get_unit(database: Database = Depends(get_database)) -> Iterator[UnitOfWork]:
    """One transaction per request, rolled back unless the route commits."""
    with database.unit() as work:
        yield work
