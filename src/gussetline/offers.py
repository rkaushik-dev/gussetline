"""The offer, which is what every supplier payload turns into.

One shape, three sources, and the shape is the contract. Everything above this
line reads offers and nothing above this line has ever seen a supplier
payload, which is the only reason a fourth supplier can be added in an
afternoon.

The breakdown is the part worth being strict about. A supplier sends a total
and some components, and the components do not always add up to the total.
Sometimes that is a real finding and sometimes it is a field we failed to
parse, so the offer records both what was sent and what the parts add to, and
refuses to quietly replace one with the other. An offer whose parts disagree
with its total is still an offer. It is just an offer with a discrepancy on
it, and the audit is what the desk is paid for.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.itinerary import Itinerary
from gussetline.money import Money, total
from gussetline.taxes import AppliedTax, carrier_total, government_total


@dataclass(frozen=True, slots=True)
class Baggage:
    """What the fare includes. Pieces and weight are alternatives, not both."""

    pieces: int | None = None
    kilos: int | None = None

    def __post_init__(self) -> None:
        if self.pieces is not None and self.pieces < 0:
            raise ValidationError("a bag count is not negative", pieces=self.pieces)
        if self.kilos is not None and self.kilos < 0:
            raise ValidationError("an allowance is not negative", kilos=self.kilos)

    @property
    def is_stated(self) -> bool:
        return self.pieces is not None or self.kilos is not None

    def __str__(self) -> str:
        if self.pieces is not None:
            return f"{self.pieces}PC"
        if self.kilos is not None:
            return f"{self.kilos}KG"
        return "unstated"


@dataclass(frozen=True, slots=True)
class PriceBreakdown:
    """Base fare, taxes, surcharges, and the total the supplier claimed."""

    base: Money
    taxes: tuple[AppliedTax, ...] = field(default_factory=tuple)
    quoted_total: Money | None = None

    def __post_init__(self) -> None:
        for tax in self.taxes:
            if tax.amount.currency != self.base.currency:
                raise ValidationError(
                    "a breakdown is in one currency",
                    base=self.base.currency,
                    tax=tax.amount.currency,
                )
        if self.quoted_total is not None and self.quoted_total.currency != self.base.currency:
            raise ValidationError("the quoted total is in another currency")

    @property
    def currency(self) -> str:
        return self.base.currency

    @property
    def tax_total(self) -> Money:
        return government_total(self.taxes, self.currency)

    @property
    def surcharge_total(self) -> Money:
        return carrier_total(self.taxes, self.currency)

    @property
    def computed_total(self) -> Money:
        return total(self.base, self.tax_total, self.surcharge_total)

    @property
    def stated_total(self) -> Money:
        """What the supplier said, falling back to what the parts add to."""
        return self.quoted_total if self.quoted_total is not None else self.computed_total

    @property
    def discrepancy(self) -> Money:
        """Quoted minus computed. Zero when the parts add up, which is not always."""
        return self.stated_total - self.computed_total

    @property
    def adds_up(self) -> bool:
        return self.discrepancy.is_zero

    def tax_codes(self) -> tuple[str, ...]:
        return tuple(sorted({t.code for t in self.taxes}))

    def __str__(self) -> str:
        return f"{self.base} + {self.tax_total} tax + {self.surcharge_total} yq"


@dataclass(frozen=True, slots=True)
class Offer:
    """One priced itinerary as one supplier described it at one moment."""

    source: str
    itinerary: Itinerary
    breakdown: PriceBreakdown
    fare_bases: tuple[str, ...]
    observed_at: datetime
    cabin: Cabin = Cabin.ECONOMY
    seats: int | None = None
    baggage: Baggage = field(default_factory=Baggage)
    refundable: bool | None = None
    validating_carrier: str | None = None
    raw_digest: str | None = None
    notes: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.source.strip():
            raise ValidationError("an offer names its source")
        object.__setattr__(self, "source", self.source.strip().lower())
        if not self.fare_bases:
            raise ValidationError("an offer carries at least one fare basis")
        if self.seats is not None and self.seats < 0:
            raise ValidationError("a seat count is not negative", seats=self.seats)

    @property
    def origin(self) -> str:
        return self.itinerary.origin

    @property
    def destination(self) -> str:
        return self.itinerary.destination

    @property
    def total(self) -> Money:
        return self.breakdown.stated_total

    @property
    def currency(self) -> str:
        return self.breakdown.currency

    @property
    def carriers(self) -> tuple[str, ...]:
        return self.itinerary.carriers

    @property
    def is_direct(self) -> bool:
        return len(self.itinerary.elements) == 1

    def fingerprint(self) -> str:
        """A stable identity for the priced thing, ignoring when it was seen.

        Two observations of the same offer an hour apart have to collapse to
        one series, and the same itinerary at a different price must not. So
        the digest covers the sectors, the fare bases and the amount, and
        deliberately leaves out the source, because two suppliers selling the
        same fare at the same price is a duplicate and not a finding.
        """
        parts = [
            "|".join(
                f"{s.designator}:{s.origin}{s.destination}:{s.departure.isoformat()}"
                for s in self.itinerary.segments
            ),
            "|".join(self.fare_bases),
            str(self.total.amount),
            self.total.currency,
        ]
        return hashlib.sha256("~".join(parts).encode("utf-8")).hexdigest()[:32]

    def series_key(self) -> tuple[str, str, str, str]:
        """What groups observations into one watchable price series."""
        return (
            self.itinerary.origin,
            self.itinerary.destination,
            self.itinerary.departure.date().isoformat(),
            self.cabin.value,
        )

    def cheaper_than(self, other: Offer) -> bool:
        if self.currency != other.currency:
            raise ValidationError(
                "two offers in different currencies do not compare",
                left=self.currency,
                right=other.currency,
            )
        return self.total < other.total

    def difference_from(self, other: Offer) -> Money:
        if self.currency != other.currency:
            raise ValidationError("two offers in different currencies do not subtract")
        return self.total - other.total

    def with_note(self, note: str) -> Offer:
        return Offer(
            source=self.source,
            itinerary=self.itinerary,
            breakdown=self.breakdown,
            fare_bases=self.fare_bases,
            observed_at=self.observed_at,
            cabin=self.cabin,
            seats=self.seats,
            baggage=self.baggage,
            refundable=self.refundable,
            validating_carrier=self.validating_carrier,
            raw_digest=self.raw_digest,
            notes=(*self.notes, note),
        )

    def __str__(self) -> str:
        route = "".join([self.origin, *(e.destination for e in self.itinerary.elements)])
        return f"{self.source} {route} {'/'.join(self.fare_bases)} {self.total}"


def cheapest(offers: list[Offer]) -> Offer:
    """The lowest total. Ties break on the earlier observation, then the source."""
    if not offers:
        raise ValidationError("no offers to compare")
    currencies = {o.currency for o in offers}
    if len(currencies) > 1:
        raise ValidationError("offers in more than one currency", currencies=sorted(currencies))
    return min(offers, key=lambda o: (o.total.amount, o.observed_at, o.source))


def deduplicate(offers: list[Offer]) -> list[Offer]:
    """Keep the first sighting of each distinct priced itinerary."""
    seen: set[str] = set()
    kept: list[Offer] = []
    for offer in sorted(offers, key=lambda o: o.observed_at):
        digest = offer.fingerprint()
        if digest in seen:
            continue
        seen.add(digest)
        kept.append(offer)
    return kept


def spread(offers: list[Offer]) -> Decimal:
    """How far apart the market is, which is the desk's first sanity check."""
    if len(offers) < 2:
        return Decimal(0)
    amounts = [o.total.amount for o in offers]
    return max(amounts) - min(amounts)
