"""Turning domain objects into rows and back, in one place.

The domain does not know the database exists. That is worth the cost of this
file, because it is the reason the fare construction can be tested with three
lines of setup instead of a schema, and it is the reason a fare read out of a
fixed width payload and one read out of the fares table are the same object
by the time anything prices them.

Both directions are here together on purpose. A mapper split across two files
drifts: somebody adds a column, fills it on the way in, and forgets the way
out, and the field comes back empty for a year without anybody noticing
because nothing reads it until the day something does.
"""

from __future__ import annotations

from datetime import date, datetime

from gussetline import series
from gussetline.carriers import Alliance, Carrier
from gussetline.collect.registry import Outcome
from gussetline.collect.run import Result, RunReport
from gussetline.collect.schedule import Market, Task
from gussetline.combinability import Combinability
from gussetline.flights import Cabin, Segment
from gussetline.geography import Airport
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Baggage, Offer, PriceBreakdown
from gussetline.persistence.models import (
    AirportRow,
    CarrierRow,
    CombinabilityRow,
    FareRow,
    ObservationRow,
    RunResultRow,
    RunRow,
    SectorRow,
    TaxRow,
)
from gussetline.tariff import Fare, FareType, OwRt
from gussetline.taxes import AppliedTax


def airport_to_row(airport: Airport) -> AirportRow:
    return AirportRow(
        code=airport.code,
        name=airport.name,
        city=airport.city,
        country=airport.country,
        latitude=airport.latitude,
        longitude=airport.longitude,
        utc_offset_minutes=airport.utc_offset_minutes,
    )


def row_to_airport(row: AirportRow) -> Airport:
    return Airport(
        code=row.code,
        name=row.name,
        city=row.city,
        country=row.country,
        latitude=row.latitude,
        longitude=row.longitude,
        utc_offset_minutes=row.utc_offset_minutes,
    )


def carrier_to_row(carrier: Carrier) -> CarrierRow:
    return CarrierRow(
        code=carrier.code,
        name=carrier.name,
        country=carrier.country,
        alliance=carrier.alliance.value,
        low_cost=carrier.low_cost,
    )


def row_to_carrier(row: CarrierRow) -> Carrier:
    return Carrier(
        code=row.code,
        name=row.name,
        country=row.country,
        alliance=Alliance(row.alliance),
        low_cost=row.low_cost,
    )


def fare_to_row(fare: Fare, load_id: int) -> FareRow:
    return FareRow(
        load_id=load_id,
        carrier=fare.carrier,
        origin=fare.origin,
        destination=fare.destination,
        fare_basis=fare.fare_basis,
        amount=fare.amount.amount,
        currency=fare.amount.currency,
        owrt=fare.owrt.value,
        global_indicator=fare.global_indicator.value,
        rule_number=fare.rule_number,
        routing_number=fare.routing_number,
        tariff_number=fare.tariff_number,
        footnote=fare.footnote,
        cabin=fare.cabin.value,
        fare_type=fare.fare_type.value,
        effective=fare.effective,
        discontinue=fare.discontinue,
        first_travel=fare.first_travel,
        last_travel=fare.last_travel,
    )


def row_to_fare(row: FareRow) -> Fare:
    return Fare(
        carrier=row.carrier,
        origin=row.origin,
        destination=row.destination,
        fare_basis=row.fare_basis,
        amount=Money(row.amount, row.currency),
        owrt=OwRt(row.owrt),
        global_indicator=GlobalIndicator(row.global_indicator),
        rule_number=row.rule_number,
        routing_number=row.routing_number,
        tariff_number=row.tariff_number,
        footnote=row.footnote,
        cabin=Cabin(row.cabin),
        fare_type=FareType(row.fare_type),
        effective=row.effective,
        discontinue=row.discontinue,
        first_travel=row.first_travel,
        last_travel=row.last_travel,
    )


def segment_to_row(segment: Segment, position: int) -> SectorRow:
    return SectorRow(
        position=position,
        marketing_carrier=segment.marketing_carrier,
        operating_carrier=segment.operating_carrier or "",
        flight_number=segment.flight_number,
        origin=segment.origin,
        destination=segment.destination,
        departure=segment.departure,
        arrival=segment.arrival,
        day_change=segment.day_change,
        booking_class=segment.booking_class,
        cabin=segment.cabin.value,
    )


def row_to_segment(row: SectorRow) -> Segment:
    return Segment(
        marketing_carrier=row.marketing_carrier,
        flight_number=row.flight_number,
        origin=row.origin,
        destination=row.destination,
        departure=row.departure,
        arrival=row.arrival,
        booking_class=row.booking_class,
        cabin=Cabin(row.cabin),
        operating_carrier=row.operating_carrier or None,
        day_change=row.day_change,
    )


def tax_to_row(tax: AppliedTax) -> TaxRow:
    return TaxRow(
        code=tax.code,
        amount=tax.amount.amount,
        currency=tax.amount.currency,
        point=tax.point,
        carrier_imposed=tax.carrier_imposed,
    )


def row_to_tax(row: TaxRow) -> AppliedTax:
    return AppliedTax(
        code=row.code,
        amount=Money(row.amount, row.currency),
        point=row.point,
        carrier_imposed=row.carrier_imposed,
    )


def offer_to_row(offer: Offer) -> ObservationRow:
    """One offer as the row that will be written, sectors and taxes included."""
    key = offer.series_key()
    row = ObservationRow(
        series_key="-".join(key),
        fingerprint=offer.fingerprint(),
        source=offer.source,
        origin=offer.origin,
        destination=offer.destination,
        departure=offer.itinerary.departure.date(),
        cabin=offer.cabin.value,
        fare_bases="/".join(offer.fare_bases),
        base_amount=offer.breakdown.base.amount,
        base_currency=offer.breakdown.base.currency,
        tax_amount=offer.breakdown.tax_total.amount,
        surcharge_amount=offer.breakdown.surcharge_total.amount,
        total_amount=offer.total.amount,
        quoted_total=(
            offer.breakdown.quoted_total.amount
            if offer.breakdown.quoted_total is not None
            else None
        ),
        seats=offer.seats,
        bag_pieces=offer.baggage.pieces,
        refundable=offer.refundable,
        validating_carrier=offer.validating_carrier or "",
        raw_digest=offer.raw_digest or "",
        first_seen=offer.observed_at,
        last_seen=offer.observed_at,
    )
    for position, segment in enumerate(offer.itinerary.segments):
        row.sectors.append(segment_to_row(segment, position))
    for tax in offer.breakdown.taxes:
        row.taxes.append(tax_to_row(tax))
    return row


def row_to_offer(row: ObservationRow) -> Offer:
    """The offer back out, priced the way it was written.

    The quoted total is restored even when it disagrees with the parts,
    because the disagreement is the finding and dropping it on the way
    through storage would quietly close every open discrepancy.
    """
    itinerary = Itinerary([row_to_segment(s) for s in row.sectors])
    breakdown = PriceBreakdown(
        base=Money(row.base_amount, row.base_currency),
        taxes=tuple(row_to_tax(t) for t in row.taxes),
        quoted_total=(
            Money(row.quoted_total, row.base_currency) if row.quoted_total is not None else None
        ),
    )
    return Offer(
        source=row.source,
        itinerary=itinerary,
        breakdown=breakdown,
        fare_bases=tuple(b for b in row.fare_bases.split("/") if b),
        observed_at=row.last_seen,
        cabin=Cabin(row.cabin),
        seats=row.seats,
        baggage=Baggage(pieces=row.bag_pieces),
        refundable=row.refundable,
        validating_carrier=row.validating_carrier or None,
        raw_digest=row.raw_digest or None,
    )


def series_key_of(origin: str, destination: str, departure: date, cabin: str) -> str:
    """The key an observation is grouped under. Kept as the name callers use."""
    return series.key(origin, destination, departure, cabin)


def result_to_row(result: Result) -> RunResultRow:
    """One task's outcome, flattened onto the row that keeps it."""
    return RunResultRow(
        supplier=result.task.supplier,
        market=result.task.market.key,
        departure=result.task.departure,
        outcome=result.outcome.value,
        offers=result.offers,
        attempts=result.attempts,
        digest=result.digest,
        reason=result.reason[:128],
        fresh=result.fresh,
        problems=result.problems,
    )


def row_to_result(row: RunResultRow, started: date) -> Result:
    """Rebuild a result, working out again what was never stored.

    Days out is derived from the run's own start rather than kept, because a
    stored copy can disagree with the departure sitting next to it and there
    is no way afterwards to tell which of the two is wrong.
    """
    origin, destination = row.market.split("-", 1)
    return Result(
        task=Task(
            supplier=row.supplier,
            market=Market(origin, destination),
            departure=row.departure,
            days_out=(row.departure - started).days,
        ),
        outcome=Outcome(row.outcome),
        offers=row.offers,
        digest=row.digest,
        fresh=row.fresh,
        attempts=row.attempts,
        problems=row.problems,
        reason=row.reason,
    )


def run_to_row(report: RunReport, finished: datetime | None = None) -> RunRow:
    """A whole run, with its results hanging off it."""
    row = RunRow(
        started_at=report.started,
        finished_at=finished,
        tasks=len(report.results),
        calls=report.calls,
        offers=report.offers,
        skipped_breaker=report.skipped_breaker,
        skipped_budget=report.skipped_budget,
    )
    row.results = [result_to_row(r) for r in report.results]
    return row


def row_to_run(row: RunRow) -> RunReport:
    """Rebuild a run report out of the database, results and all."""
    started = row.started_at
    report = RunReport(
        started=started,
        skipped_breaker=row.skipped_breaker,
        skipped_budget=row.skipped_budget,
    )
    report.results = [row_to_result(r, started.date()) for r in row.results]
    return report


# The eight flags travel together and are named the same on both sides, so
# both directions read off one list rather than two that can drift apart.
COMBINABILITY_FLAGS = (
    "round_trip",
    "circle_trip",
    "open_jaw",
    "double_open_jaw",
    "end_on_end",
    "same_carrier_only",
    "same_rule_only",
    "side_trip",
)


def combinability_to_row(record: Combinability, load_id: int | None = None) -> CombinabilityRow:
    return CombinabilityRow(
        load_id=load_id,
        carrier=record.carrier,
        fare_basis=record.fare_basis,
        **{name: getattr(record, name) for name in COMBINABILITY_FLAGS},
    )


def row_to_combinability(row: CombinabilityRow) -> Combinability:
    return Combinability(
        carrier=row.carrier,
        fare_basis=row.fare_basis,
        **{name: getattr(row, name) for name in COMBINABILITY_FLAGS},
    )
