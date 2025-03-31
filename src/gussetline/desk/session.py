"""One night's work, which is the only place the whole desk runs in order.

Everything below this can be tested on its own and is. What cannot be tested
anywhere else is the order, and the order is where the two bugs that cost real
money both lived.

Expiry comes first. A recommendation raised on Monday against a fare that has
gone is still sitting open on Thursday, and if the run raises a fresh one for
the same ticket before closing that, the desk holds two open numbers for one
phone call. Closing first means every series starts the night with at most one
open recommendation, and the rest of this file can then assume it.

Retiring comes before deciding, not after. A watch on a ticket that has been
flown, or on a fare category 31 will never allow a change to, will otherwise be
priced first and retired afterwards, which is a shopping request spent on an
answer nobody can use. The cheap check goes first and the expensive one only
runs on what survives it.

The last rule is the one that is not obvious. When the market drops again while
a recommendation is still open, the old one is withdrawn and a new one raised,
but only if the improvement is worth the churn. An agent working a queue does
not want the same ticket appearing twice in a morning because the fare moved
four euros, so a better number under the margin is simply not raised and the
run says how many it held back.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from gussetline.desk.recommend import (
    DEFAULT_LIFETIME_DAYS,
    Recommendation,
    expire_stale,
    open_on,
    raise_from,
)
from gussetline.desk.reprice import Decision, decide
from gussetline.desk.watch import Retired, Watch
from gussetline.money import Money
from gussetline.rules.base import Context
from gussetline.rules.changes import VoluntaryChange
from gussetline.rules.penalties import Penalty

# How much better a fresh recommendation has to be before it is worth
# replacing one an agent may already have in front of them.
SUPERSEDE_MARGIN = Decimal("10.00")

# What the session needs to know about a watch that only the tariff can say.
Rules = Callable[[Watch], tuple[VoluntaryChange, list[Penalty], Context]]


@dataclass
class Night:
    """What one run did, in the terms the morning report is written in."""

    on: date
    currency: str
    looked_at: int = 0
    skipped_inactive: int = 0
    expired: int = 0
    held_back: int = 0
    retired: list[tuple[str, Retired]] = field(default_factory=list)
    raised: list[Recommendation] = field(default_factory=list)
    withdrawn: list[Recommendation] = field(default_factory=list)
    decisions: list[Decision] = field(default_factory=list)

    @property
    def actionable(self) -> list[Decision]:
        return [d for d in self.decisions if d.actionable]

    @property
    def benefit(self) -> Money:
        """What the recommendations raised tonight are worth together."""
        running = Money.zero(self.currency)
        for made in self.raised:
            running = running + made.benefit
        return running

    def why_not(self) -> dict[str, int]:
        """The verdicts that were not actionable, counted."""
        counts: dict[str, int] = {}
        for decision in self.decisions:
            if decision.actionable:
                continue
            counts[decision.verdict.value] = counts.get(decision.verdict.value, 0) + 1
        return counts

    def __str__(self) -> str:
        return (
            f"{self.on.isoformat()}: looked at {self.looked_at}, "
            f"raised {len(self.raised)} worth {self.benefit}, "
            f"retired {len(self.retired)}, expired {self.expired}"
        )


def run(
    watches: Iterable[Watch],
    recommendations: list[Recommendation],
    rules: Rules,
    today: date,
    currency: str,
    *,
    lifetime: int = DEFAULT_LIFETIME_DAYS,
    margin: Decimal = SUPERSEDE_MARGIN,
) -> Night:
    """Work through every watch once, in the order that wastes least.

    ``recommendations`` is the desk's whole open book and it is written to:
    stale ones are expired and replaced ones are withdrawn in place. The ones
    raised tonight are appended, so a second watch on the same series inside
    one run sees them.
    """
    night = Night(on=today, currency=currency)
    night.expired = expire_stale(recommendations, today)

    for watch in watches:
        if not watch.active:
            night.skipped_inactive += 1
            continue

        gone = watch.should_retire(today)
        if gone is not None:
            watch.retire(gone)
            night.retired.append((watch.series_key, gone))
            continue

        night.looked_at += 1
        change_rule, penalties, context = rules(watch)
        decision = decide(watch, change_rule, penalties, context, today)
        night.decisions.append(decision)

        finished = decision.verdict.retires_the_watch
        if finished is not None:
            watch.retire(finished)
            night.retired.append((watch.series_key, finished))
            continue

        if not decision.actionable:
            continue

        _offer(night, recommendations, decision, today, lifetime, margin)

    return night


def _offer(
    night: Night,
    recommendations: list[Recommendation],
    decision: Decision,
    today: date,
    lifetime: int,
    margin: Decimal,
) -> None:
    """Raise a recommendation, unless one already open beats it."""
    standing = open_on(recommendations, decision.watch.series_key)
    if standing is not None and not _worth_replacing(standing, decision, margin):
        night.held_back += 1
        return

    made = raise_from(decision, today, lifetime)
    if standing is not None:
        standing.withdraw(today, made.fingerprint)
        night.withdrawn.append(standing)

    recommendations.append(made)
    night.raised.append(made)


def _worth_replacing(standing: Recommendation, decision: Decision, margin: Decimal) -> bool:
    """Whether the new number beats the open one by enough to be worth it.

    Currency is checked rather than assumed. A ticket repriced into another
    currency is a different conversation and the comparison here would be
    meaningless, so it always supersedes and lets a person look at both.
    """
    if standing.benefit.currency != decision.worth.currency:
        return True
    return decision.worth.amount - standing.benefit.amount >= margin
