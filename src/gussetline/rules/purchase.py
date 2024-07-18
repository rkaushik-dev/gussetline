"""Category 5, advance reservation and ticketing.

The rule has two clocks and they do not measure the same thing. The
reservation clock runs backwards from departure and says how early the seat
has to be held. The ticketing clock runs forwards from the reservation and
says how long the hold lasts, and it is also capped by a second backwards
deadline from departure.

So the ticketing deadline is whichever of those two dates comes first, and
that is where the interesting behaviour is. A fare filed as three days after
reservation and seven days before departure gives a passenger who books six
months out three days to pay, and a passenger who books eight days out one
day, because the seven days before departure has become the binding one. Both
deadlines are in the same filing and a system that reads only one of them
prices a fare that cannot be ticketed.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

from gussetline.errors import ValidationError
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


@dataclass(frozen=True, slots=True)
class AdvancePurchase(Provision):
    """Category 5 as it is filed.

    ``reservation_days`` is days before departure the booking has to exist.
    ``ticketing_days_after_reservation`` is how long the hold lasts.
    ``ticketing_days_before_departure`` is the backstop. Any of them may be
    absent, and absent means unrestricted rather than zero.
    """

    reservation_days: int | None = None
    ticketing_days_after_reservation: int | None = None
    ticketing_days_before_departure: int | None = None
    booked_on: date | None = None

    category: Category = Category.ADVANCE_RESERVATION

    def __post_init__(self) -> None:
        for name in (
            "reservation_days",
            "ticketing_days_after_reservation",
            "ticketing_days_before_departure",
        ):
            value = getattr(self, name)
            if value is not None and value < 0:
                raise ValidationError("an advance purchase period is not negative", field=name)

    @property
    def is_empty(self) -> bool:
        return (
            self.reservation_days is None
            and self.ticketing_days_after_reservation is None
            and self.ticketing_days_before_departure is None
        )

    def latest_reservation(self, departure: date) -> date | None:
        """The last day the booking may be made."""
        if self.reservation_days is None:
            return None
        return departure - timedelta(days=self.reservation_days)

    def ticketing_deadline(self, departure: date, booked: date | None) -> date | None:
        """The earlier of the two deadlines, or None when neither is filed.

        Both are computed and then the earlier wins. Returning the first one
        found would be right about half the time and the half it is wrong
        about is the late booking, which is the one that matters.
        """
        candidates: list[date] = []
        if self.ticketing_days_after_reservation is not None and booked is not None:
            candidates.append(booked + timedelta(days=self.ticketing_days_after_reservation))
        if self.ticketing_days_before_departure is not None:
            candidates.append(departure - timedelta(days=self.ticketing_days_before_departure))
        if not candidates:
            return None
        return min(candidates)

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        departure = context.journey_departure
        booked = self.booked_on if self.booked_on is not None else context.ticketing

        latest = self.latest_reservation(departure)
        if latest is not None and booked > latest:
            return fails(
                self.category,
                f"booking has to be {self.reservation_days} days out and it is "
                f"{(departure - booked).days}",
                needed=self.reservation_days,
                had=(departure - booked).days,
            )

        deadline = self.ticketing_deadline(departure, booked)
        if deadline is not None and context.ticketing > deadline:
            return fails(
                self.category,
                f"ticketing deadline was {deadline.isoformat()} and it was sold "
                f"{context.ticketing.isoformat()}",
                deadline=deadline.isoformat(),
                sold=context.ticketing.isoformat(),
            )

        return passes(
            self.category,
            "advance purchase satisfied",
            deadline=deadline.isoformat() if deadline else "none",
        )


@dataclass(frozen=True, slots=True)
class InstantPurchase(Provision):
    """The other shape of category 5, where the ticket is issued on booking.

    Filed as a number of hours rather than days, because it is usually one or
    twenty four and a day granularity cannot tell those apart.
    """

    hours: int = 0
    booked_at: date | None = None

    category: Category = Category.ADVANCE_RESERVATION

    def __post_init__(self) -> None:
        if self.hours < 0:
            raise ValidationError("an instant purchase window is not negative", hours=self.hours)

    def evaluate(self, context: Context) -> Outcome:
        if self.hours == 0:
            return silent(self.category)
        booked = self.booked_at if self.booked_at is not None else context.ticketing
        deadline = booked + timedelta(days=max(1, self.hours // 24))
        if context.ticketing > deadline:
            return fails(
                self.category,
                f"instant purchase within {self.hours} hours and it was later",
                hours=self.hours,
            )
        return passes(self.category, f"ticketed inside the {self.hours} hour window")
