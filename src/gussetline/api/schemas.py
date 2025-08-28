"""The shapes that cross the wire, which are not the domain objects.

Two reasons for the copy, and both have been paid for.

An amount is a string on the wire and a Decimal in the domain. JSON has one
number type and it is a float, so a fare sent as a number arrives as a float
somewhere, and the somewhere is always a client the desk does not control.

And the wire shape is a promise. The domain gets refactored, fields move
between objects, and a schema that was the domain object would break every
client each time. This layer is the thing that does not change, and when it
has to, it changes on purpose.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field

from gussetline.money import Money


class MoneyOut(BaseModel):
    """An amount and a currency. The amount is a string, always."""

    model_config = ConfigDict(frozen=True)

    amount: str
    currency: str

    @classmethod
    def of(cls, money: Money) -> MoneyOut:
        return cls(amount=str(money.amount), currency=money.currency)

    def to_money(self) -> Money:
        return Money(Decimal(self.amount), self.currency)


class SectorOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    carrier: str
    flight_number: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    booking_class: str
    cabin: str


class TaxOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    code: str
    amount: MoneyOut
    carrier_imposed: bool = False


class OfferOut(BaseModel):
    """One observed offer, as a screen wants to draw it."""

    model_config = ConfigDict(frozen=True)

    fingerprint: str
    source: str
    origin: str
    destination: str
    departure: date
    cabin: str
    fare_bases: list[str]
    base: MoneyOut
    taxes: list[TaxOut] = Field(default_factory=list)
    surcharges: MoneyOut
    total: MoneyOut
    quoted_total: MoneyOut | None = None
    adds_up: bool = True
    seats: int | None = None
    sectors: list[SectorOut] = Field(default_factory=list)
    first_seen: datetime
    last_seen: datetime
    sightings: int = 1


class SeriesOut(BaseModel):
    """A price series, cheapest first, with what the desk paid beside it."""

    model_config = ConfigDict(frozen=True)

    series_key: str
    origin: str
    destination: str
    departure: date
    cabin: str
    offers: list[OfferOut] = Field(default_factory=list)
    cheapest: MoneyOut | None = None


class PriceRequest(BaseModel):
    """What to price. Sectors in, a construction out."""

    model_config = ConfigDict(frozen=True)

    ticketing: date
    selling_currency: str = "EUR"
    breaks: list[int] | None = None
    sectors: list[SectorIn]


class SectorIn(BaseModel):
    model_config = ConfigDict(frozen=True)

    carrier: str
    flight_number: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    booking_class: str = "Y"
    cabin: str = "Y"
    operating_carrier: str | None = None


class ComponentOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    board_point: str
    off_point: str
    fare_basis: str
    nuc: str
    surcharges: list[ChargeOut] = Field(default_factory=list)


class ChargeOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    reason: str
    nuc: str


class PriceOut(BaseModel):
    """A finished price and the trail that built it."""

    model_config = ConfigDict(frozen=True)

    base_fare: MoneyOut
    total_nuc: str
    fare_calculation: str
    issuable: bool
    refusals: list[str] = Field(default_factory=list)
    components: list[ComponentOut] = Field(default_factory=list)
    trail: list[TrailStepOut] = Field(default_factory=list)


class TrailStepOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    stage: str
    verdict: str
    message: str


class WatchOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    series_key: str
    ticket_number: str
    origin: str
    destination: str
    departure: date
    cabin: str
    threshold: MoneyOut
    active: bool
    retired_reason: str = ""
    checks: int = 0
    last_checked: datetime | None = None


class RecommendationOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    ticket_number: str
    series_key: str
    raised_on: date
    state: str
    paid: MoneyOut
    new_fare: MoneyOut
    penalty: MoneyOut
    benefit: MoneyOut
    residual: str
    decided_on: date | None = None
    decided_by: str = ""


class DecisionIn(BaseModel):
    """Taking or killing a recommendation. Both need a name against them."""

    model_config = ConfigDict(frozen=True)

    by: str = Field(min_length=1, max_length=32)
    note: str = Field(default="", max_length=256)


class SupplierOut(BaseModel):
    model_config = ConfigDict(frozen=True)

    name: str
    host: str
    capabilities: list[str]
    cost_per_call: str
    breaker: str = "closed"
    quota_used: int = 0
    quota_limit: int = 0


class PageOut(BaseModel):
    """A page of anything, with the cursor to ask for the next one."""

    model_config = ConfigDict(frozen=True)

    items: list[dict] = Field(default_factory=list)
    next_cursor: str | None = None
    total: int | None = None


PriceRequest.model_rebuild()
ComponentOut.model_rebuild()
PriceOut.model_rebuild()


class PlaceOut(BaseModel):
    """One recommendation as the worklist screen shows it."""

    model_config = ConfigDict(frozen=True)

    ticket_number: str
    series_key: str
    raised_on: date
    expires_on: date
    benefit: MoneyOut
    # A string, like every other amount here. The score is a decimal and a
    # float would round it differently in every client that read it.
    score: str
    days_left: int
    days_out: int
    urgent: bool
    fare_moved: bool
    why: str


class QueueOut(BaseModel):
    """The whole worklist, with the three numbers the screen shows at the top."""

    model_config = ConfigDict(frozen=True)

    as_of: date
    open: int
    urgent: int
    worth: MoneyOut
    places: list[PlaceOut]
