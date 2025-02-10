"""The reprice decision, which is where five separate rules meet.

A held drop is not a saving. It becomes one only if all of these agree:

the fare permits a voluntary change at all, which is category 31;
the change reprices off today rather than off the original ticketing date,
which is also category 31 and is the one that quietly kills most of them;
the residual comes back as something, which is category 31 again and decides
whether the difference is money, a voucher or nothing;
the fee is small enough that something is left, which is category 16 and moves
depending on whether the passenger has flown yet;
and the ticket still has coupons open, which is a reading of the ticket
against today.

Any one of them can turn a four hundred euro drop into nothing, and four of
the five say so without looking at the price at all. So the decision runs them
in that order and stops at the first refusal, and it says which one it was,
because the desk gets asked.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.desk.watch import Observation, Retired, Watch
from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.rules.base import Context
from gussetline.rules.changes import CREDIT_DISCOUNT, RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty, total_penalty


class Verdict(str, Enum):
    """What the desk should do, in one word."""

    REISSUE = "reissue"
    NOT_WORTH_IT = "not_worth_it"
    NOT_CHANGEABLE = "not_changeable"
    PRICED_HISTORICALLY = "priced_historically"
    RESIDUAL_FORFEITED = "residual_forfeited"
    NOTHING_OPEN = "nothing_open"

    @property
    def is_actionable(self) -> bool:
        return self is Verdict.REISSUE

    @property
    def retires_the_watch(self) -> Retired | None:
        """Whether this verdict means the watch should stop looking.

        A fare that cannot be changed and one that reprices historically will
        never become actionable however far the market falls, so the watch is
        spending a shopping request a week on nothing. Not worth it is
        different: that is a price, and prices move.
        """
        if self is Verdict.NOT_CHANGEABLE:
            return Retired.NOT_CHANGEABLE
        if self is Verdict.PRICED_HISTORICALLY:
            return Retired.HISTORICAL
        if self is Verdict.NOTHING_OPEN:
            return Retired.TICKET_FINISHED
        return None


@dataclass(frozen=True, slots=True)
class Decision:
    """What a held drop is actually worth, and why."""

    verdict: Verdict
    watch: Watch
    observation: Observation | None = None
    drop: Money | None = None
    penalty: Money | None = None
    benefit: Money | None = None
    residual: Residual = Residual.FORFEITED
    reason: str = ""
    notes: tuple[str, ...] = field(default_factory=tuple)

    @property
    def actionable(self) -> bool:
        return self.verdict.is_actionable

    @property
    def worth(self) -> Money:
        if self.benefit is None:
            return Money.zero(self.watch.paid.currency)
        return self.benefit

    def __str__(self) -> str:
        if self.actionable:
            return f"{self.watch.series_key} reissue worth {self.worth}"
        return f"{self.watch.series_key} {self.verdict.value}, {self.reason}"


def decide(
    watch: Watch,
    change_rule: VoluntaryChange,
    penalties: list[Penalty],
    context: Context,
    today: date,
    *,
    credit_discount: Decimal = CREDIT_DISCOUNT,
) -> Decision:
    """Run the five checks in the order that costs least to fail."""
    if watch.ticket.is_finished(today):
        return Decision(
            Verdict.NOTHING_OPEN,
            watch,
            reason="every coupon has been used",
        )

    if not change_rule.permitted:
        return Decision(
            Verdict.NOT_CHANGEABLE,
            watch,
            reason="category 31 does not permit a voluntary change",
        )

    if change_rule.basis is not RepricingBasis.CURRENT:
        return Decision(
            Verdict.PRICED_HISTORICALLY,
            watch,
            reason="the reissue prices off the original ticketing date",
        )

    if change_rule.residual is Residual.FORFEITED:
        return Decision(
            Verdict.RESIDUAL_FORFEITED,
            watch,
            residual=change_rule.residual,
            reason="any difference is kept by the carrier",
        )

    held = watch.held_drop()
    if held is None:
        return Decision(
            Verdict.NOT_WORTH_IT,
            watch,
            reason="no drop has held long enough",
        )

    components = len(watch.ticket.coupons)
    fee = total_penalty(
        [p for p in penalties if p.action is Action.CHANGE], context, components, today
    )
    if fee is None:
        fee = Money.zero(watch.paid.currency)
    if fee.currency != watch.paid.currency:
        raise ValidationError(
            "the fee and the fare are in different currencies",
            fee=fee.currency,
            fare=watch.paid.currency,
        )

    drop = watch.drop_against(held)
    gross = drop - fee
    if gross.is_negative or gross.is_zero:
        return Decision(
            Verdict.NOT_WORTH_IT,
            watch,
            observation=held,
            drop=drop,
            penalty=fee,
            benefit=Money.zero(watch.paid.currency),
            residual=change_rule.residual,
            reason=f"the drop of {drop} does not cover the {fee} fee",
        )

    benefit = gross
    notes: list[str] = []
    if change_rule.residual is Residual.RETAINED:
        benefit = Money((gross.amount * credit_discount).quantize(Decimal("0.01")), gross.currency)
        notes.append("the residual comes back as a credit and is counted at a discount")

    if benefit < watch.threshold:
        return Decision(
            Verdict.NOT_WORTH_IT,
            watch,
            observation=held,
            drop=drop,
            penalty=fee,
            benefit=benefit,
            residual=change_rule.residual,
            reason=f"{benefit} is under the {watch.threshold} the desk needs",
            notes=tuple(notes),
        )

    return Decision(
        Verdict.REISSUE,
        watch,
        observation=held,
        drop=drop,
        penalty=fee,
        benefit=benefit,
        residual=change_rule.residual,
        reason=f"a drop of {drop} against a {fee} fee",
        notes=tuple(notes),
    )


def worth_reporting(decisions: list[Decision]) -> list[Decision]:
    """The ones the desk should read, dearest first."""
    live = [d for d in decisions if d.actionable]
    return sorted(live, key=lambda d: d.worth.amount, reverse=True)


def total_benefit(decisions: list[Decision], currency: str) -> Money:
    running = Money.zero(currency)
    for decision in decisions:
        if decision.actionable:
            running = running + decision.worth
    return running


def to_retire(decisions: list[Decision]) -> list[tuple[Watch, Retired]]:
    """Every watch a decision said will never become actionable."""
    found: list[tuple[Watch, Retired]] = []
    for decision in decisions:
        reason = decision.verdict.retires_the_watch
        if reason is not None:
            found.append((decision.watch, reason))
    return found
