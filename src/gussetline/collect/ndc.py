"""The offer feed, which is JSON and is not as tidy as that sounds.

Four habits of this supplier's payloads, all of them normal in the industry
and all of them fatal to a reader that assumes otherwise.

A field that holds a list holds a bare object when there is one of them. One
segment is a dict and two segments are a list of dicts, and the shape changes
with the data rather than with the schema.

Amounts arrive as strings on one endpoint and as JSON numbers on another. A
JSON number is a float and a float is not an amount, so numbers are read
through their text form and never through the value the parser produced.

Keys moved from camel case to snake case in a version that both endpoints
still serve, so every lookup takes both spellings.

And null is used for absent, for zero and for unknown, so a null is treated as
absent everywhere and the caller decides what absent means for that field.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from gussetline.errors import MalformedPayload
from gussetline.flights import Cabin, Segment
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Baggage, Offer, PriceBreakdown
from gussetline.taxes import CARRIER_IMPOSED, AppliedTax

SOURCE = "ndc"


def snake(name: str) -> str:
    out: list[str] = []
    for char in name:
        if char.isupper():
            out.append("_")
            out.append(char.lower())
        else:
            out.append(char)
    return "".join(out).lstrip("_")


def pick(node: Any, *names: str, default: Any = None) -> Any:
    """Read a key under either spelling, treating null as absent."""
    if not isinstance(node, dict):
        return default
    for name in names:
        for candidate in (name, snake(name)):
            if candidate in node and node[candidate] is not None:
                return node[candidate]
    return default


def as_list(value: Any) -> list[Any]:
    """A list, whatever shape the supplier used for one of something."""
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def amount_of(value: Any) -> Decimal:
    """Read an amount without ever going through a float.

    A JSON number has already been through a float by the time it reaches
    here, so it is rendered back to its shortest exact text and read from
    that. Anything that is not a number at all is a malformed payload rather
    than a zero.
    """
    if isinstance(value, Decimal):
        return value
    if isinstance(value, bool) or value is None:
        raise MalformedPayload("not an amount", value=repr(value))
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        return Decimal(repr(value))
    if isinstance(value, str):
        try:
            return Decimal(value.strip())
        except InvalidOperation:
            raise MalformedPayload("not an amount", value=value) from None
    raise MalformedPayload("not an amount", value=repr(value))


def money_of(node: Any, fallback_currency: str = "") -> Money | None:
    """Read the money shape, which is an amount and a code in one object."""
    if node is None:
        return None
    if not isinstance(node, dict):
        return None
    raw = pick(node, "amount", "value")
    if raw is None:
        return None
    code = pick(node, "currencyCode", "code", "currency", default=fallback_currency)
    if not code:
        return None
    return Money(amount_of(raw), str(code))


def when(value: Any) -> datetime | None:
    """Read a timestamp, dropping the offset because sectors are wall clock.

    The offset has to be cut for both signs and only after the date, because
    a date has two hyphens in it and a naive search for one takes the string
    apart at the year. A western departure carries a minus offset and an
    earlier version of this only cut the plus, so every American arrival came
    back aware and every comparison against a naive time raised.
    """
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1]
    tail = text[10:]
    for sign in ("+", "-"):
        if sign in tail:
            text = text[: 10 + tail.index(sign)]
            break
    try:
        return datetime.fromisoformat(text)
    except ValueError:
        return None


@dataclass
class Reading:
    """What came out, and what could not be read."""

    offers: list[Offer] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return not self.problems

    def __len__(self) -> int:
        return len(self.offers)

    def __str__(self) -> str:
        return f"{len(self.offers)} offers, {len(self.problems)} problems"


def _segment(node: Any) -> Segment | None:
    departure = when(pick(node, "departureTime", "departure"))
    arrival = when(pick(node, "arrivalTime", "arrival"))
    origin = pick(node, "origin", "departureAirport")
    destination = pick(node, "destination", "arrivalAirport")
    carrier = pick(node, "marketingCarrier", "carrier")
    number = pick(node, "flightNumber", "number")
    booking_class = pick(node, "bookingClass", "rbd")
    if not all([departure, arrival, origin, destination, carrier, number, booking_class]):
        return None
    assert departure is not None and arrival is not None
    cabin = str(pick(node, "cabin", default="Y"))
    return Segment(
        marketing_carrier=str(carrier),
        flight_number=str(number),
        origin=str(origin),
        destination=str(destination),
        departure=departure,
        arrival=arrival,
        booking_class=str(booking_class),
        cabin=Cabin(cabin) if cabin in {c.value for c in Cabin} else Cabin.ECONOMY,
        operating_carrier=pick(node, "operatingCarrier"),
        day_change=max(0, (arrival.date() - departure.date()).days),
    )


def _taxes(node: Any, fallback: str) -> list[AppliedTax]:
    found: list[AppliedTax] = []
    for entry in as_list(pick(node, "taxes", "taxBreakdown")):
        code = pick(entry, "code", "taxCode")
        amount = money_of(entry, fallback)
        if code is None or amount is None:
            continue
        found.append(
            AppliedTax(
                code=str(code),
                amount=amount,
                point=str(pick(entry, "point", default="")),
                carrier_imposed=str(code) in CARRIER_IMPOSED,
            )
        )
    return found


def parse(payload: str | bytes, observed_at: datetime) -> Reading:
    """Read an offer feed response, keeping what it could not use."""
    try:
        document = json.loads(payload)
    except ValueError as exc:
        raise MalformedPayload("response was not json") from exc

    envelope = pick(document, "offerResponse", "OfferResponse", default=document)
    entries = as_list(pick(envelope, "offers", "offerList"))
    if not entries:
        raise MalformedPayload("the payload carries no offers")

    reading = Reading()
    for index, entry in enumerate(entries):
        try:
            offer = _offer(entry, observed_at)
        except MalformedPayload as exc:
            reading.problems.append(f"offer {index}: {exc.message}")
            continue
        if offer is None:
            reading.problems.append(f"offer {index}: could not be assembled")
            continue
        reading.offers.append(offer)
    return reading


def _offer(entry: Any, observed_at: datetime) -> Offer | None:
    price = pick(entry, "priceDetail", "price", default={})
    base = money_of(pick(price, "baseAmount", "base"))
    if base is None:
        raise MalformedPayload("no base amount on the offer")

    segments: list[Segment] = []
    for journey in as_list(pick(entry, "paxJourneys", "journeys")):
        for node in as_list(pick(journey, "segments", "segmentList")):
            built = _segment(node)
            if built is None:
                raise MalformedPayload("a segment was missing a field")
            segments.append(built)
    if not segments:
        return None

    try:
        itinerary = Itinerary(segments)
    except Exception as exc:
        raise MalformedPayload("the segments do not join up") from exc

    bags = pick(entry, "baggageAllowance", "bags")
    pieces = pick(bags, "pieces") if isinstance(bags, dict) else bags

    return Offer(
        source=SOURCE,
        itinerary=itinerary,
        breakdown=PriceBreakdown(
            base=base,
            taxes=tuple(_taxes(price, base.currency)),
            quoted_total=money_of(pick(price, "totalAmount", "total"), base.currency),
        ),
        fare_bases=tuple(str(b) for b in as_list(pick(entry, "fareBasis", "fareBasisCodes"))),
        observed_at=observed_at,
        cabin=itinerary.segments[0].cabin,
        seats=pick(entry, "seatsRemaining", "availableSeats"),
        baggage=Baggage(pieces=int(pieces)) if isinstance(pieces, int) else Baggage(),
        refundable=pick(entry, "refundable"),
        validating_carrier=pick(entry, "validatingCarrier"),
    )
