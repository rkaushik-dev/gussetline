"""What a reissue does to the taxes, which is where the money actually goes.

The fare side of a reissue is arithmetic and lives with category 31. The tax
side is not arithmetic and it is where every reissue argument this desk has
ever had ended up.

Four rules and none of them follows from the others.

A tax already paid on a coupon that has been flown is gone. The passenger flew
out of Frankfurt, the German departure tax was owed and collected, and changing
the return does not unmake the departure. Refunding it is the single most
expensive mistake available here, because it looks correct on every screen and
the carrier finds it at the year end.

A tax on a point the new itinerary no longer touches comes back. Not because
the fare came down but because the taxable event did not happen: the passenger
is not departing Vienna any more, so nobody is owed a Vienna departure tax.

A carrier imposed surcharge is not a tax. YQ and YR are the carrier's own money
in a tax shaped box, they move with the fare, and they follow the residual: a
fare whose difference is forfeited forfeits its YQ with it. Treating them as
taxes refunds money the carrier never agreed to give back.

And a tax that went up is collected whatever else happens. There is no netting
against a fare that came down: the government is owed what it is owed and the
residual question is between the passenger and the carrier.

Everything here works per tax code and per point, because that is the grain the
rules are written at. Totalling first and comparing the totals gives the right
answer on the easy cases and quietly wrong ones on every itinerary that changed
a country.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.rules.changes import CREDIT_DISCOUNT, Residual
from gussetline.taxes import AppliedTax


class Treatment(str, Enum):
    """What happens to one tax line when a ticket is reissued."""

    COLLECTED = "collected"
    REFUNDED = "refunded"
    KEPT = "kept"
    FLOWN = "flown"
    UNCHANGED = "unchanged"

    @property
    def moves_money(self) -> bool:
        return self in (Treatment.COLLECTED, Treatment.REFUNDED)


@dataclass(frozen=True, slots=True)
class Line:
    """One tax code at one point, and what the reissue did with it."""

    code: str
    point: str
    was: Money
    now: Money
    treatment: Treatment
    carrier_imposed: bool = False

    @property
    def difference(self) -> Money:
        return self.now - self.was

    @property
    def collected(self) -> Money:
        """What the passenger pays for this line. Never negative."""
        if self.treatment is not Treatment.COLLECTED:
            return Money.zero(self.was.currency)
        return self.difference

    @property
    def refunded(self) -> Money:
        """What comes back for this line. Never negative."""
        if self.treatment is not Treatment.REFUNDED:
            return Money.zero(self.was.currency)
        return self.was - self.now

    def __str__(self) -> str:
        return f"{self.code} at {self.point} {self.treatment.value} {self.was} to {self.now}"


@dataclass
class TaxReissue:
    """Every tax line on a reissue, and the two totals that come out of it."""

    currency: str
    lines: list[Line] = field(default_factory=list)

    @property
    def collect(self) -> Money:
        total = Money.zero(self.currency)
        for line in self.lines:
            total = total + line.collected
        return total

    @property
    def refund(self) -> Money:
        total = Money.zero(self.currency)
        for line in self.lines:
            total = total + line.refunded
        return total

    @property
    def kept(self) -> Money:
        """What the carrier keeps, which is a residual and not a refund."""
        total = Money.zero(self.currency)
        for line in self.lines:
            if line.treatment is Treatment.KEPT:
                total = total + (line.was - line.now)
        return total

    @property
    def flown(self) -> Money:
        """What is gone because the taxable event already happened."""
        total = Money.zero(self.currency)
        for line in self.lines:
            if line.treatment is Treatment.FLOWN:
                total = total + line.was
        return total

    def of(self, treatment: Treatment) -> list[Line]:
        return [line for line in self.lines if line.treatment is treatment]

    def __len__(self) -> int:
        return len(self.lines)

    def __str__(self) -> str:
        return f"collect {self.collect}, refund {self.refund}, {len(self.lines)} lines"


def _key(tax: AppliedTax) -> tuple[str, str]:
    return (tax.code.upper(), tax.point.upper())


def assess(
    was: tuple[AppliedTax, ...],
    now: tuple[AppliedTax, ...],
    flown_points: frozenset[str] = frozenset(),
    residual: Residual = Residual.REFUNDABLE,
) -> TaxReissue:
    """Work out every tax line, per code and per point.

    ``flown_points`` names the places the passenger has already departed. A
    tax owed at one of those was owed when it was collected and changing what
    happens afterwards does not unmake it.
    """
    currency = _currency(was, now)
    report = TaxReissue(currency=currency)
    before = {_key(tax): tax for tax in was}
    after = {_key(tax): tax for tax in now}
    gone = {point.upper() for point in flown_points}

    for key in sorted(before.keys() | after.keys()):
        code, point = key
        old = before.get(key)
        new = after.get(key)
        report.lines.append(_line(code, point, old, new, currency, gone, residual))
    return report


def _line(
    code: str,
    point: str,
    old: AppliedTax | None,
    new: AppliedTax | None,
    currency: str,
    flown: set[str],
    residual: Residual,
) -> Line:
    """One tax code at one point, decided in the order the rules stack."""
    filed = old if old is not None else new
    imposed = filed.carrier_imposed if filed is not None else False
    was = old.amount if old is not None else Money.zero(currency)
    became = new.amount if new is not None else Money.zero(currency)

    if became > was:
        # A government is owed what it is owed. There is no netting against a
        # fare that came down.
        return Line(code, point, was, became, Treatment.COLLECTED, imposed)

    if became == was:
        return Line(code, point, was, became, Treatment.UNCHANGED, imposed)

    if point in flown and not imposed:
        # The taxable event already happened. Refunding this is the most
        # expensive mistake available here and it looks right on every screen.
        return Line(code, point, was, became, Treatment.FLOWN, imposed)

    if imposed and residual is not Residual.REFUNDABLE:
        # The carrier's own money in a tax shaped box, following the fare it
        # belongs to rather than the rules a government writes.
        return Line(code, point, was, became, Treatment.KEPT, imposed)

    return Line(code, point, was, became, Treatment.REFUNDED, imposed)


def _currency(was: tuple[AppliedTax, ...], now: tuple[AppliedTax, ...]) -> str:
    found = {tax.amount.currency for tax in (*was, *now)}
    if not found:
        raise ValidationError("a reissue needs a tax to compare")
    if len(found) > 1:
        raise ValidationError("a reissue compares one currency", currencies=sorted(found))
    return found.pop()


def credit_for(report: TaxReissue, residual: Residual) -> Money:
    """What a refundable tax total is worth when it comes back as a voucher.

    A voucher is not cash and the desk has never valued it as cash. The same
    discount the fare side uses, so a recommendation cannot be worth more by
    moving money between the fare and the taxes.
    """
    if residual is not Residual.RETAINED:
        return Money.zero(report.currency)
    amount = report.refund.amount * CREDIT_DISCOUNT
    return Money(amount.quantize(report.refund.amount), report.currency)


def net(report: TaxReissue) -> Money:
    """Collect minus refund, which is what the reissue costs in tax alone.

    Negative means the taxes came back. Reported rather than clamped, because
    a reissue whose taxes come back further than its fare goes up is a real
    shape and the desk needs to see the sign.
    """
    return report.collect - report.refund


def points_flown(coupons_flown: tuple[str, ...]) -> frozenset[str]:
    """The departure points behind us, as the set the assessment reads.

    Takes the origins and not the destinations. A tax is owed on departure
    from a point, so arriving somewhere and not leaving again has not made
    anything owed there.
    """
    return frozenset(point.upper() for point in coupons_flown if point)
