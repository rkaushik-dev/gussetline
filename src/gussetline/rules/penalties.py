"""Category 16, what it costs to change or cancel, and when.

Two amounts are filed for every action, one before departure and one after,
and which one applies depends on whether the passenger has flown anything
yet. That is a reading of the itinerary against today's date and not a
property of the ticket, so the same ticket carries a fifty euro change fee in
the morning and a two hundred euro one after the outbound has gone.

The second thing filed is how many times the fee is charged. Per ticket means
once. Per fare component means once per component, so a round trip pays twice
and a circle trip three times, and on a cheap fare the fee can exceed what the
ticket cost. The tariff also files a highest of instruction, which means take
the dearest of the applicable component fees and charge it once, and it is
different from all three of the others.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


class Applies(str, Enum):
    """How many times a filed fee is charged."""

    PER_TICKET = "per_ticket"
    PER_COMPONENT = "per_component"
    HIGHEST_OF = "highest_of"


class Action(str, Enum):
    CHANGE = "change"
    CANCEL = "cancel"
    NO_SHOW = "no_show"


@dataclass(frozen=True, slots=True)
class Penalty(Provision):
    """Category 16 as it is filed, for one action."""

    action: Action = Action.CHANGE
    before_departure: Money | None = None
    after_departure: Money | None = None
    applies: Applies = Applies.PER_TICKET
    permitted: bool = True
    no_show_forfeits: bool = False

    category: Category = Category.PENALTIES

    def __post_init__(self) -> None:
        for amount in (self.before_departure, self.after_departure):
            if amount is not None and amount.is_negative:
                raise ValidationError("a penalty is not negative", amount=str(amount))

    @property
    def is_empty(self) -> bool:
        return self.permitted and self.before_departure is None and self.after_departure is None

    def travel_started(self, context: Context, on: date | None = None) -> bool:
        """Whether anything has been flown by the date asked about."""
        today = on if on is not None else (context.today or context.ticketing)
        return today > context.journey_departure

    def amount_on(self, context: Context, on: date | None = None) -> Money | None:
        """The filed amount that applies on a given date, before multiplying."""
        if self.travel_started(context, on):
            return (
                self.after_departure
                if self.after_departure is not None
                else (self.before_departure)
            )
        return self.before_departure

    def charge(
        self,
        context: Context,
        components: int = 1,
        on: date | None = None,
    ) -> Money | None:
        """What the action actually costs, with the multiplier applied.

        None means the action is not permitted at all, which is a different
        answer from a fee of zero and has to stay different, because a fare
        with a zero change fee is the most valuable thing on the desk and a
        fare that cannot be changed is the least.
        """
        if not self.permitted:
            return None
        base = self.amount_on(context, on)
        if base is None:
            return None
        if self.applies is Applies.PER_COMPONENT:
            return base * components
        return base

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)
        if not self.permitted:
            return fails(
                self.category,
                f"the fare does not permit a {self.action.value}",
                action=self.action.value,
            )
        amount = self.amount_on(context)
        return passes(
            self.category,
            f"{self.action.value} costs {amount}" if amount else f"{self.action.value} is free",
            action=self.action.value,
            amount=str(amount) if amount else "0",
        )

    def __str__(self) -> str:
        if not self.permitted:
            return f"{self.action.value} not permitted"
        return (
            f"{self.action.value} {self.before_departure} before, "
            f"{self.after_departure} after, {self.applies.value}"
        )


def highest_of(amounts: list[Money]) -> Money | None:
    """The dearest of several component fees, for the highest of filing."""
    priced = [a for a in amounts if a is not None]
    if not priced:
        return None
    currencies = {a.currency for a in priced}
    if len(currencies) > 1:
        raise ValidationError("penalties in more than one currency", currencies=sorted(currencies))
    return max(priced, key=lambda a: a.amount)


def total_penalty(
    penalties: list[Penalty],
    context: Context,
    components: int = 1,
    on: date | None = None,
) -> Money | None:
    """Add up every filed penalty for one action, respecting each multiplier.

    A highest of filing does not add to the others, it replaces the per
    component arithmetic for its own filing only, which is why this cannot be
    a sum with a special case bolted on.
    """
    charges: list[Money] = []
    for penalty in penalties:
        if penalty.applies is Applies.HIGHEST_OF:
            base = penalty.amount_on(context, on)
            if base is not None:
                dearest = highest_of([base * n for n in range(1, components + 1)])
                if dearest is not None:
                    charges.append(dearest)
            continue
        charge = penalty.charge(context, components, on)
        if charge is not None:
            charges.append(charge)
    if not charges:
        return None
    currencies = {c.currency for c in charges}
    if len(currencies) > 1:
        raise ValidationError("penalties in more than one currency", currencies=sorted(currencies))
    running = Money.zero(charges[0].currency)
    for charge in charges:
        running = running + charge
    return running


def is_refundable(penalties: list[Penalty]) -> bool:
    """Whether any filing permits a cancellation at all."""
    cancels = [p for p in penalties if p.action is Action.CANCEL]
    if not cancels:
        return True
    return any(p.permitted for p in cancels)


def residual_value(paid: Money, penalty: Money | None) -> Money:
    """What is left of a ticket after the fee, floored at nothing.

    Floored because a fee larger than the fare does not leave the passenger
    owing money on a refund, it leaves them with nothing, and a negative
    residual would flow into the savings report as a gain.
    """
    if penalty is None:
        return Money.zero(paid.currency)
    left = paid - penalty
    return left if not left.is_negative else Money.zero(paid.currency)
