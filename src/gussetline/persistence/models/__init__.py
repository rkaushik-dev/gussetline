"""Every table, imported here so the metadata is complete for a migration."""

from gussetline.persistence.models.reference import (
    AirportRow,
    CarrierRow,
    InterlineRow,
    MileageRow,
    RateRow,
)

__all__ = [
    "AirportRow",
    "CarrierRow",
    "InterlineRow",
    "MileageRow",
    "RateRow",
]
