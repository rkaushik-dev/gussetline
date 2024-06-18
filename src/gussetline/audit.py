"""The audit, which is the whole reason the desk exists.

A supplier sends an offer. We reprice the same itinerary from the tariff and
compare. The interesting output is not whether the numbers match, because they
usually do, it is the shape of the disagreement when they do not.

Four shapes come up and they need different letters:

A supplier that is too high is money the customer should not have paid, and it
is worth a claim. A supplier that is too low is usually a fare we do not hold,
which means our tariff is stale rather than their price being wrong, and
sending that as a finding is how a desk loses a supplier relationship. A tax
difference is neither, it is a filing we have not loaded. And a difference
that lands exactly on a minimum check is nearly always us: our check fired and
theirs did not, or the other way round.

So every finding carries a class as well as an amount, and the class decides
who reads it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.engine import Priced, Tariffs, price
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter
from gussetline.fcl import compare as compare_lines
from gussetline.money import Money
from gussetline.offers import Offer
from gussetline.trace import Stage, Trace, Verdict

# Below this the desk does not write to anybody. It is one rounding unit in
# most currencies and chasing it costs more than it recovers.
MATERIAL = Decimal("1.00")


class Finding(str, Enum):
    """What kind of disagreement this is, which decides who gets the letter."""

    AGREES = "agrees"
    SUPPLIER_HIGH = "supplier_high"
    SUPPLIER_LOW = "supplier_low"
    TAX_DIFFERENCE = "tax_difference"
    MINIMUM_CHECK = "minimum_check"
    NOT_PRICEABLE = "not_priceable"


@dataclass(frozen=True, slots=True)
class Audit:
    """One offer, repriced, with the disagreement named."""

    offer: Offer
    finding: Finding
    ours: Money | None = None
    theirs: Money | None = None
    difference: Money | None = None
    line_differences: tuple[str, ...] = field(default_factory=tuple)
    reasons: tuple[str, ...] = field(default_factory=tuple)
    priced: Priced | None = None

    @property
    def material(self) -> bool:
        """Whether the gap is worth anybody's time."""
        if self.difference is None:
            return False
        return self.difference.abs().amount >= MATERIAL

    @property
    def claimable(self) -> bool:
        """A finding the desk will actually send to the supplier."""
        return self.material and self.finding is Finding.SUPPLIER_HIGH

    @property
    def recovered(self) -> Money:
        """What a successful claim would be worth. Zero unless claimable."""
        if not self.claimable or self.difference is None:
            return Money.zero(self.offer.currency)
        return self.difference.abs()

    def __str__(self) -> str:
        if self.finding is Finding.AGREES:
            return f"{self.offer.source} agrees at {self.theirs}"
        if self.difference is None:
            return f"{self.offer.source} {self.finding.value}"
        return f"{self.offer.source} {self.finding.value} by {self.difference.abs()}"


def audit(
    offer: Offer,
    tariffs: Tariffs,
    converter: Converter,
    ticketing: date,
    *,
    breaks: tuple[int, ...] | None = None,
    trace: Trace | None = None,
) -> Audit:
    """Reprice an offer from the tariff and name the disagreement.

    ``breaks`` is where the supplier broke the fare, which comes off the
    coupons and is not something to guess at. Reprice against a different
    split and every finding is about the split rather than about the price.
    """
    trail = trace if trace is not None else Trace(offer.fingerprint()[:8])
    try:
        priced = price(
            offer.itinerary,
            tariffs,
            converter,
            ticketing,
            offer.currency,
            breaks=breaks,
            trace=trail,
        )
    except NoFareFound as exc:
        trail.failed_step(Stage.AUDIT, "could not reprice from the tariff", **exc.details)
        return Audit(
            offer=offer,
            finding=Finding.NOT_PRICEABLE,
            theirs=offer.total,
            reasons=(str(exc),),
        )

    ours = priced.base_fare
    theirs = offer.breakdown.base
    difference = theirs - ours

    finding = _classify(difference, priced, offer)
    trail.record(
        Stage.AUDIT,
        _verdict(finding),
        f"{finding.value} by {difference.abs()}",
        ours=str(ours),
        theirs=str(theirs),
    )
    return Audit(
        offer=offer,
        finding=finding,
        ours=ours,
        theirs=theirs,
        difference=difference,
        reasons=tuple(priced.refusals),
        priced=priced,
    )


def _classify(difference: Money, priced: Priced, offer: Offer) -> Finding:
    if difference.abs().amount < MATERIAL:
        return Finding.AGREES
    if priced.construction.minimum_added > 0:
        return Finding.MINIMUM_CHECK
    if not offer.breakdown.adds_up:
        return Finding.TAX_DIFFERENCE
    if difference.amount > 0:
        return Finding.SUPPLIER_HIGH
    return Finding.SUPPLIER_LOW


def _verdict(finding: Finding) -> Verdict:
    if finding is Finding.AGREES:
        return Verdict.PASS
    if finding in (Finding.SUPPLIER_LOW, Finding.MINIMUM_CHECK):
        return Verdict.INFO
    return Verdict.FAIL


def audit_line(offer_line: str, priced: Priced) -> tuple[str, ...]:
    """Compare a supplier's fare calculation line against ours.

    Worth running even when the totals agree. Two lines that reach the same
    number by different routes mean one of us has the wrong fare and the
    other has a compensating error, and that pair will not agree next month.
    """
    return tuple(compare_lines(priced.line, offer_line))


def recovered_total(audits: list[Audit], currency: str) -> Money:
    """What the claimable findings in a run are worth together."""
    running = Money.zero(currency)
    for item in audits:
        if item.claimable:
            running = running + item.recovered
    return running


def by_finding(audits: list[Audit]) -> dict[str, int]:
    """How a run came out, which is the first line of the daily report."""
    counts: dict[str, int] = {}
    for item in audits:
        counts[item.finding.value] = counts.get(item.finding.value, 0) + 1
    return counts
