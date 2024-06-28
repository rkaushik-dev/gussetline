"""Discounts by passenger type, and the two things they do not come off.

A child pays seventy five percent of the fare. That is one sentence and four
places where it goes wrong.

The percentage comes off the fare and off the Q surcharges, because those are
fare. It does not come off the mileage surcharge as a separate step, because
the mileage surcharge is already a percentage of a fare that has been
discounted. It does not come off a flat tax at all, and it does come off a
percentage tax, because a percentage tax is a percentage of a discounted fare
and follows it down without anybody doing anything.

And the age is read on the date of departure and not on the date of booking.
A child who turns twelve between the two is a child on the ticket and an adult
in the airport, and the fare that priced them is still the child fare, which
is a conversation the desk has about twice a year.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

from gussetline.carriers import normalise_code
from gussetline.errors import ValidationError
from gussetline.trace import Stage, Trace


class PassengerType(str, Enum):
    """The codes the tariff prices and the desk sells."""

    ADULT = "ADT"
    CHILD = "CHD"
    INFANT = "INF"
    INFANT_WITH_SEAT = "INS"
    YOUTH = "YTH"
    SENIOR = "SRC"
    STUDENT = "STU"

    @property
    def needs_adult(self) -> bool:
        return self in (PassengerType.CHILD, PassengerType.INFANT, PassengerType.INFANT_WITH_SEAT)

    @property
    def occupies_seat(self) -> bool:
        return self is not PassengerType.INFANT


@dataclass(frozen=True, slots=True)
class Passenger:
    """One traveller, with the date their age is read on."""

    kind: PassengerType
    born: date | None = None

    def age_on(self, when: date) -> int | None:
        """Whole years on a given date, or None when no birth date was given."""
        if self.born is None:
            return None
        years = when.year - self.born.year
        if (when.month, when.day) < (self.born.month, self.born.day):
            years -= 1
        return years

    def __str__(self) -> str:
        return self.kind.value


@dataclass(frozen=True, slots=True)
class Discount:
    """One filed discount and the passenger it is for."""

    carrier: str
    kind: PassengerType
    percent: Decimal
    designator: str = ""
    min_age: int | None = None
    max_age: int | None = None
    accompanied: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        if not 0 <= self.percent <= 100:
            raise ValidationError(
                "a discount is between nothing and everything", percent=str(self.percent)
            )
        if self.min_age is not None and self.max_age is not None and self.min_age > self.max_age:
            raise ValidationError("the age range closes before it opens")

    @property
    def multiplier(self) -> Decimal:
        return (Decimal(100) - self.percent) / Decimal(100)

    def covers(self, passenger: Passenger, departure: date) -> bool:
        """Whether this filing applies to this passenger on this date.

        A discount with an age range and a passenger with no birth date do
        not match. Assuming the passenger qualifies is how a desk sells a
        child fare to an adult and eats the difference on an audit.
        """
        if passenger.kind is not self.kind:
            return False
        if self.min_age is None and self.max_age is None:
            return True
        age = passenger.age_on(departure)
        if age is None:
            return False
        if self.min_age is not None and age < self.min_age:
            return False
        return not (self.max_age is not None and age > self.max_age)

    def apply_to(self, nuc: Decimal) -> Decimal:
        return (nuc * self.multiplier).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    def __str__(self) -> str:
        name = self.designator or self.kind.value
        return f"{self.carrier} {name} {self.percent} percent"


class DiscountStore:
    """Filed discounts by carrier, in the order they were loaded."""

    def __init__(self) -> None:
        self._by_carrier: dict[str, list[Discount]] = {}

    def add(self, discount: Discount) -> Discount:
        self._by_carrier.setdefault(discount.carrier, []).append(discount)
        return discount

    def for_carrier(self, carrier: str) -> tuple[Discount, ...]:
        return tuple(self._by_carrier.get(normalise_code(carrier), ()))

    def find(self, carrier: str, passenger: Passenger, departure: date) -> Discount | None:
        """The first filing that covers this passenger, or None.

        First and not best, because the tariff is ordered and a carrier that
        files a general child discount and then a narrower one for a specific
        market means the narrower one to be read first.
        """
        for discount in self.for_carrier(carrier):
            if discount.covers(passenger, departure):
                return discount
        return None

    def __len__(self) -> int:
        return sum(len(rows) for rows in self._by_carrier.values())


def discounted_nuc(
    nuc: Decimal,
    carrier: str,
    passenger: Passenger,
    departure: date,
    store: DiscountStore,
    trace: Trace | None = None,
) -> Decimal:
    """Take the discount off a component total, or leave it alone."""
    if passenger.kind is PassengerType.ADULT:
        return nuc
    found = store.find(carrier, passenger, departure)
    if found is None:
        if trace is not None:
            trace.note(
                Stage.SELECTION,
                f"no {passenger.kind.value} discount filed, pricing at the adult fare",
            )
        return nuc
    reduced = found.apply_to(nuc)
    if trace is not None:
        trace.passed(
            Stage.SELECTION,
            f"{found} takes {nuc} to {reduced}",
            designator=found.designator or found.kind.value,
        )
    return reduced


def default_discount_store() -> DiscountStore:
    """The discounts the desk sees on nearly every carrier it sells."""
    store = DiscountStore()
    for carrier in ("LH", "BA", "AF", "KL", "UA", "AA", "DL"):
        store.add(Discount(carrier, PassengerType.INFANT, Decimal(90), "IN90", max_age=1))
        store.add(Discount(carrier, PassengerType.INFANT_WITH_SEAT, Decimal(25), "IN25", max_age=1))
        store.add(
            Discount(carrier, PassengerType.CHILD, Decimal(25), "CH25", min_age=2, max_age=11)
        )
        store.add(
            Discount(carrier, PassengerType.YOUTH, Decimal(10), "YT10", min_age=12, max_age=24)
        )
    return store
