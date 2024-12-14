"""Every table, imported here so the metadata is complete for a migration."""

from gussetline.persistence.models.desk import (
    CouponRow,
    RecommendationRow,
    RunResultRow,
    RunRow,
    TicketRow,
    WatchRow,
)
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
    "CouponRow",
    "FareRow",
    "InterlineRow",
    "LoadRow",
    "MileageRow",
    "ObservationRow",
    "ProvisionRow",
    "QuoteRow",
    "RateRow",
    "RecommendationRow",
    "RoutingRow",
    "RuleRow",
    "RunResultRow",
    "RunRow",
    "SectorRow",
    "SurchargeRow",
    "TaxRow",
    "TicketRow",
    "WatchRow",
]
