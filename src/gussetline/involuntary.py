"""When the carrier breaks the contract, which is a different arithmetic.

A voluntary change is the passenger deciding to travel differently and paying
for the privilege. An involuntary one is the carrier cancelling the flight, and
almost everything the desk knows about repricing is wrong for it.

The fare does not reprice. The passenger bought a fare and the carrier failed to
fly it, so the passenger keeps the fare they bought whatever it costs today.
Repricing an involuntary reissue at today's number is the single most common
way a desk overcharges somebody who has already been let down.

There is no change fee. Category 16 is about a passenger changing their mind
and nobody changed their mind here.

The advance purchase and the minimum stay do not apply. Those conditions are
promises the passenger made about how they would buy and travel, and they kept
them; the carrier is the one who moved.

What does still apply is the carrier's own limit on what it will reaccommodate
to. A cancelled economy seat does not become a business one, and a rerouting on
another airline needs an agreement between the two.

The distinction that pays for this module is that an involuntary reissue looks
identical to a voluntary one in every system the desk touches. Both are a
reissue against a ticket, both have a new itinerary, and only the reason tells
them apart. So the reason is a required field here and there is no default.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.money import Money
from gussetline.reissue import TaxReissue
from gussetline.rules.changes import Residual


class Cause(str, Enum):
    """Why the carrier is reaccommodating. Never a default and never blank."""

    CANCELLED = "cancelled"
    SCHEDULE_CHANGE = "schedule_change"
    MISCONNECTED = "misconnected"
    DOWNGRADED = "downgraded"
    DENIED_BOARDING = "denied_boarding"

    @property
    def is_the_carriers_doing(self) -> bool:
        """Every one of them is, which is what makes this file necessary."""
        return True

    @property
    def owes_a_downgrade_refund(self) -> bool:
        """Whether the passenger is owed the difference between two cabins."""
        return self in (Cause.DOWNGRADED, Cause.DENIED_BOARDING)


@dataclass(frozen=True, slots=True)
class Reaccommodation:
    """What the carrier is offering, and what it may not offer."""

    cause: Cause
    same_carrier_only: bool = True
    same_cabin_or_better: bool = True
    interline_agreed: tuple[str, ...] = field(default_factory=tuple)

    def permits(self, carrier: str, validating: str) -> bool:
        """Whether this reaccommodation may be flown on a given carrier."""
        wanted = carrier.strip().upper()
        if wanted == validating.strip().upper():
            return True
        if self.same_carrier_only:
            return False
        return wanted in {code.strip().upper() for code in self.interline_agreed}

    def permits_cabin(self, was: Cabin, now: Cabin) -> bool:
        """Whether the cabin offered is one the carrier may reaccommodate into."""
        if not self.same_cabin_or_better:
            return True
        return now.rank >= was.rank

    def __str__(self) -> str:
        where = "same carrier" if self.same_carrier_only else "interline agreed"
        return f"{self.cause.value}, {where}"


@dataclass(frozen=True, slots=True)
class Involuntary:
    """What an involuntary reissue actually costs, which is usually nothing."""

    paid: Money
    cause: Cause
    taxes: TaxReissue | None = None
    downgrade_refund: Money = field(default_factory=lambda: Money.zero("EUR"))
    notes: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.downgrade_refund.is_negative:
            raise ValidationError("a refund is not negative", amount=str(self.downgrade_refund))
        if not self.cause.is_the_carriers_doing:
            raise ValidationError("that is not an involuntary cause", cause=self.cause.value)

    @property
    def new_fare(self) -> Money:
        """The fare the passenger keeps, which is the one they bought.

        Not today's number. The passenger bought a fare and the carrier
        failed to fly it, and repricing here is the most common way a desk
        overcharges somebody who has already been let down.
        """
        return self.paid

    @property
    def penalty(self) -> Money:
        """Nothing. Category 16 is about changing your mind."""
        return Money.zero(self.paid.currency)

    @property
    def add_collect(self) -> Money:
        """What the passenger pays, which is the tax difference and nothing else."""
        if self.taxes is None:
            return Money.zero(self.paid.currency)
        return self.taxes.collect

    @property
    def refund(self) -> Money:
        """What comes back: the tax difference, plus any downgrade owed."""
        back = self.downgrade_refund
        if self.taxes is not None:
            back = back + self.taxes.refund
        return back

    @property
    def net(self) -> Money:
        """Collect minus refund. Negative means the passenger is owed money."""
        return self.add_collect - self.refund

    def __str__(self) -> str:
        return f"{self.cause.value}: collect {self.add_collect}, refund {self.refund}"


def assess(
    paid: Money,
    cause: Cause,
    taxes: TaxReissue | None = None,
    was: Cabin = Cabin.ECONOMY,
    now: Cabin | None = None,
    cabin_difference: Money | None = None,
) -> Involuntary:
    """Work out an involuntary reissue, which is mostly working out what is not owed.

    ``cabin_difference`` is what the two cabins differ by and is priced above
    this, because it is a fare question and this file does not reprice fares.
    """
    notes = [
        "the fare is the one the passenger bought, because the carrier did not fly it",
        "no change fee, because nobody changed their mind",
    ]

    owed = Money.zero(paid.currency)
    if now is not None and now.rank < was.rank:
        if not cause.owes_a_downgrade_refund:
            notes.append(f"flown in {now.value} rather than {was.value} without a downgrade owed")
        elif cabin_difference is None:
            raise ValidationError(
                "a downgrade needs the difference between the two cabins",
                was=was.value,
                now=now.value,
            )
        else:
            owed = cabin_difference
            notes.append(f"downgraded from {was.value} to {now.value}")

    return Involuntary(
        paid=paid,
        cause=cause,
        taxes=taxes,
        downgrade_refund=owed,
        notes=tuple(notes),
    )


def waives(cause: Cause) -> tuple[str, ...]:
    """Which conditions an involuntary reissue does not have to satisfy.

    Written out rather than checked, because the ones that are waived are
    waived by the reason and not by anything on the ticket, and a caller that
    has this list can say so on the screen next to the price.
    """
    return (
        "advance purchase",
        "minimum stay",
        "maximum stay",
        "day and time",
        "seasonality",
        "penalties",
    )


def still_applies(cause: Cause) -> tuple[str, ...]:
    """What an involuntary reissue still has to satisfy.

    Shorter than the list above and every entry is the carrier's own limit on
    what it will put the passenger on, rather than a condition the passenger
    agreed to.
    """
    return ("eligibility", "flight application", "ticket endorsement")


def residual_for(cause: Cause) -> Residual:
    """What happens to a difference on an involuntary reissue.

    Refundable, always. A residual that is forfeited or retained is a term
    the passenger accepted in exchange for a cheaper fare, and none of that
    applies when the carrier is the one who did not fly.
    """
    return Residual.REFUNDABLE


def is_within_reaccommodation(
    cause: Cause,
    original: date,
    offered: date,
    days: int = 14,
) -> bool:
    """Whether the offered date is close enough to count as the same journey.

    A carrier reaccommodates onto a nearby flight. Two months later is not a
    reaccommodation, it is a new journey the passenger asked for, and pricing
    it as involuntary hands them a fare they did not buy.
    """
    if days < 0:
        raise ValidationError("a reaccommodation window is not negative", days=days)
    return abs((offered - original).days) <= days
