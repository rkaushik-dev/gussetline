"""The low fare calendar, which is a web page and not an interface.

The third supplier has no API. It has a month grid of dates and prices meant
for a person, and the desk reads it because a shopping request against the
pricing gateway costs money and this does not. What it gives back is not an
offer, because a calendar cell has no flights in it. It is a quote: a market,
a date, and a number the airline is prepared to advertise.

That distinction is the whole point of keeping this separate. A quote cannot
be audited, cannot be repriced from the tariff and cannot be watched for a
reissue. What it can do is tell the scheduler which dates are worth spending
a real shopping request on, which is worth a great deal when the quota is a
thousand a day and the calendar covers three hundred and thirty dates.

Parsed with the standard library's own HTML reader rather than a regular
expression. The page has attributes in either quoting style and in any order,
and a thousand separator that changes with the locale header, and none of
that is worth fighting with a pattern.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser

from gussetline.errors import MalformedPayload
from gussetline.geography import city_of
from gussetline.money import Money

SOURCE = "calendar"


@dataclass(frozen=True, slots=True)
class Quote:
    """One advertised price for one departure date. No flights in it."""

    origin: str
    destination: str
    departure: date
    amount: Money
    observed_at: datetime
    carrier: str = ""
    seats: int | None = None
    sold_out: bool = False

    @property
    def market(self) -> tuple[str, str]:
        return (self.origin, self.destination)

    def cheaper_than(self, other: Quote) -> bool:
        return self.amount < other.amount

    def __str__(self) -> str:
        if self.sold_out:
            return f"{self.origin}{self.destination} {self.departure.isoformat()} sold out"
        return f"{self.origin}{self.destination} {self.departure.isoformat()} {self.amount}"


def read_price(text: str, currency: str) -> Money | None:
    """Read an advertised price out of whatever the page wrapped it in.

    Handles a currency symbol on either side, a non breaking space between
    the number and the symbol, and both thousand separator conventions. A
    cell that says sold out has no price and gives None rather than zero.
    """
    # Everything that is not a digit or a separator goes first, which covers
    # the currency symbol, the non breaking space and the Swiss apostrophe in
    # one pass and leaves only the separator question to decide.
    cleaned = "".join(ch for ch in text if ch.isdigit() or ch in ".,-")
    if not cleaned or not any(ch.isdigit() for ch in cleaned):
        return None
    if "," in cleaned and "." in cleaned:
        # Whichever separator is last is the decimal one.
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif "," in cleaned:
        head, _, tail = cleaned.rpartition(",")
        cleaned = f"{head}.{tail}" if len(tail) == 2 else cleaned.replace(",", "")
    try:
        return Money(Decimal(cleaned), currency)
    except InvalidOperation:
        return None


class _Grid(HTMLParser):
    """Pulls the fare cells out of the month grid."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.currency = ""
        self.origin = ""
        self.destination = ""
        self.carrier = ""
        self.cells: list[dict[str, str]] = []
        self._cell: dict[str, str] | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {name: (value or "") for name, value in attrs}
        if tag == "meta" and values.get("name") == "fare-currency":
            self.currency = values.get("content", "").upper()
        if tag == "meta" and values.get("name") == "fare-market":
            market = values.get("content", "")
            if "-" in market:
                left, _, right = market.partition("-")
                self.origin, self.destination = left.strip().upper(), right.strip().upper()
        if tag == "meta" and values.get("name") == "fare-carrier":
            self.carrier = values.get("content", "").upper()
        if tag in ("td", "div") and "fare-day" in values.get("class", ""):
            self._cell = dict(values)
            self._text = []

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if self._cell is not None and tag in ("td", "div"):
            self._cell["_text"] = "".join(self._text).strip()
            self.cells.append(self._cell)
            self._cell = None
            self._text = []


@dataclass
class Reading:
    """The quotes a page gave up, and the cells it could not read."""

    quotes: list[Quote] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return not self.problems

    def cheapest(self) -> Quote | None:
        live = [q for q in self.quotes if not q.sold_out]
        if not live:
            return None
        return min(live, key=lambda q: (q.amount.amount, q.departure))

    def on(self, day: date) -> Quote | None:
        for quote in self.quotes:
            if quote.departure == day:
                return quote
        return None

    def __len__(self) -> int:
        return len(self.quotes)

    def __str__(self) -> str:
        return f"{len(self.quotes)} quotes, {len(self.problems)} problems"


def parse(payload: str | bytes, observed_at: datetime) -> Reading:
    """Read a month grid into quotes."""
    text = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else payload
    grid = _Grid()
    grid.feed(text)

    if not grid.origin or not grid.destination:
        raise MalformedPayload("the page does not say which market it is for")
    if not grid.currency:
        raise MalformedPayload("the page does not say which currency it is in")

    origin, destination = city_of(grid.origin), city_of(grid.destination)
    reading = Reading()

    for cell in grid.cells:
        raw_date = cell.get("data-date", "")
        try:
            departure = date.fromisoformat(raw_date)
        except ValueError:
            reading.problems.append(f"cell with an unreadable date {raw_date!r}")
            continue

        sold_out = "sold-out" in cell.get("class", "") or not cell.get("_text", "").strip()
        amount = read_price(cell.get("data-amount", "") or cell.get("_text", ""), grid.currency)
        if amount is None and not sold_out:
            reading.problems.append(f"cell on {raw_date} has no price we could read")
            continue

        reading.quotes.append(
            Quote(
                origin=origin,
                destination=destination,
                departure=departure,
                amount=amount if amount is not None else Money.zero(grid.currency),
                observed_at=observed_at,
                carrier=grid.carrier,
                seats=_seats(cell.get("data-seats", "")),
                sold_out=sold_out or amount is None,
            )
        )

    return reading


def _seats(raw: str) -> int | None:
    try:
        return int(raw)
    except ValueError:
        return None
