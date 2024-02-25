"""Fare basis codes, which are a filing convention and not a grammar.

``KLWAP7RT/CH25`` is a real code. The first letter is the booking class it
prices in, ``L`` is the low season, ``W`` is the weekend variant, ``AP7``
means seven days advance purchase, ``RT`` says it is half of a round trip, and
everything after the slash is a ticket designator that discounts it by a
quarter for a child.

None of that is guaranteed. Carriers file whatever fits in the field and the
only part the tariff itself relies on is the prime code and the designator.
So this reads what it recognises, records the rest verbatim, and never refuses
a code it cannot decompose. A parser that throws here would stop the desk
pricing perfectly good tickets because a carrier used a letter we had not
seen.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal

from gussetline.errors import ValidationError

_SEASONS = {
    "H": "high",
    "K": "shoulder",
    "L": "low",
    "O": "off peak",
    "J": "peak",
    "T": "basic",
}

_ADVANCE = re.compile(r"AP(\d{1,3})")
_MIN_STAY = re.compile(r"MIN(\d{1,3})([DM])")
_MAX_STAY = re.compile(r"(\d{1,2})M(?![A-Z])")
_DESIGNATOR = re.compile(r"^([A-Z]{2})(\d{1,3})?$")
_CODE = re.compile(r"^[A-Z0-9]{1,15}$")


@dataclass(frozen=True, slots=True)
class TicketDesignator:
    """The part after the slash. It discounts the fare or it names a deal."""

    raw: str
    kind: str
    percent: Decimal | None = None

    @property
    def is_discount(self) -> bool:
        return self.percent is not None

    def apply_to(self, amount: Decimal) -> Decimal:
        """Take the discount off. A designator with no percent changes nothing."""
        if self.percent is None:
            return amount
        return amount * (Decimal(100) - self.percent) / Decimal(100)

    def __str__(self) -> str:
        return self.raw


@dataclass(frozen=True, slots=True)
class FareBasis:
    """A parsed fare basis, with everything it could not decode kept whole."""

    raw: str
    prime: str
    designator: TicketDesignator | None = None
    season: str | None = None
    weekend: bool | None = None
    advance_days: int | None = None
    max_stay_months: int | None = None
    min_stay: tuple[int, str] | None = None
    round_trip: bool | None = None
    unparsed: str = ""
    notes: tuple[str, ...] = field(default_factory=tuple)

    @property
    def body(self) -> str:
        """The code without its designator, which is what the tariff keys on."""
        return self.raw.split("/", 1)[0]

    @property
    def is_discounted(self) -> bool:
        return self.designator is not None and self.designator.is_discount

    def matches_class(self, booking_class: str) -> bool:
        return self.prime == booking_class.strip().upper()

    def __str__(self) -> str:
        return self.raw


def parse_designator(text: str) -> TicketDesignator:
    """Read ``CH25``, ``ID90`` or a bare ``IT`` into a designator."""
    raw = text.strip().upper()
    match = _DESIGNATOR.match(raw)
    if not match:
        raise ValidationError("not a ticket designator", value=text)
    kind, digits = match.group(1), match.group(2)
    percent = Decimal(digits) if digits is not None else None
    if percent is not None and percent > 100:
        raise ValidationError("a discount over a hundred percent", value=text)
    return TicketDesignator(raw=raw, kind=kind, percent=percent)


def parse_fare_basis(text: str) -> FareBasis:
    """Decompose a fare basis as far as it goes and keep the remainder."""
    raw = text.strip().upper()
    if not raw:
        raise ValidationError("empty fare basis")

    body, _, designator_text = raw.partition("/")
    if not _CODE.match(body):
        raise ValidationError("not a fare basis", value=text)

    designator = parse_designator(designator_text) if designator_text else None

    prime = body[0]
    if not prime.isalpha():
        raise ValidationError("a fare basis starts with its booking class", value=text)

    rest = body[1:]
    notes: list[str] = []

    advance = _ADVANCE.search(rest)
    advance_days = int(advance.group(1)) if advance else None
    if advance:
        rest = rest[: advance.start()] + rest[advance.end() :]

    minimum = _MIN_STAY.search(rest)
    min_stay: tuple[int, str] | None = None
    if minimum:
        min_stay = (int(minimum.group(1)), "days" if minimum.group(2) == "D" else "months")
        rest = rest[: minimum.start()] + rest[minimum.end() :]

    max_stay = _MAX_STAY.search(rest)
    max_stay_months = int(max_stay.group(1)) if max_stay else None
    if max_stay:
        rest = rest[: max_stay.start()] + rest[max_stay.end() :]

    round_trip: bool | None = None
    for token, value in (("RT", True), ("OW", False)):
        if token in rest:
            round_trip = value
            rest = rest.replace(token, "", 1)
            break

    season: str | None = None
    weekend: bool | None = None
    leftover: list[str] = []
    for char in rest:
        if season is None and char in _SEASONS:
            season = _SEASONS[char]
            continue
        if weekend is None and char in ("W", "X"):
            weekend = char == "W"
            continue
        leftover.append(char)

    unparsed = "".join(leftover)
    if unparsed:
        notes.append(f"unrecognised {unparsed}")

    return FareBasis(
        raw=raw,
        prime=prime,
        designator=designator,
        season=season,
        weekend=weekend,
        advance_days=advance_days,
        max_stay_months=max_stay_months,
        min_stay=min_stay,
        round_trip=round_trip,
        unparsed=unparsed,
        notes=tuple(notes),
    )


def same_fare_family(left: FareBasis, right: FareBasis) -> bool:
    """Whether two codes are the same fare wearing different designators.

    Two codes that differ only after the slash are the same published fare
    sold to a child and to an adult, and the watch has to treat them as one
    or it will report a drop every time a family books.
    """
    return left.body == right.body
