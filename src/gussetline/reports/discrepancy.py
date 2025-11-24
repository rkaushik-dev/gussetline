"""Turning a pile of audits into the few letters that are worth writing.

An audit run over a day produces thousands of findings and most of them are
one offer apiece. Sending those out one at a time is how the desk got a
reputation for noise, because a single offer that is three euros high is
rounding, a bad rate of exchange on their side, or a fare filed the hour after
we loaded the tariff. Nobody can act on it and nobody should be asked to.

Forty offers on the same carrier, in the same market, on the same fare basis,
all three euros high, is not rounding. It is a filing error, and it is one
letter about one fare rather than forty letters about forty tickets. So the
report groups first and counts second, and a group only becomes a case when
enough of its members agree about the size of the gap as well as its direction.

The other thing the report insists on is not adding up money it cannot add up.
The audits arrive in whatever currency the supplier quoted, and a total that
silently sums euros and pounds is worse than no total. Anything outside the
report's own currency is counted and set aside rather than converted here,
because converting at report time uses today's rate for a fare that was quoted
in March and the resulting number cannot be reconciled against anything.

Findings where the supplier came in low are kept, but they are not claims. A
supplier lower than our reprice nearly always means we are missing a fare, so
those go in their own section under our own name, and the daily habit of
reading that section is what keeps the tariff loaded.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from decimal import Decimal

from gussetline.audit import Audit, Finding
from gussetline.money import Money
from gussetline.reports import layout

# How many offers have to agree before a group is worth writing about.
CASE_THRESHOLD = 3

# Gaps are grouped to the nearest whole unit before they are counted as equal.
GAP_STEP = Decimal("1")


@dataclass(frozen=True, slots=True)
class Key:
    """What makes two findings the same problem rather than two problems."""

    source: str
    carrier: str
    market: str
    fare_basis: str
    finding: Finding
    gap: Decimal

    def __str__(self) -> str:
        return f"{self.source} {self.carrier} {self.market} {self.fare_basis} off by {self.gap}"


@dataclass
class Case:
    """One group of findings that agree about the direction and the size."""

    key: Key
    audits: list[Audit] = field(default_factory=list)
    currency: str = "EUR"

    @property
    def offers(self) -> int:
        return len(self.audits)

    @property
    def worth(self) -> Money:
        """What the whole group is worth if the supplier pays it."""
        running = Money.zero(self.currency)
        for item in self.audits:
            if item.difference is not None:
                running = running + item.difference.abs()
        return running

    @property
    def is_a_case(self) -> bool:
        """Whether enough offers agree to make this worth a letter."""
        return self.offers >= CASE_THRESHOLD

    @property
    def claimable(self) -> bool:
        return self.is_a_case and self.key.finding is Finding.SUPPLIER_HIGH

    def __str__(self) -> str:
        return f"{self.key} on {self.offers} offers worth {self.worth}"


@dataclass
class Discrepancy:
    """A run of audits, grouped, with the letters pulled out of the noise."""

    currency: str
    audited: int = 0
    agreed: int = 0
    not_priceable: int = 0
    other_currency: int = 0
    cases: list[Case] = field(default_factory=list)
    singles: list[Case] = field(default_factory=list)

    @property
    def claims(self) -> list[Case]:
        """The groups the desk will write to a supplier about."""
        return [c for c in self.cases if c.claimable]

    @property
    def our_gaps(self) -> list[Case]:
        """Groups where the supplier beat our tariff, which is our problem."""
        return [c for c in self.cases if c.key.finding is Finding.SUPPLIER_LOW]

    @property
    def claim_value(self) -> Money:
        running = Money.zero(self.currency)
        for case in self.claims:
            running = running + case.worth
        return running

    @property
    def noise(self) -> int:
        """Findings that never made it into a group big enough to send."""
        return sum(case.offers for case in self.singles)

    def __str__(self) -> str:
        return (
            f"{self.audited} audited, {len(self.claims)} claims "
            f"worth {self.claim_value}, {self.noise} left as noise"
        )


def _market(item: Audit) -> str:
    return f"{item.offer.origin}-{item.offer.destination}"


def _carrier(item: Audit) -> str:
    carrier = item.offer.validating_carrier
    if carrier:
        return carrier
    return item.offer.carriers[0] if item.offer.carriers else "??"


def _fare_basis(item: Audit) -> str:
    return item.offer.fare_bases[0] if item.offer.fare_bases else "??"


def _gap(item: Audit) -> Decimal:
    """The size of the disagreement, rounded so near misses group together."""
    if item.difference is None:
        return Decimal(0)
    return item.difference.abs().amount.quantize(GAP_STEP)


def key_for(item: Audit) -> Key:
    """What this finding would have to share with another to be the same one."""
    return Key(
        source=item.offer.source,
        carrier=_carrier(item),
        market=_market(item),
        fare_basis=_fare_basis(item),
        finding=item.finding,
        gap=_gap(item),
    )


def build(audits: Iterable[Audit], currency: str) -> Discrepancy:
    """Group a run of audits and split the letters from the noise."""
    report = Discrepancy(currency=currency)
    groups: dict[Key, Case] = {}

    for item in audits:
        report.audited += 1
        if item.finding is Finding.AGREES:
            report.agreed += 1
            continue
        if item.finding is Finding.NOT_PRICEABLE:
            report.not_priceable += 1
            continue
        if item.offer.currency != currency:
            report.other_currency += 1
            continue
        if not item.material:
            continue
        key = key_for(item)
        case = groups.setdefault(key, Case(key=key, currency=currency))
        case.audits.append(item)

    ordered = sorted(
        groups.values(),
        key=lambda c: (-c.offers, c.key.source, c.key.carrier, c.key.market),
    )
    report.cases = [c for c in ordered if c.is_a_case]
    report.singles = [c for c in ordered if not c.is_a_case]
    return report


def render(report: Discrepancy, limit: int = 10) -> str:
    """The report as it goes to the person who writes the letters."""
    return layout.page(
        layout.heading(f"discrepancies in {report.currency}"),
        layout.figures(
            [
                ("audited", report.audited),
                ("agreed", report.agreed),
                ("not priceable", report.not_priceable),
                ("other currency", report.other_currency),
                ("claims", f"{len(report.claims)} worth {report.claim_value}"),
                ("noise", f"{report.noise} findings in groups too small to send"),
            ]
        ),
        layout.section("worth a letter", report.claims, limit),
        layout.section("fares we do not hold, which is ours to fix", report.our_gaps, limit),
    )
