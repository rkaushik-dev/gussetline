"""Category 31, voluntary changes, which is the arithmetic the desk lives on.

A cheaper fare has appeared for a ticket the customer already holds. Whether
that is worth anything is not the price difference. It is the price difference
minus the change fee, and then only if the fare difference is collectable in
the direction that helps, which it usually is not.

Three filings decide it.

Repricing basis says which day's tariff the new ticket is priced from.
Historical means the original ticketing date, so today's cheaper fare is not
available at all and the whole exercise is off. Current means today, which is
the case worth watching.

Residual handling says what happens when the new fare is lower. Forfeited
means the difference is kept by the carrier and the customer saves nothing,
which makes a drop worthless however large it is. Refundable means it comes
back as money. Retained means it becomes a credit for a future ticket, which
the desk counts at a discount because credits expire.

And the fee itself, from category 16, is subtracted whatever happens.

So a fare that has dropped three hundred euro can be worth three hundred, or
worth nothing, or worth a credit the customer will probably not use, and the
only way to tell is to read all three filings together.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


class RepricingBasis(str, Enum):
    """Which day's tariff the reissue is priced from."""

    HISTORICAL = "historical"
    CURRENT = "current"


class Residual(str, Enum):
    """What happens when the new fare is lower than the old one."""

    FORFEITED = "forfeited"
    REFUNDABLE = "refundable"
    RETAINED = "retained"


# What a travel credit is worth against cash. Credits expire, they are tied to
# one passenger, and about a fifth of the ones this desk has issued were never
# used, so a recommendation that counts them at face value overstates itself.
CREDIT_DISCOUNT = Decimal("0.80")


@dataclass(frozen=True, slots=True)
class VoluntaryChange(Provision):
    """Category 31 as it is filed."""

    permitted: bool = True
    basis: RepricingBasis = RepricingBasis.CURRENT
    residual: Residual = Residual.FORFEITED
    keep_fare_breaks: bool = False
    same_carrier_only: bool = False
    once_only: bool = False

    category: Category = Category.VOLUNTARY_CHANGES

    def evaluate(self, context: Context) -> Outcome:
        if not context.reissue:
            return silent(self.category, "not a reissue")
        if not self.permitted:
            return fails(self.category, "the fare does not permit a voluntary change")
        return passes(
            self.category,
            f"reissue priced {self.basis.value}, residual {self.residual.value}",
            basis=self.basis.value,
            residual=self.residual.value,
        )

    def __str__(self) -> str:
        if not self.permitted:
            return "changes not permitted"
        return f"changes permitted, {self.basis.value} pricing, residual {self.residual.value}"


@dataclass(frozen=True, slots=True)
class Reissue:
    """What a reissue actually costs or returns."""

    paid: Money
    new_fare: Money
    penalty: Money
    rule: VoluntaryChange

    def __post_init__(self) -> None:
        for amount in (self.paid, self.new_fare, self.penalty):
            if amount.currency != self.paid.currency:
                raise ValidationError("a reissue happens in one currency")
            if amount.is_negative:
                raise ValidationError("a reissue amount is not negative", amount=str(amount))

    @property
    def difference(self) -> Money:
        """New fare minus old. Positive means the new one costs more."""
        return self.new_fare - self.paid

    @property
    def add_collect(self) -> Money:
        """What the passenger pays now. Never less than nothing.

        A reissue to a dearer fare collects the difference plus the fee. A
        reissue to a cheaper one still collects the fee, and whether the
        difference comes back is the residual question and not this one.
        """
        if self.difference.is_negative:
            return self.penalty
        return self.difference + self.penalty

    @property
    def refund(self) -> Money:
        """Cash back, which only a refundable residual produces."""
        if not self.difference.is_negative:
            return Money.zero(self.paid.currency)
        if self.rule.residual is not Residual.REFUNDABLE:
            return Money.zero(self.paid.currency)
        back = self.difference.abs() - self.penalty
        return back if not back.is_negative else Money.zero(self.paid.currency)

    @property
    def credit(self) -> Money:
        """A voucher, which only a retained residual produces."""
        if not self.difference.is_negative:
            return Money.zero(self.paid.currency)
        if self.rule.residual is not Residual.RETAINED:
            return Money.zero(self.paid.currency)
        left = self.difference.abs() - self.penalty
        return left if not left.is_negative else Money.zero(self.paid.currency)

    @property
    def benefit(self) -> Money:
        """What the reissue is worth to the customer, in comparable money.

        Cash counts at face value and a credit at a discount, because a credit
        that expires unused was never worth what it said. A reissue that costs
        money is a negative benefit and the desk should not raise it.
        """
        if not self.rule.permitted:
            return Money.zero(self.paid.currency)
        cash = self.refund
        voucher = self.credit
        if not voucher.is_zero:
            cash = cash + Money(
                (voucher.amount * CREDIT_DISCOUNT).quantize(Decimal("0.01")), voucher.currency
            )
        if self.add_collect.amount > 0 and cash.is_zero:
            return -self.add_collect
        return cash

    @property
    def worth_doing(self) -> bool:
        return self.rule.permitted and self.benefit.amount > 0

    def __str__(self) -> str:
        if not self.rule.permitted:
            return "not permitted"
        if self.benefit.is_negative:
            return f"costs {self.benefit.abs()}"
        return f"worth {self.benefit}"


def reissue_for(
    paid: Money,
    new_fare: Money,
    penalty: Money,
    rule: VoluntaryChange,
) -> Reissue:
    return Reissue(paid=paid, new_fare=new_fare, penalty=penalty, rule=rule)


def priced_from_today(rule: VoluntaryChange) -> bool:
    """Whether today's tariff is even usable for this ticket.

    A historical filing means the reissue prices off the original ticketing
    date, so a fare that dropped since then does not exist for this passenger
    and the watch should be retired rather than kept firing.
    """
    return rule.permitted and rule.basis is RepricingBasis.CURRENT
