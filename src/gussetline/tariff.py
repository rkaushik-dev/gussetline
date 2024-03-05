"""Published fares, and the four dates every one of them carries.

A fare is not valid or invalid. It has an effective date and a discontinue
date, which say when it may be sold, and a first and last travel date, which
say when it may be flown. A ticket sold in February for travel in June is
checked against the sale window on the ticketing date and the travel window on
the departure date, and confusing the two is how a desk ends up arguing that a
fare which expired in March priced a June flight.

The other thing worth saying up front is the one way round trip indicator.
A type 2 fare is filed as half of a round trip. Its amount is not what a one
way costs, and doubling it is not optional, it is what the filing means.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.carriers import normalise_code
from gussetline.errors import NoFareFound, ValidationError
from gussetline.farebasis import FareBasis, parse_fare_basis
from gussetline.flights import Cabin
from gussetline.geography import city_of
from gussetline.indicators import GlobalIndicator, indicator_matches
from gussetline.money import Money

FAR_FUTURE = date(2999, 12, 31)


class OwRt(str, Enum):
    """What the filed amount means."""

    ONE_WAY_ONLY = "1"
    ROUND_TRIP_HALF = "2"
    ONE_WAY_MAY_DOUBLE = "3"


class FareType(str, Enum):
    PUBLISHED = "published"
    PRIVATE = "private"
    NEGOTIATED = "negotiated"
    INCLUSIVE_TOUR = "it"
    CORPORATE = "corporate"


@dataclass(frozen=True, slots=True)
class Fare:
    """One published fare, keyed the way the tariff keys it."""

    carrier: str
    origin: str
    destination: str
    fare_basis: str
    amount: Money
    owrt: OwRt
    global_indicator: GlobalIndicator
    rule_number: str
    routing_number: str
    tariff_number: str = "1"
    footnote: str = ""
    cabin: Cabin = Cabin.ECONOMY
    fare_type: FareType = FareType.PUBLISHED
    effective: date = date(1900, 1, 1)
    discontinue: date = FAR_FUTURE
    first_travel: date = date(1900, 1, 1)
    last_travel: date = FAR_FUTURE

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "origin", city_of(self.origin))
        object.__setattr__(self, "destination", city_of(self.destination))
        object.__setattr__(self, "fare_basis", self.fare_basis.strip().upper())
        if self.origin == self.destination:
            raise ValidationError("a fare goes between two places", point=self.origin)
        if self.amount.amount <= 0:
            raise ValidationError("a published fare is positive", amount=str(self.amount))
        if self.discontinue < self.effective:
            raise ValidationError(
                "the sale window closes before it opens",
                effective=self.effective.isoformat(),
                discontinue=self.discontinue.isoformat(),
            )
        if self.last_travel < self.first_travel:
            raise ValidationError("the travel window closes before it opens")

    @property
    def market(self) -> tuple[str, str]:
        return (self.origin, self.destination)

    @property
    def parsed_basis(self) -> FareBasis:
        return parse_fare_basis(self.fare_basis)

    @property
    def booking_class(self) -> str:
        return self.fare_basis[0]

    @property
    def is_half_round_trip(self) -> bool:
        return self.owrt is OwRt.ROUND_TRIP_HALF

    def sellable_on(self, ticketing: date) -> bool:
        return self.effective <= ticketing <= self.discontinue

    def flyable_on(self, departure: date) -> bool:
        return self.first_travel <= departure <= self.last_travel

    def one_way_amount(self) -> Money:
        """What a single direction costs on this filing.

        A round trip half cannot be sold on its own, so asking for its one way
        amount is a mistake worth refusing rather than answering with half a
        round trip and letting it reach an invoice.
        """
        if self.owrt is OwRt.ROUND_TRIP_HALF:
            raise ValidationError(
                "a round trip half does not have a one way amount",
                fare_basis=self.fare_basis,
            )
        return self.amount

    def round_trip_amount(self) -> Money:
        if self.owrt is OwRt.ONE_WAY_ONLY:
            raise ValidationError(
                "this fare may not be doubled",
                fare_basis=self.fare_basis,
            )
        return Money(self.amount.amount * Decimal(2), self.amount.currency)

    def key(self) -> tuple[str, str, str, str, str]:
        return (
            self.carrier,
            self.origin,
            self.destination,
            self.fare_basis,
            self.global_indicator.value,
        )

    def __str__(self) -> str:
        return f"{self.carrier} {self.origin}{self.destination} {self.fare_basis} {self.amount}"


class TariffStore:
    """Every fare the desk holds, indexed on the key a lookup actually uses."""

    def __init__(self) -> None:
        self._by_market: dict[tuple[str, str, str], list[Fare]] = {}
        self._count = 0

    def add(self, fare: Fare) -> Fare:
        key = (fare.carrier, fare.origin, fare.destination)
        self._by_market.setdefault(key, []).append(fare)
        self._count += 1
        return fare

    def add_many(self, fares: Iterable[Fare]) -> None:
        for fare in fares:
            self.add(fare)

    def __len__(self) -> int:
        return self._count

    def markets(self) -> list[tuple[str, str, str]]:
        return sorted(self._by_market)

    def search(
        self,
        carrier: str,
        origin: str,
        destination: str,
        *,
        ticketing: date | None = None,
        departure: date | None = None,
        indicator: GlobalIndicator | None = None,
        cabin: Cabin | None = None,
        booking_class: str | None = None,
    ) -> list[Fare]:
        """Every fare that could price this market, cheapest first.

        Directional on purpose. A fare filed Frankfurt to New York does not
        answer a search from New York, because the tariff publishes both
        directions separately and their amounts differ.
        """
        key = (normalise_code(carrier), city_of(origin), city_of(destination))
        found = list(self._by_market.get(key, ()))
        if ticketing is not None:
            found = [f for f in found if f.sellable_on(ticketing)]
        if departure is not None:
            found = [f for f in found if f.flyable_on(departure)]
        if indicator is not None:
            found = [f for f in found if indicator_matches(f.global_indicator, indicator)]
        if cabin is not None:
            found = [f for f in found if f.cabin is cabin]
        if booking_class is not None:
            wanted = booking_class.strip().upper()
            found = [f for f in found if f.booking_class == wanted]
        return sorted(found, key=lambda f: (f.amount.amount, f.fare_basis))

    def cheapest(self, carrier: str, origin: str, destination: str, **filters) -> Fare:
        found = self.search(carrier, origin, destination, **filters)
        if not found:
            raise NoFareFound(
                "nothing published for this market",
                carrier=carrier,
                origin=city_of(origin),
                destination=city_of(destination),
            )
        return found[0]

    def by_basis(self, carrier: str, origin: str, destination: str, fare_basis: str) -> Fare:
        wanted = fare_basis.strip().upper()
        for fare in self._by_market.get(
            (normalise_code(carrier), city_of(origin), city_of(destination)), ()
        ):
            if fare.fare_basis == wanted:
                return fare
        raise NoFareFound(
            "no fare with that basis",
            carrier=carrier,
            origin=city_of(origin),
            destination=city_of(destination),
            fare_basis=wanted,
        )
