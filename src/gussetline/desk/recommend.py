"""Recommendations, and the states a person moves them through.

A decision is what the arithmetic says. A recommendation is what the desk told
a customer, and the two have to stay apart, because the arithmetic changes
every time the market moves and what was said does not.

So a recommendation is a snapshot. It copies every number it was made on, and
from then on it only changes state: somebody takes it, or kills it, or it
expires because the fare it was based on has gone. The savings report reads
the taken ones and nothing else, which is why it gives the same answer today
as it did last month.

Expiry is the part worth stating. A recommendation raised on Monday against a
fare that was gone by Wednesday is not a saving and is not a failure either,
it is the ordinary case, and counting it as either makes the desk look better
or worse than it is.

Withdrawal is the other one. The market can drop again while a recommendation
is still sitting in somebody's queue, and the desk then has two open numbers
for one ticket and one phone call to make. The old one is withdrawn rather
than killed, because killed means a person looked at it and said no, and a
report that cannot tell those apart will show a take rate falling every time
the collectors do their job well.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from enum import Enum

from gussetline.desk.reprice import Decision
from gussetline.errors import ConflictError, ValidationError
from gussetline.money import Money
from gussetline.rules.changes import Residual

# How long a quoted fare is worth chasing before it is assumed gone.
DEFAULT_LIFETIME_DAYS = 3


class State(str, Enum):
    OPEN = "open"
    TAKEN = "taken"
    KILLED = "killed"
    EXPIRED = "expired"
    SUPERSEDED = "superseded"

    @property
    def is_settled(self) -> bool:
        return self is not State.OPEN

    @property
    def counts_as_saving(self) -> bool:
        return self is State.TAKEN

    @property
    def a_person_decided(self) -> bool:
        """Whether a human settled this, which is what a take rate measures.

        The clock and the collectors settle recommendations too, and counting
        those against the desk means the take rate moves when nobody on the
        desk has done anything at all.
        """
        return self in (State.TAKEN, State.KILLED)


@dataclass
class Recommendation:
    """What the desk was told, frozen at the moment it was told."""

    ticket_number: str
    series_key: str
    raised_on: date
    paid: Money
    new_fare: Money
    penalty: Money
    benefit: Money
    residual: Residual
    fare_bases: tuple[str, ...] = field(default_factory=tuple)
    fingerprint: str = ""
    # What the fare being recommended includes. Copied like every other
    # number here, and empty where the supplier did not say, which is a
    # different thing from no allowance and reads differently on the screen.
    baggage: str = ""
    state: State = State.OPEN
    decided_on: date | None = None
    decided_by: str = ""
    note: str = ""
    replaced_by: str = ""
    lifetime_days: int = DEFAULT_LIFETIME_DAYS

    def __post_init__(self) -> None:
        for amount in (self.paid, self.new_fare, self.penalty, self.benefit):
            if amount.currency != self.paid.currency:
                raise ValidationError("a recommendation is in one currency")
        if self.lifetime_days < 1:
            raise ValidationError("a recommendation lasts at least a day")

    @property
    def currency(self) -> str:
        return self.paid.currency

    @property
    def expires_on(self) -> date:
        return self.raised_on + timedelta(days=self.lifetime_days)

    def stale_on(self, today: date) -> bool:
        return self.state is State.OPEN and today > self.expires_on

    def take(self, today: date, by: str, note: str = "") -> None:
        """The desk made the reissue. This is the only state that saves money."""
        self._settle(State.TAKEN, today, by, note)

    def kill(self, today: date, by: str, note: str = "") -> None:
        """The desk looked and decided not to. Not a saving and not a loss."""
        self._settle(State.KILLED, today, by, note)

    def expire(self, today: date) -> None:
        """Nobody got to it in time. Recorded, and counted as neither."""
        if self.state is not State.OPEN:
            raise ConflictError("only an open recommendation expires", state=self.state.value)
        self.state = State.EXPIRED
        self.decided_on = today
        self.decided_by = "the clock"

    def withdraw(self, today: date, fingerprint: str = "") -> None:
        """A better one on the same series has replaced this.

        Not killed, because nobody looked at it, and not expired, because the
        fare it was raised on may still be there. It simply is not the number
        the desk should be quoting any more.
        """
        if self.state is not State.OPEN:
            raise ConflictError("only an open recommendation is withdrawn", state=self.state.value)
        self.state = State.SUPERSEDED
        self.decided_on = today
        self.decided_by = "a better fare"
        self.replaced_by = fingerprint

    def _settle(self, state: State, today: date, by: str, note: str) -> None:
        if self.state is not State.OPEN:
            raise ConflictError(
                "this recommendation has already been settled",
                state=self.state.value,
                by=self.decided_by,
            )
        if not by.strip():
            raise ValidationError("a decision names who made it")
        self.state = state
        self.decided_on = today
        self.decided_by = by.strip()
        self.note = note

    @property
    def realised(self) -> Money:
        """What this actually saved. Zero unless somebody took it."""
        if not self.state.counts_as_saving:
            return Money.zero(self.currency)
        return self.benefit

    def __str__(self) -> str:
        if self.state is State.OPEN:
            return f"{self.ticket_number} open, worth {self.benefit}"
        return f"{self.ticket_number} {self.state.value} by {self.decided_by}"


def raise_from(
    decision: Decision, today: date, lifetime: int = DEFAULT_LIFETIME_DAYS
) -> Recommendation:
    """Turn an actionable decision into a recommendation, copying the numbers."""
    if not decision.actionable:
        raise ValidationError(
            "only an actionable decision becomes a recommendation",
            verdict=decision.verdict.value,
        )
    assert decision.observation is not None
    assert decision.penalty is not None and decision.benefit is not None
    return Recommendation(
        ticket_number=decision.watch.ticket.number,
        series_key=decision.watch.series_key,
        raised_on=today,
        paid=decision.watch.paid,
        new_fare=decision.observation.amount,
        penalty=decision.penalty,
        benefit=decision.benefit,
        residual=decision.residual,
        fare_bases=decision.watch.ticket.fare_bases(),
        fingerprint=decision.observation.fingerprint,
        baggage=str(decision.observation.baggage) if decision.observation.baggage.is_stated else "",
        lifetime_days=lifetime,
    )


def expire_stale(recommendations: list[Recommendation], today: date) -> int:
    """Close the ones nobody got to. Returns how many."""
    closed = 0
    for recommendation in recommendations:
        if recommendation.stale_on(today):
            recommendation.expire(today)
            closed += 1
    return closed


def open_ones(recommendations: list[Recommendation]) -> list[Recommendation]:
    return [r for r in recommendations if r.state is State.OPEN]


def open_on(recommendations: list[Recommendation], series_key: str) -> Recommendation | None:
    """The open recommendation for a series, if the desk already has one.

    There is at most one by construction, and the session is what keeps that
    true. Returning the newest rather than the first means a run that somehow
    left two behind still supersedes the one a person would be looking at.
    """
    found = [r for r in open_ones(recommendations) if r.series_key == series_key]
    if not found:
        return None
    return max(found, key=lambda r: r.raised_on)


def realised_total(recommendations: list[Recommendation], currency: str) -> Money:
    running = Money.zero(currency)
    for recommendation in recommendations:
        if recommendation.state.counts_as_saving:
            running = running + recommendation.realised
    return running


def take_rate(recommendations: list[Recommendation]) -> Decimal:
    """How often a recommendation a person decided was acted on.

    Measured against the ones a person decided and not against everything.
    An open recommendation has not been decided yet, and counting it as a
    refusal makes the desk look worse every morning and better every
    afternoon. An expired or withdrawn one was never a person's to decide.
    """
    decided = [r for r in recommendations if r.state.a_person_decided]
    if not decided:
        return Decimal(0)
    taken = sum(1 for r in decided if r.state.counts_as_saving)
    return (Decimal(taken) / Decimal(len(decided))).quantize(Decimal("0.01"))
