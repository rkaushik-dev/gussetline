"""The ticketed itinerary, which is not the same thing as an itinerary.

An itinerary is a plan. A ticket is a plan that has been paid for, and the
difference is the coupons: each one has a status, and a coupon that has been
flown cannot be changed, cannot be refunded, and takes the whole ticket out of
the before departure penalty band.

So the question every reprice asks first is which coupons are still open, and
the answer is a reading of the coupon statuses against today rather than a
property of the ticket. A ticket with an open outbound is worth watching. A
ticket with a flown outbound and an open return is worth watching for less,
because only half of it can be repriced. A ticket with everything flown is
worth nothing and its watch should be closed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.geography import city_of
from gussetline.money import Money


class CouponStatus(str, Enum):
    """What has happened to one coupon."""

    OPEN = "OK"
    USED = "USED"
    EXCHANGED = "EXCH"
    REFUNDED = "RFND"
    VOID = "VOID"

    @property
    def is_open(self) -> bool:
        return self is CouponStatus.OPEN


@dataclass(frozen=True, slots=True)
class Coupon:
    """One flight coupon, which is the unit a reissue actually works on."""

    position: int
    carrier: str
    flight_number: str
    origin: str
    destination: str
    departure: datetime
    booking_class: str
    fare_basis: str
    status: CouponStatus = CouponStatus.OPEN
    cabin: Cabin = Cabin.ECONOMY

    def __post_init__(self) -> None:
        if self.position < 0:
            raise ValidationError("a coupon position is not negative", value=self.position)
        if self.origin == self.destination:
            raise ValidationError("a coupon goes somewhere", point=self.origin)

    @property
    def designator(self) -> str:
        return f"{self.carrier}{self.flight_number}"

    def flown_by(self, today: date) -> bool:
        """Whether this coupon is behind us, by status or by the calendar.

        The status is authoritative when it says used, and the calendar is
        the fallback, because a carrier that has not sent the used message
        yet has still flown the passenger and the penalty band has moved.
        """
        if self.status is CouponStatus.USED:
            return True
        if not self.status.is_open:
            return False
        return self.departure.date() < today

    def __str__(self) -> str:
        return (
            f"{self.position} {self.designator} {self.origin}{self.destination} {self.status.value}"
        )


@dataclass(frozen=True, slots=True)
class Ticket:
    """One issued ticket and everything a reprice needs to know about it."""

    number: str
    record_locator: str
    passenger_name: str
    coupons: tuple[Coupon, ...]
    issued_on: date
    validating_carrier: str
    base_fare: Money
    total_paid: Money
    fare_calculation: str = ""
    country_of_sale: str = ""
    reissued_from: str = ""
    notes: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.coupons:
            raise ValidationError("a ticket has at least one coupon", number=self.number)
        positions = [c.position for c in self.coupons]
        if positions != sorted(positions):
            raise ValidationError("coupons are in order", number=self.number)
        if len(set(positions)) != len(positions):
            raise ValidationError("two coupons at the same position", number=self.number)

    @property
    def origin(self) -> str:
        return self.coupons[0].origin

    @property
    def destination(self) -> str:
        return self.coupons[-1].destination

    @property
    def market(self) -> tuple[str, str]:
        return (city_of(self.origin), city_of(self.destination))

    @property
    def departure(self) -> date:
        return self.coupons[0].departure.date()

    @property
    def cabin(self) -> Cabin:
        return self.coupons[0].cabin

    def open_coupons(self, today: date) -> tuple[Coupon, ...]:
        return tuple(c for c in self.coupons if not c.flown_by(today))

    def flown_coupons(self, today: date) -> tuple[Coupon, ...]:
        return tuple(c for c in self.coupons if c.flown_by(today))

    def is_untouched(self, today: date) -> bool:
        """Nothing flown yet, so the whole ticket can still be repriced."""
        return len(self.open_coupons(today)) == len(self.coupons)

    def is_partially_flown(self, today: date) -> bool:
        flown = len(self.flown_coupons(today))
        return 0 < flown < len(self.coupons)

    def is_finished(self, today: date) -> bool:
        return not self.open_coupons(today)

    def repriceable_value(self, today: date) -> Money:
        """How much of what was paid is still on the table.

        Apportioned by open coupons, which is rough and is what the carriers
        do. A ticket half flown is worth half its fare on a reissue, and
        arguing for a better apportionment costs more than it recovers.
        """
        if self.is_finished(today):
            return Money.zero(self.base_fare.currency)
        share = Decimal(len(self.open_coupons(today))) / Decimal(len(self.coupons))
        return Money(
            (self.base_fare.amount * share).quantize(Decimal("0.01")),
            self.base_fare.currency,
        )

    def next_departure(self, today: date) -> date | None:
        """When the passenger next flies, which is what a penalty band reads."""
        remaining = self.open_coupons(today)
        if not remaining:
            return None
        return remaining[0].departure.date()

    def fare_bases(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(c.fare_basis for c in self.coupons))

    def __str__(self) -> str:
        return f"{self.number} {self.origin}{self.destination} {self.total_paid}"
