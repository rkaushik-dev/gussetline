"""Every table, imported here so the metadata is complete for a migration."""

from gussetline.persistence.models.market import (
    ObservationRow,
    QuoteRow,
    SectorRow,
    TaxRow,
)
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
    "ObservationRow",
    "ProvisionRow",
    "QuoteRow",
    "RateRow",
    "RoutingRow",
    "RuleRow",
    "SectorRow",
    "SurchargeRow",
    "TaxRow",
]
