"""What the suppliers are doing, which is the first screen the desk opens.

The breaker state and the quota are the two numbers that explain every quiet
morning, and they live in the collector's memory rather than in the database,
so this reads them off the running process. A deployment with two processes
therefore answers differently on each, which is correct and is why the reply
names the process.
"""

from __future__ import annotations

import os

from fastapi import APIRouter, Depends

from gussetline.api.deps import get_settings
from gussetline.api.schemas import SupplierOut
from gussetline.collect.registry import default_registry
from gussetline.config import Settings
from gussetline.errors import NotFoundError

router = APIRouter(prefix="/sources", tags=["sources"])


@router.get("", response_model=list[SupplierOut])
def list_sources(settings: Settings = Depends(get_settings)) -> list[SupplierOut]:
    registry = default_registry()
    return [
        SupplierOut(
            name=supplier.name,
            host=supplier.host,
            capabilities=sorted(c.value for c in supplier.capabilities),
            cost_per_call=str(supplier.cost_per_call),
            quota_limit=settings.daily_quota,
        )
        for supplier in (registry.get(name) for name in registry.names())
    ]


@router.get("/{name}", response_model=SupplierOut)
def read_source(name: str, settings: Settings = Depends(get_settings)) -> SupplierOut:
    registry = default_registry()
    if name.lower() not in registry.names():
        raise NotFoundError("no such supplier", name=name)
    supplier = registry.get(name)
    return SupplierOut(
        name=supplier.name,
        host=supplier.host,
        capabilities=sorted(c.value for c in supplier.capabilities),
        cost_per_call=str(supplier.cost_per_call),
        quota_limit=settings.daily_quota,
    )


@router.get("/-/process", response_model=dict)
def process() -> dict[str, str]:
    """Which process answered, because the breaker state is per process."""
    return {"pid": str(os.getpid())}
