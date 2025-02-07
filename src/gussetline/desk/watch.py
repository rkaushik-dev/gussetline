"""Watches, and the difference between a price moving and a price dropping.

A price series is noisy. A market that sits at eight hundred and forty will
show seven hundred and ninety for one poll and go back, because a supplier
cached a stale answer or an inventory bucket opened for an hour and closed.
Acting on that costs a change fee for a fare that is no longer there by the
time the agent picks up the phone, and it has happened enough that the desk
now wants a drop to hold before it is told about it.

So a drop is not the lowest price seen. It is a price below the threshold that
has been seen on the last several polls in a row, and the count is the
hysteresis. Two is the number this desk landed on: one is noise and three is
too slow on a departure a week away.

The threshold itself is not a fixed number either. It is the change fee plus
what the desk needs to make the phone call worth making, so a ticket with a
fifty euro fee and a ticket with a three hundred euro fee want different
drops, and the watch carries its own.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from enum import Enum

from gussetline.desk.ticket import Ticket
from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.geography import city_of
from gussetline.money import Money
from gussetline.offers import Offer

# How many polls in a row a drop has to hold before the desk hears about it.
DEFAULT_HYSTERESIS = 2


class Retired(str, Enum):
    """Why a watch was closed. Never closed without one of these."""

    FLOWN = "the departure has gone"
    TICKET_FINISHED = "every coupon has been used"
    TAKEN = "a reissue was made"
    NOT_CHANGEABLE = "the fare does not permit a change"
    HISTORICAL = "the fare reprices off the original ticketing date"
    BY_HAND = "closed by the desk"


@dataclass(frozen=True, slots=True)
class Observation:
    """One reading of a series, which is all a watch ever sees."""

    seen_on: date
    amount: Money
    fingerprint: str = ""
    source: str = ""

    def __str__(self) -> str:
        return f"{self.seen_on.isoformat()} {self.amount}"


@dataclass
class Watch:
    """A standing instruction to keep looking at one ticket's market."""

    ticket: Ticket
    origin: str
    destination: str
    departure: date
    threshold: Money
    cabin: Cabin = Cabin.ECONOMY
    hysteresis: int = DEFAULT_HYSTERESIS
    active: bool = True
    retired_reason: Retired | None = None
    checks: int = 0
    last_checked: datetime | None = None
    history: list[Observation] = field(default_factory=list)

    def __post_init__(self) -> None:
        object.__setattr__(self, "origin", city_of(self.origin))
        object.__setattr__(self, "destination", city_of(self.destination))
        if self.threshold.is_negative:
            raise ValidationError("a threshold is not negative", value=str(self.threshold))
        if self.hysteresis < 1:
            raise ValidationError("a drop has to hold at least once", value=self.hysteresis)

    @property
    def series_key(self) -> str:
        return f"{self.origin}-{self.destination}-{self.departure.isoformat()}-{self.cabin.value}"

    @property
    def paid(self) -> Money:
        return self.ticket.base_fare

    def record(self, observation: Observation) -> None:
        """Note what a poll found, whether or not it was interesting."""
        self.history.append(observation)
        self.checks += 1

    def retire(self, reason: Retired) -> None:
        self.active = False
        self.retired_reason = reason

    def should_retire(self, today: date) -> Retired | None:
        """Whether this watch has stopped being worth anything."""
        if self.departure < today:
            return Retired.FLOWN
        if self.ticket.is_finished(today):
            return Retired.TICKET_FINISHED
        return None

    def best_since(self, when: date) -> Observation | None:
        recent = [o for o in self.history if o.seen_on >= when]
        if not recent:
            return None
        return min(recent, key=lambda o: o.amount.amount)

    def drop_against(self, observation: Observation) -> Money:
        """How far below what was paid one reading sits. Never negative."""
        if observation.amount.currency != self.paid.currency:
            raise ValidationError(
                "a watch compares one currency",
                paid=self.paid.currency,
                seen=observation.amount.currency,
            )
        gap = self.paid - observation.amount
        return gap if not gap.is_negative else Money.zero(self.paid.currency)

    def clears_threshold(self, observation: Observation) -> bool:
        return self.drop_against(observation) >= self.threshold

    def held_drop(self) -> Observation | None:
        """The drop the desk should hear about, or None.

        The last ``hysteresis`` readings all have to clear the threshold, and
        the one reported is the dearest of them rather than the cheapest. That
        is deliberate: the cheapest of a run is the one most likely to have
        been the outlier, and quoting it to a customer who then cannot book it
        is worse than quoting a number that holds.
        """
        if not self.active or len(self.history) < self.hysteresis:
            return None
        recent = self.history[-self.hysteresis :]
        if not all(self.clears_threshold(o) for o in recent):
            return None
        return max(recent, key=lambda o: o.amount.amount)

    def quiet_run(self) -> int:
        """How many of the most recent polls found the same amount."""
        if len(self.history) < 2:
            return 0
        run = 0
        last = self.history[-1].amount
        for observation in reversed(self.history[:-1]):
            if observation.amount != last:
                break
            run += 1
        return run

    def __str__(self) -> str:
        if not self.active:
            reason = self.retired_reason.value if self.retired_reason else "no reason given"
            return f"{self.series_key} retired, {reason}"
        return f"{self.series_key} live after {self.checks} checks"


def threshold_for(change_fee: Money, margin: Money) -> Money:
    """What a drop has to beat before anybody picks up the phone.

    The fee plus the margin, because a drop that exactly covers the fee is
    worth nothing to the customer and an hour of the desk's time.
    """
    if change_fee.currency != margin.currency:
        raise ValidationError("a threshold is in one currency")
    return change_fee + margin


def observation_from(offer: Offer, seen_on: date) -> Observation:
    return Observation(
        seen_on=seen_on,
        amount=offer.breakdown.base,
        fingerprint=offer.fingerprint(),
        source=offer.source,
    )


def series_key_for(origin: str, destination: str, departure: date, cabin: Cabin) -> str:
    return f"{city_of(origin)}-{city_of(destination)}-{departure.isoformat()}-{cabin.value}"


def total_dropped(watches: list[Watch], currency: str) -> Money:
    """What every held drop across a set of watches adds up to."""
    running = Money.zero(currency)
    for watch in watches:
        held = watch.held_drop()
        if held is not None:
            running = running + watch.drop_against(held)
    return running


def default_margin(currency: str) -> Money:
    """What the desk needs on top of the fee. Twenty five, everywhere."""
    return Money(Decimal(25), currency)
