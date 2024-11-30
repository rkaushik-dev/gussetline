"""Every table, imported here so the metadata is complete for a migration."""

from gussetline.persistence.models.reference import (
    AirportRow,
    CarrierRow,
    InterlineRow,
    MileageRow,
    RateRow,
)
from gussetline.persistence.models.tariff import (
    CombinabilityRow,
    FareRow,
    LoadRow,
    ProvisionRow,
    RoutingRow,
    RuleRow,
    SurchargeRow,
)

__all__ = [
    "AirportRow",
    "CarrierRow",
    "CombinabilityRow",
    "FareRow",
    "InterlineRow",
    "LoadRow",
    "MileageRow",
    "ProvisionRow",
    "RateRow",
    "RoutingRow",
    "RuleRow",
    "SurchargeRow",
]
