"""The order an agent works the open recommendations in.

Sorting by what they are worth is the obvious answer and it is wrong. A four
hundred euro saving on a March departure will still be four hundred euros
tomorrow. A ninety euro one that expires tonight is worth ninety euros today
and nothing at all in the morning, and an agent who works the list by value
loses the second one every time.

So the order is what is at risk. Three things put a recommendation at risk and
they multiply rather than add, because a recommendation that is nearly expired
on a departure next week is not twice as urgent as one of those, it is a
different kind of problem.

The lifetime is the first. A recommendation is raised against a fare that was
there when it was raised, and every day it sits the odds of that fare still
being there fall. On its last day it is nearly gone.

The departure is the second. A reissue three days before travel is a phone
call to a passenger who is packing, and it stops being possible at all once
they have flown.

And the fare having moved is the third. The collectors keep watching after a
recommendation is raised, so if the series has been seen dearer since, the
number the desk is about to quote may not be there. That demotes rather than
removes: a fare seen dearer this morning is often still available and the
passenger is the one who finds out.

The order is stable between refreshes. An agent halfway down a list that
reorders under them loses their place, which costs more than any ordering
gains, so equal scores break on the ticket number and never on anything that
moves.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from gussetline.desk.recommend import Recommendation, State
from gussetline.desk.watch import Observation
from gussetline.money import Money

# How much a recommendation on its last day is worth chasing over one raised
# this morning. Read off nothing but the desk's own preference.
EXPIRY_WEIGHT = Decimal("3.0")

# The same, for a departure that is nearly here.
DEPARTURE_WEIGHT = Decimal("2.0")

# Departures further out than this are all equally far away.
FAR_OUT_DAYS = 60

# What a recommendation is worth when the market has been seen dearer since.
MOVED_ON = Decimal("0.4")


@dataclass(frozen=True, slots=True)
class Place:
    """One recommendation and why it is where it is in the queue."""

    recommendation: Recommendation
    score: Decimal
    days_left: int
    days_out: int
    fare_moved: bool

    @property
    def worth(self) -> Money:
        return self.recommendation.benefit

    @property
    def urgent(self) -> bool:
        """Whether this one goes today whatever else is on the list."""
        return self.days_left <= 1 or self.days_out <= 3

    def why(self) -> str:
        parts = []
        if self.days_left <= 1:
            parts.append("expires today")
        elif self.days_left <= 2:
            parts.append(f"{self.days_left} days left")
        if self.days_out <= 3:
            parts.append("departs within three days")
        if self.fare_moved:
            parts.append("the market has been dearer since")
        return ", ".join(parts) or "no hurry"

    def __str__(self) -> str:
        return f"{self.recommendation.ticket_number} {self.worth} score {self.score} ({self.why()})"


def urgency(days_left: int, days_out: int) -> Decimal:
    """How much the clock is worth on one recommendation.

    One means no hurry at all. It climbs towards the weights above as either
    clock runs down, and the two multiply because being nearly out of time on
    both is a different problem from being nearly out of time on one.
    """
    return _towards(EXPIRY_WEIGHT, days_left, 3) * _towards(
        DEPARTURE_WEIGHT, days_out, FAR_OUT_DAYS
    )


def _towards(weight: Decimal, days: int, window: int) -> Decimal:
    """A multiplier that runs from the weight at nought days to one at the window."""
    if days >= window:
        return Decimal(1)
    if days <= 0:
        return weight
    left = Decimal(days) / Decimal(window)
    return weight - (weight - Decimal(1)) * left


def score(
    recommendation: Recommendation,
    today: date,
    departure: date,
    fare_moved: bool = False,
) -> Decimal:
    """What this recommendation is worth chasing right now."""
    left = (recommendation.expires_on - today).days
    out = (departure - today).days
    running = recommendation.benefit.amount * urgency(left, out)
    if fare_moved:
        running *= MOVED_ON
    return running.quantize(Decimal("0.01"))


def has_moved(raised_on: date, fingerprint: str, history: list[Observation]) -> bool:
    """Whether the series has been seen dearer since this was raised.

    Compared against the reading it was raised on, found by its fingerprint.
    A recommendation whose own reading is no longer in the history says
    nothing either way: the desk keeps a handful of readings and an old
    recommendation falls off the end of them.
    """
    raised_at = next((o for o in history if o.fingerprint == fingerprint), None)
    if raised_at is None:
        return False
    later = [o for o in history if o.seen_on > raised_on]
    return any(o.amount > raised_at.amount for o in later)


def build(
    recommendations: list[Recommendation],
    today: date,
    departures: dict[str, date],
    histories: dict[str, list[Observation]] | None = None,
) -> list[Place]:
    """The open recommendations, in the order an agent should work them.

    ``departures`` is keyed by series and comes from the caller rather than
    the recommendation, because a recommendation is a frozen statement about
    money and carries no travel date of its own.
    """
    seen = histories or {}
    places: list[Place] = []

    for made in recommendations:
        if made.state is not State.OPEN:
            continue
        departure = departures.get(made.series_key)
        if departure is None:
            continue
        moved = has_moved(made.raised_on, made.fingerprint, seen.get(made.series_key, []))
        places.append(
            Place(
                recommendation=made,
                score=score(made, today, departure, moved),
                days_left=(made.expires_on - today).days,
                days_out=(departure - today).days,
                fare_moved=moved,
            )
        )

    # Highest score first, then the ticket number. Never on anything that
    # moves, so an agent halfway down the list keeps their place.
    return sorted(places, key=lambda p: (-p.score, p.recommendation.ticket_number))


def urgent(places: list[Place]) -> list[Place]:
    """The ones that go today whatever else is on the list."""
    return [place for place in places if place.urgent]


def worth_of(places: list[Place], currency: str) -> Money:
    """What the whole queue is worth if every one of them is taken."""
    total = Money.zero(currency)
    for place in places:
        total = total + place.worth
    return total


def render(places: list[Place], limit: int = 20) -> str:
    """The queue as an agent reads it down the left of a screen."""
    if not places:
        return "nothing open"
    lines = [f"{len(places)} open, {len(urgent(places))} for today"]
    lines.extend(f"  {place}" for place in places[:limit])
    if len(places) > limit:
        lines.append(f"  and {len(places) - limit} more")
    return "\n".join(lines)
