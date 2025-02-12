"""One result shape for every parser, because there were three.

Each adapter grew its own Reading class with the same two fields under
slightly different names, and the collector's parser type only fitted one of
them. So the gateway adapter was called through the declared interface, the
offer feed adapter was called through a wrapper that threw its problem list
away, and the calendar adapter could not be called by the collector at all
and was run by hand.

The problem list going missing is the one that cost something. A month of
malformed offers were dropped silently because the only code that read the
problems was a test.

A harvest carries both kinds of output. That is not a compromise: a calendar
page really does produce quotes and no offers, and a gateway response really
does produce offers and no quotes, and a caller that wants offers asks for
offers and gets an empty list from the calendar rather than a type error.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime

from gussetline.money import Money
from gussetline.offers import Offer


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


@dataclass
class Harvest:
    """Everything one payload gave up, and everything it did not."""

    offers: list[Offer] = field(default_factory=list)
    quotes: list[Quote] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    source: str = ""

    @property
    def clean(self) -> bool:
        return not self.problems

    @property
    def empty(self) -> bool:
        return not self.offers and not self.quotes

    def __len__(self) -> int:
        """How many things came out, of whichever kind."""
        return len(self.offers) + len(self.quotes)

    def cheapest_offer(self) -> Offer | None:
        if not self.offers:
            return None
        return min(self.offers, key=lambda o: o.total.amount)

    def cheapest_quote(self) -> Quote | None:
        live = [q for q in self.quotes if not q.sold_out]
        if not live:
            return None
        return min(live, key=lambda q: q.amount.amount)

    def cheapest_amount(self) -> str:
        """The lowest price of either kind, as text, for the schedule.

        Text because the schedule only compares it for equality and two
        amounts in different currencies would compare as numbers without
        complaining.
        """
        offer = self.cheapest_offer()
        quote = self.cheapest_quote()
        if offer is not None:
            return str(offer.total)
        if quote is not None:
            return str(quote.amount)
        return ""

    def quote_on(self, day: date) -> Quote | None:
        for quote in self.quotes:
            if quote.departure == day:
                return quote
        return None

    def extend(self, other: Harvest) -> None:
        self.offers.extend(other.offers)
        self.quotes.extend(other.quotes)
        self.problems.extend(other.problems)

    def __str__(self) -> str:
        parts = []
        if self.offers:
            parts.append(f"{len(self.offers)} offers")
        if self.quotes:
            parts.append(f"{len(self.quotes)} quotes")
        if not parts:
            parts.append("nothing")
        if self.problems:
            parts.append(f"{len(self.problems)} problems")
        return ", ".join(parts)


# What every adapter is. Bytes and the moment they arrived, in; a harvest out.
Adapter = Callable[[bytes, datetime], Harvest]


def offers_only(offers: list[Offer], source: str = "") -> Harvest:
    return Harvest(offers=list(offers), source=source)


def quotes_only(quotes: list[Quote], source: str = "") -> Harvest:
    return Harvest(quotes=list(quotes), source=source)


def nothing(problem: str, source: str = "") -> Harvest:
    return Harvest(problems=[problem], source=source)


def combine(harvests: list[Harvest]) -> Harvest:
    """Fold a run's worth of harvests into one, keeping every problem."""
    joined = Harvest()
    for harvest in harvests:
        joined.extend(harvest)
    return joined
