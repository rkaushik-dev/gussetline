"""The pricing gateway, which is fixed width and stateful and old.

The response is a run of records that only means anything in order. A header
opens the payload and gives the year, an offer record opens each priced
option, and the segments, fare components and taxes that follow belong to
whichever offer record came last. An end record closes the offer with the
total the gateway is claiming.

The year is the part that bites. Segment records carry the day and month and
not the year, because in 1978 nobody was shopping across a new year, and a
reader that assumes the header's year gets every December booking for January
travel wrong by twelve months. So the year is carried forward and rolled when
the month goes backwards.
"""

from __future__ import annotations

from dataclasses import dataclass
from dataclasses import field as dcfield
from datetime import date, datetime
from decimal import Decimal

from gussetline.collect.fixedwidth import Layout, Record, RecordSpec, fields
from gussetline.collect.harvest import Harvest
from gussetline.errors import MalformedPayload, ValidationError
from gussetline.flights import Cabin, Segment
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Baggage, Offer, PriceBreakdown
from gussetline.taxes import CARRIER_IMPOSED, AppliedTax

SOURCE = "gds"

HEADER = RecordSpec(
    "H1",
    fields(
        ("source", 2, 8),
        ("stamp", 10, 8, "date"),
        ("origin", 18, 3),
        ("destination", 21, 3),
        ("currency", 24, 3),
    ),
)

OFFER = RecordSpec(
    "O1",
    fields(
        ("reference", 2, 6),
        ("seats", 8, 2, "int", True),
        ("bags", 10, 2, "int", True),
    ),
)

SECTOR = RecordSpec(
    "S1",
    fields(
        ("carrier", 2, 2),
        ("flight", 4, 4),
        ("origin", 8, 3),
        ("destination", 11, 3),
        ("departure", 14, 8, "daytime"),
        ("arrival", 22, 8, "daytime"),
        ("day_change", 30, 1, "int"),
        ("booking_class", 31, 1),
        ("cabin", 32, 1),
        ("operating", 33, 2, "text", True),
    ),
)

COMPONENT = RecordSpec(
    "F1",
    fields(
        ("basis", 2, 10),
        ("currency", 12, 3),
        ("amount", 15, 9, "amount"),
        ("indicator", 24, 2),
    ),
)

TAX = RecordSpec(
    "T1",
    fields(
        ("code", 2, 3),
        ("currency", 5, 3),
        ("amount", 8, 9, "amount"),
    ),
)

END = RecordSpec(
    "E1",
    fields(
        ("currency", 2, 3),
        ("amount", 5, 9, "amount"),
    ),
)

LAYOUT = Layout([HEADER, OFFER, SECTOR, COMPONENT, TAX, END])


class YearRoll:
    """Carries the year forward across records that only give a month.

    Starts at the header's year and rolls forward whenever the month goes
    backwards, which is the only signal the payload gives that a new year has
    begun. It never rolls back, because a journey does not.
    """

    def __init__(self, start: int) -> None:
        self.year = start
        self._last_month = 0

    def resolve(self, month: int, day: int) -> date:
        if self._last_month and month < self._last_month:
            self.year += 1
        self._last_month = month
        return date(self.year, month, day)


def _sector(record: Record, roll: YearRoll) -> Segment:
    month, day, hour, minute = record["departure"]
    departs = datetime.combine(roll.resolve(month, day), datetime.min.time()).replace(
        hour=hour, minute=minute
    )
    _, _, arr_hour, arr_minute = record["arrival"]
    arrives = departs.replace(hour=arr_hour, minute=arr_minute)
    cabin = record.get("cabin", "Y")
    return Segment(
        marketing_carrier=record["carrier"],
        flight_number=record["flight"].lstrip("0") or "0",
        origin=record["origin"],
        destination=record["destination"],
        departure=departs,
        arrival=arrives,
        booking_class=record["booking_class"],
        cabin=Cabin(cabin) if cabin in {c.value for c in Cabin} else Cabin.ECONOMY,
        operating_carrier=record.get("operating") or None,
        day_change=record["day_change"],
    )


def _tax(record: Record) -> AppliedTax:
    code = record["code"]
    return AppliedTax(
        code=code,
        amount=Money(record["amount"], record["currency"]),
        point="",
        carrier_imposed=code in CARRIER_IMPOSED,
    )


@dataclass
class _Building:
    """One offer under construction, as the records arrive."""

    reference: str
    seats: int | None = None
    bags: int | None = None
    sectors: list[Segment] = dcfield(default_factory=list)
    bases: list[str] = dcfield(default_factory=list)
    taxes: list[AppliedTax] = dcfield(default_factory=list)
    base: Money | None = None
    total: Money | None = None

    def add_component(self, record: Record) -> None:
        self.bases.append(record["basis"])
        amount = Money(record["amount"], record["currency"])
        self.base = amount if self.base is None else self.base + amount

    def build(self, observed_at: datetime) -> Offer | None:
        """An offer, or None when the records did not add up to one."""
        if not self.sectors or self.base is None:
            return None
        try:
            itinerary = Itinerary(self.sectors)
        except ValidationError:
            return None
        return Offer(
            source=SOURCE,
            itinerary=itinerary,
            breakdown=PriceBreakdown(
                base=self.base,
                taxes=tuple(self.taxes),
                quoted_total=self.total,
            ),
            fare_bases=tuple(self.bases),
            observed_at=observed_at,
            cabin=itinerary.segments[0].cabin,
            seats=self.seats,
            baggage=Baggage(pieces=self.bags) if self.bags is not None else Baggage(),
            validating_carrier=itinerary.segments[0].marketing_carrier,
        )


def parse(payload: str | bytes, observed_at: datetime) -> Harvest:
    """Read a whole gateway response into offers.

    An offer that cannot be assembled is dropped and the rest are kept,
    because a gateway that sends one broken option out of twelve is a normal
    Tuesday and refusing the payload would lose the other eleven.
    """
    reading = LAYOUT.read(payload)
    harvest = Harvest(source=SOURCE, problems=[str(p) for p in reading.problems])
    header = reading.require("H1")
    if header["source"] != "GDSFARE":
        raise MalformedPayload("not a pricing gateway payload", source=header["source"])

    year = header["stamp"].year
    offers: list[Offer] = harvest.offers
    current: _Building | None = None
    roll = YearRoll(year)

    for record in reading.records:
        if record.tag == "H1":
            continue
        if record.tag == "O1":
            if current is not None:
                built = current.build(observed_at)
                if built is not None:
                    offers.append(built)
            roll = YearRoll(year)
            current = _Building(
                reference=record["reference"],
                seats=record.get("seats"),
                bags=record.get("bags"),
            )
            continue
        if current is None:
            continue
        if record.tag == "S1":
            current.sectors.append(_sector(record, roll))
        elif record.tag == "F1":
            current.add_component(record)
        elif record.tag == "T1":
            current.taxes.append(_tax(record))
        elif record.tag == "E1":
            current.total = Money(record["amount"], record["currency"])

    if current is not None:
        built = current.build(observed_at)
        if built is not None:
            offers.append(built)
    return harvest


def amount_field(value: Decimal) -> str:
    """The gateway's own nine digit implied decimal, for building requests."""
    return str(int(value * 100)).rjust(9, "0")
