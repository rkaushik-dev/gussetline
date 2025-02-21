"""Reading what the collectors saw.

Read only, on purpose. Nothing writes an observation through here, because an
observation is a reading of a market and the only thing entitled to make one
is a collector that actually asked a supplier. A route that let a person post
one would put a number into the savings report that nobody ever saw.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query

from gussetline.api.deps import get_unit
from gussetline.api.schemas import MoneyOut, OfferOut, SectorOut, SeriesOut, TaxOut
from gussetline.errors import NotFoundError
from gussetline.offers import Offer
from gussetline.persistence.mappers import series_key_of
from gussetline.persistence.unitofwork import UnitOfWork

router = APIRouter(prefix="/observations", tags=["observations"])


def offer_out(offer: Offer, sightings: int = 1) -> OfferOut:
    return OfferOut(
        fingerprint=offer.fingerprint(),
        source=offer.source,
        origin=offer.origin,
        destination=offer.destination,
        departure=offer.itinerary.departure.date(),
        cabin=offer.cabin.value,
        fare_bases=list(offer.fare_bases),
        base=MoneyOut.of(offer.breakdown.base),
        taxes=[
            TaxOut(
                code=t.code,
                amount=MoneyOut.of(t.amount),
                carrier_imposed=t.carrier_imposed,
            )
            for t in offer.breakdown.taxes
        ],
        surcharges=MoneyOut.of(offer.breakdown.surcharge_total),
        total=MoneyOut.of(offer.total),
        quoted_total=(
            MoneyOut.of(offer.breakdown.quoted_total)
            if offer.breakdown.quoted_total is not None
            else None
        ),
        adds_up=offer.breakdown.adds_up,
        seats=offer.seats,
        sectors=[
            SectorOut(
                carrier=s.marketing_carrier,
                flight_number=s.flight_number,
                origin=s.origin,
                destination=s.destination,
                departure=s.departure,
                arrival=s.arrival,
                booking_class=s.booking_class,
                cabin=s.cabin.value,
            )
            for s in offer.itinerary.segments
        ],
        first_seen=offer.observed_at,
        last_seen=offer.observed_at,
        sightings=sightings,
    )


@router.get("/series/{series_key}", response_model=SeriesOut)
def read_series(series_key: str, work: UnitOfWork = Depends(get_unit)) -> SeriesOut:
    """Every offer in one series, cheapest first."""
    offers = work.observations.series(series_key)
    if not offers:
        raise NotFoundError("no observations in that series", series_key=series_key)
    first = offers[0]
    return SeriesOut(
        series_key=series_key,
        origin=first.origin,
        destination=first.destination,
        departure=first.itinerary.departure.date(),
        cabin=first.cabin.value,
        offers=[offer_out(o) for o in offers],
        cheapest=MoneyOut.of(first.total),
    )


@router.get("/market", response_model=SeriesOut)
def read_market(
    origin: str = Query(min_length=3, max_length=3),
    destination: str = Query(min_length=3, max_length=3),
    departure: date = Query(),
    cabin: str = Query(default="Y", min_length=1, max_length=1),
    work: UnitOfWork = Depends(get_unit),
) -> SeriesOut:
    """The same thing, addressed the way a person would say it."""
    key = series_key_of(origin.upper(), destination.upper(), departure, cabin.upper())
    return read_series(key, work)
