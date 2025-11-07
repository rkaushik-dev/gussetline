"""When a held booking has to be paid for, which is three clocks at once.

A booking is held before it is ticketed, and the hold has a deadline. Miss it
and the airline cancels the booking, the fare goes back to the market, and the
passenger finds out at the airport. The desk's whole reason for holding
anything is to reissue at a better fare, so the deadline is the one date it
cannot get wrong.

Three separate clocks decide it and the earliest of them wins.

The rule's own hold, from category 5: so many days after the booking was made.
That is the one everybody knows about.

The advance purchase backstop, also category 5: so many days before departure,
whatever the hold says. A booking made four months out with a seven day hold
and a twenty one day backstop is ticketed within a week, not three months
later.

And the departure itself, which needs no rule: a ticket issued after the flight
has gone is not a ticket. It is the clock nobody files because nobody imagines
needing to, and it is the one that catches a same day booking.

The earliest wins, and which one it was is reported, because an agent asking
why a booking has to be paid for today needs the reason and not the date.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from enum import Enum

from gussetline.errors import ValidationError

# What time of day a deadline falls at when the carrier does not say. Late
# enough that a whole working day is available and early enough that the
# overnight batch has not run.
DEFAULT_HOUR = 23


class Clock(str, Enum):
    """Which of the three set the deadline."""

    HOLD = "the hold on the booking"
    ADVANCE_PURCHASE = "the advance purchase backstop"
    DEPARTURE = "the departure itself"
    IMMEDIATE = "instant purchase"

    @property
    def is_filed(self) -> bool:
        """Whether a rule set this or the calendar did."""
        return self is not Clock.DEPARTURE


@dataclass(frozen=True, slots=True)
class TimeLimit:
    """When a held booking has to be paid for, and which clock decided."""

    at: datetime
    clock: Clock
    booked: date
    departure: date

    @property
    def on(self) -> date:
        return self.at.date()

    def days_left(self, today: date) -> int:
        """Days from today until it falls. Negative once it has gone."""
        return (self.on - today).days

    def expired_on(self, today: date) -> bool:
        return today > self.on

    def is_today(self, today: date) -> bool:
        return self.on == today

    def __str__(self) -> str:
        return f"{self.at:%Y-%m-%d %H:%M} by {self.clock.value}"


def limit_for(
    booked: date,
    departure: date,
    hold_days: int | None = None,
    ticket_by_days_before: int | None = None,
    instant: bool = False,
    hour: int = DEFAULT_HOUR,
) -> TimeLimit:
    """The earliest of the three clocks, and which one it was.

    ``instant`` is the other shape of category 5, where the ticket is issued
    on booking. It beats everything, because a booking that was never held
    has no deadline to miss: it is either paid for now or it does not exist.
    """
    if departure < booked:
        raise ValidationError(
            "a booking is made before it flies",
            booked=booked.isoformat(),
            departure=departure.isoformat(),
        )
    if not 0 <= hour <= 23:
        raise ValidationError("that is not an hour of the day", hour=hour)

    if instant:
        return TimeLimit(_at(booked, hour), Clock.IMMEDIATE, booked, departure)

    candidates = [(departure, Clock.DEPARTURE)]
    if hold_days is not None:
        candidates.append((booked + timedelta(days=_days(hold_days, "hold")), Clock.HOLD))
    if ticket_by_days_before is not None:
        backstop = departure - timedelta(days=_days(ticket_by_days_before, "backstop"))
        candidates.append((max(backstop, booked), Clock.ADVANCE_PURCHASE))

    # The earliest wins, and a tie goes to the filed clock: an agent asking
    # why a booking is due today wants the rule that says so, not the
    # calendar fact that the flight is also today.
    when, clock = min(candidates, key=lambda pair: (pair[0], not pair[1].is_filed))
    return TimeLimit(_at(when, hour), clock, booked, departure)


def _days(value: int, what: str) -> int:
    if value < 0:
        raise ValidationError(f"a {what} is not negative", days=value)
    return value


def _at(day: date, hour: int) -> datetime:
    return datetime.combine(day, time(hour=hour))


def urgent(limit: TimeLimit, today: date, within: int = 1) -> bool:
    """Whether this one has to be dealt with now rather than tomorrow."""
    return limit.days_left(today) <= within


def soonest(limits: list[TimeLimit]) -> TimeLimit | None:
    """The one that falls first, which is the only one worth chasing.

    A booking with three coupons has three of these and the desk works to the
    earliest. Reporting them all is how an agent pays for the wrong one.
    """
    if not limits:
        return None
    return min(limits, key=lambda limit: limit.at)


def falling_by(limits: list[TimeLimit], today: date, within: int = 1) -> list[TimeLimit]:
    """Every limit due within a few days, soonest first."""
    due = [limit for limit in limits if limit.days_left(today) <= within]
    return sorted(due, key=lambda limit: limit.at)


def survived(limit: TimeLimit, ticketed: datetime | None) -> bool:
    """Whether the booking was paid for in time.

    Nothing ticketed at all is not survival. A booking with no ticket against
    it after its limit has gone is a booking the airline has already
    cancelled, whatever the desk's own records still say.
    """
    if ticketed is None:
        return False
    return ticketed <= limit.at
