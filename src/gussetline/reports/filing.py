"""What moved between two filings of a carrier's tariff.

A carrier refiles constantly. Most of a weekly file is the same fares it held
last week, some amounts move, a handful of fare bases appear, and a handful
stop being filed. The desk needs to know which is which before it opens,
because each one means something different and only one of them is news.

An amount that moved is the interesting case. Every watch in that market is
now measuring against a fare the carrier no longer files at that price, and
the ones that moved down are the ones worth repricing this morning rather
than tonight. That is the only reason this report exists.

A fare that stopped being filed is the dangerous case, because nothing about
it looks broken. The fare is still in the database under the old load, it
still prices, and the answer it gives is one the carrier will refuse at the
ticket desk. The desk finds out when a passenger is on the phone.

A new fare is usually nothing. Carriers file promotional fares constantly and
most of them are for markets nobody is watching. They are counted rather than
listed unless somebody asks, so the two lists that matter stay on the page.

What counts as the same fare, and this is the whole design: carrier, market,
fare basis, cabin and the direction the fare is filed in. Not the amount,
obviously, and not the rule number either, because a carrier that repoints a
fare at a new rule has changed what the fare permits and not which fare it
is. That change is reported as an amendment beside the price ones, because
a rule change on an unchanged price is exactly the amendment that gets missed.

Sale dates are deliberately not part of the identity. A refile that extends
the discontinue date of an unchanged fare is the most common single edit in a
tariff and treating it as a withdrawal plus an addition would bury the two
lists that matter under a hundred rows of nothing happening.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal

from gussetline.money import Money
from gussetline.persistence.models import FareRow
from gussetline.reports import layout

# What makes two filed fares the same fare.
Identity = tuple[str, str, str, str, str, str]


def identity(row: FareRow) -> Identity:
    """Carrier, market, basis, cabin and direction. Not the price."""
    return (
        row.carrier,
        row.origin,
        row.destination,
        row.fare_basis,
        row.cabin,
        row.owrt,
    )


@dataclass(frozen=True, slots=True)
class Move:
    """One fare whose price changed, and by how much."""

    fare: Identity
    was: Money
    now: Money

    @property
    def difference(self) -> Money:
        return self.now - self.was

    @property
    def fell(self) -> bool:
        return self.now < self.was

    @property
    def percent(self) -> Decimal:
        """How far it moved, for ordering. Zero rather than a division error."""
        if not self.was.amount:
            return Decimal(0)
        return (self.difference.amount / self.was.amount * 100).quantize(Decimal("0.01"))

    def __str__(self) -> str:
        carrier, origin, destination, basis, cabin, _ = self.fare
        way = "down" if self.fell else "up"
        return (
            f"{carrier} {origin}-{destination} {basis} {cabin} "
            f"{self.was} to {self.now}, {way} {abs(self.percent)}%"
        )


@dataclass(frozen=True, slots=True)
class Amendment:
    """One fare whose price held and whose terms did not."""

    fare: Identity
    field: str
    was: str
    now: str

    def __str__(self) -> str:
        carrier, origin, destination, basis, _, _ = self.fare
        return f"{carrier} {origin}-{destination} {basis} {self.field} {self.was} to {self.now}"


@dataclass(frozen=True, slots=True)
class Gone:
    """One fare the carrier has stopped filing."""

    fare: Identity
    was: Money

    def __str__(self) -> str:
        carrier, origin, destination, basis, cabin, _ = self.fare
        return f"{carrier} {origin}-{destination} {basis} {cabin} was {self.was}"


@dataclass
class Filing:
    """One filing read against the one before it."""

    carrier: str = ""
    currency: str = "EUR"
    before: int = 0
    after: int = 0
    moves: list[Move] = field(default_factory=list)
    amendments: list[Amendment] = field(default_factory=list)
    withdrawn: list[Gone] = field(default_factory=list)
    added: int = 0

    @property
    def held(self) -> int:
        """Fares in both filings, whatever happened to them."""
        return self.after - self.added

    @property
    def unchanged(self) -> int:
        return self.held - len(self.moves) - len(self.amendments)

    def falls(self) -> list[Move]:
        """The ones worth repricing this morning, steepest first."""
        return sorted([m for m in self.moves if m.fell], key=lambda m: m.percent)

    def rises(self) -> list[Move]:
        return sorted([m for m in self.moves if not m.fell], key=lambda m: -m.percent)

    def worth_a_look(self) -> bool:
        """Whether anybody has to do something before the desk opens."""
        return bool(self.falls() or self.withdrawn)

    def __str__(self) -> str:
        return (
            f"{len(self.moves)} moved, {len(self.withdrawn)} withdrawn, "
            f"{self.added} added over {self.after} fares"
        )


# The fields a fare can be amended on without its price moving. Order is the
# order they are reported in, which is roughly how much trouble each causes.
AMENDABLE = ("rule_number", "routing_number", "tariff_number", "footnote", "global_indicator")


def build(before: list[FareRow], after: list[FareRow], currency: str = "EUR") -> Filing:
    """Read one filing against the one it replaces.

    Both sides come in as rows rather than as a load id, so this can be run
    against a file that has not been taken in yet. Reading a filing before
    loading it is the whole point on the morning a carrier files something
    unexpected.
    """
    was = {identity(row): row for row in before}
    now = {identity(row): row for row in after}

    report = Filing(
        carrier=_carrier(before, after),
        currency=currency,
        before=len(before),
        after=len(after),
        added=len([key for key in now if key not in was]),
    )

    for key, row in now.items():
        old = was.get(key)
        if old is None:
            continue
        if old.amount != row.amount or old.currency != row.currency:
            report.moves.append(
                Move(
                    key,
                    Money.of(old.amount, old.currency),
                    Money.of(row.amount, row.currency),
                )
            )
            continue
        report.amendments.extend(_amendments(key, old, row))

    report.withdrawn = [
        Gone(key, Money.of(row.amount, row.currency)) for key, row in was.items() if key not in now
    ]
    report.withdrawn.sort(key=lambda g: g.fare)
    return report


def _amendments(key: Identity, old: FareRow, new: FareRow) -> list[Amendment]:
    """Every term that moved on a fare whose price did not."""
    found = []
    for name in AMENDABLE:
        before, after = getattr(old, name), getattr(new, name)
        if before != after:
            found.append(Amendment(key, name.replace("_", " "), str(before), str(after)))
    return found


def _carrier(before: list[FareRow], after: list[FareRow]) -> str:
    """Whose filing this is, or a note that it is more than one carrier's."""
    carriers = {row.carrier for row in before} | {row.carrier for row in after}
    if len(carriers) == 1:
        return carriers.pop()
    return "+".join(sorted(carriers)) if carriers else ""


def render(report: Filing, limit: int = 15) -> str:
    """The report as somebody reads it before the desk opens."""
    return layout.page(
        layout.heading(f"filing for {report.carrier or 'nobody'}"),
        layout.figures(
            [
                ("was", report.before),
                ("is", report.after),
                ("held", report.held),
                ("unchanged", report.unchanged),
                ("moved", len(report.moves)),
                ("amended", len(report.amendments)),
                ("withdrawn", len(report.withdrawn)),
                ("added", report.added),
            ]
        ),
        layout.section("fell, steepest first, reprice these", report.falls(), limit),
        layout.section(
            "no longer filed, these still price and should not", report.withdrawn, limit
        ),
        layout.section("rose", report.rises(), limit),
        layout.section("same price, different terms", report.amendments, limit),
    )
