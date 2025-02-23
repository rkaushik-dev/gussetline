"""Pricing a journey on demand, which is what the audit screen calls.

The interesting thing this returns is not the number, it is the trail. An
agent looking at a supplier quote that is forty euro high wants to know which
step made the difference, and the trail says so line by line. So the trail is
part of the response and not a debug flag, and it is the part clients actually
render.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from gussetline.api import security
from gussetline.api.deps import get_settings, get_unit
from gussetline.api.schemas import (
    ChargeOut,
    ComponentOut,
    MoneyOut,
    PriceOut,
    PriceRequest,
    TrailStepOut,
)
from gussetline.config import Settings
from gussetline.distance import default_mileage_table
from gussetline.engine import Tariffs, price
from gussetline.exchange import Converter, default_roe_table
from gussetline.flights import Cabin, Segment
from gussetline.itinerary import Itinerary
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.tariff import TariffStore

router = APIRouter(prefix="/pricing", tags=["pricing"], dependencies=[Depends(security.reader)])


@router.post("", response_model=PriceOut)
def price_journey(
    request: PriceRequest,
    work: UnitOfWork = Depends(get_unit),
    settings: Settings = Depends(get_settings),
) -> PriceOut:
    """Price the sectors given, from the tariff, and show the working."""
    segments = [
        Segment(
            marketing_carrier=s.carrier,
            flight_number=s.flight_number,
            origin=s.origin,
            destination=s.destination,
            departure=s.departure,
            arrival=s.arrival,
            booking_class=s.booking_class,
            cabin=Cabin(s.cabin) if s.cabin in {c.value for c in Cabin} else Cabin.ECONOMY,
            operating_carrier=s.operating_carrier,
            day_change=max(0, (s.arrival.date() - s.departure.date()).days),
        )
        for s in request.sectors
    ]
    itinerary = Itinerary(segments)

    store = TariffStore()
    for fare in work.tariff.search(
        segments[0].marketing_carrier,
        itinerary.origin,
        itinerary.destination,
        on=request.ticketing,
    ):
        store.add(fare)

    tariffs = Tariffs(fares=store, miles=default_mileage_table())
    converter = Converter(default_roe_table())

    result = price(
        itinerary,
        tariffs,
        converter,
        request.ticketing,
        request.selling_currency or settings.selling_currency,
        breaks=tuple(request.breaks) if request.breaks is not None else None,
    )

    return PriceOut(
        base_fare=MoneyOut.of(result.base_fare),
        total_nuc=str(result.total_nuc),
        fare_calculation=result.line,
        issuable=result.issuable,
        refusals=list(result.refusals),
        components=[
            ComponentOut(
                board_point=c.board_point,
                off_point=c.off_point,
                fare_basis=c.fare.fare_basis if c.fare else "",
                nuc=str(c.total_nuc),
                surcharges=[
                    ChargeOut(reason=charge.reason, nuc=str(charge.nuc)) for charge in c.charges
                ],
            )
            for c in result.construction.components
        ],
        trail=[
            TrailStepOut(stage=s.stage.value, verdict=s.verdict.value, message=s.message)
            for s in result.trace
        ],
    )
