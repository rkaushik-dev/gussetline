"""Add-on construction, which is how a fare exists for a market nobody filed.

A carrier files Frankfurt to New York and files Frankfurt to Nuremberg. It does
not file Nuremberg to New York, because there are ten thousand such pairs and
nobody is going to file them. Instead it files an add-on, an arbitrary amount
that may be added to a gateway fare to reach an interior point, and the desk
builds the fare it needs out of the two.

The construction itself is addition. Everything difficult is in what may be
added to what.

An add-on attaches at one end. Nuremberg to New York is the Frankfurt fare plus
the Nuremberg add-on at the origin end, and if the passenger also goes on to
Hartford the Hartford add-on attaches at the destination end. Two at one end is
not a construction, it is a mistake: the second add-on would be measured from
a point the first one already left.

An add-on may not be added to a constructed fare. Frankfurt to New York plus
Nuremberg gives a fare, and adding a second arbitrary to that would build a
fare out of a fare out of a fare, which no carrier prices and no audit
department will accept.

The rule and the footnote come from the gateway fare and never from the add-on.
An add-on is an amount, not a fare: it has no advance purchase, no minimum
stay, and no combinability of its own, and reading its blank rule number as
unrestricted is how a constructed fare ends up with no conditions at all.

And a constructed fare loses a tie to a published one. If a carrier has filed
the market, that fare is what it sells, whatever an arbitrary adds up to.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from gussetline.carriers import normalise_code
from gussetline.errors import ConstructionError, ValidationError
from gussetline.geography import city_of
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money, truncate_nuc
from gussetline.tariff import Fare, FareType, OwRt


class End(str, Enum):
    """Which end of the gateway fare an arbitrary attaches to."""

    ORIGIN = "origin"
    DESTINATION = "destination"


@dataclass(frozen=True, slots=True)
class AddOn:
    """One filed arbitrary: an amount between a gateway and an interior point."""

    carrier: str
    gateway: str
    point: str
    amount: Money
    end: End = End.ORIGIN
    booking_class: str = ""
    routing_number: str = ""
    fare_class: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "gateway", city_of(self.gateway))
        object.__setattr__(self, "point", city_of(self.point))
        if self.gateway == self.point:
            raise ValidationError("an add-on reaches somewhere else", point=self.point)
        if self.amount.is_negative:
            raise ValidationError("an add-on is not negative", amount=str(self.amount))

    @property
    def market(self) -> tuple[str, str]:
        """The pair this arbitrary covers, in the direction it is travelled."""
        if self.end is End.ORIGIN:
            return (self.point, self.gateway)
        return (self.gateway, self.point)

    def __str__(self) -> str:
        return f"{self.carrier} {self.point}/{self.gateway} {self.amount} at the {self.end.value}"


class AddOnStore:
    """Filed arbitraries, by carrier and by the point they reach."""

    def __init__(self) -> None:
        self._by_point: dict[tuple[str, str, str], list[AddOn]] = {}

    def add(self, addon: AddOn) -> AddOn:
        key = (addon.carrier, addon.point, addon.gateway)
        self._by_point.setdefault(key, []).append(addon)
        return addon

    def add_many(self, addons: list[AddOn]) -> None:
        for addon in addons:
            self.add(addon)

    def for_point(self, carrier: str, point: str, gateway: str, end: End) -> list[AddOn]:
        """Every arbitrary that reaches a point from a gateway, cheapest first."""
        key = (normalise_code(carrier), city_of(point), city_of(gateway))
        found = [a for a in self._by_point.get(key, []) if a.end is end]
        return sorted(found, key=lambda a: (a.amount.amount, a.point))

    def gateways(self, carrier: str, point: str) -> list[str]:
        """Every gateway an arbitrary reaches this point from."""
        code = normalise_code(carrier)
        wanted = city_of(point)
        return sorted(
            {
                gateway
                for held, held_point, gateway in self._by_point
                if held == code and held_point == wanted
            }
        )

    def __len__(self) -> int:
        return sum(len(found) for found in self._by_point.values())


@dataclass(frozen=True, slots=True)
class Constructed:
    """A fare built out of a gateway fare and one arbitrary at each end."""

    fare: Fare
    gateway_fare: Fare
    origin_addon: AddOn | None = None
    destination_addon: AddOn | None = None
    notes: tuple[str, ...] = field(default_factory=tuple)

    @property
    def arbitrary_total(self) -> Decimal:
        """What the arbitraries added, which the fare calculation line shows."""
        total = Decimal(0)
        for addon in (self.origin_addon, self.destination_addon):
            if addon is not None:
                total += addon.amount.amount
        return truncate_nuc(total)

    @property
    def ends(self) -> tuple[End, ...]:
        return tuple(
            end
            for end, addon in (
                (End.ORIGIN, self.origin_addon),
                (End.DESTINATION, self.destination_addon),
            )
            if addon is not None
        )

    def __str__(self) -> str:
        return f"{self.fare.fare_basis} built on {self.gateway_fare} plus {self.arbitrary_total}"


def construct(
    gateway_fare: Fare,
    origin_addon: AddOn | None = None,
    destination_addon: AddOn | None = None,
) -> Constructed:
    """Build the fare a market has no filing for.

    Refuses more than it accepts, and every refusal is a construction a
    carrier does not price rather than a rule this desk invented.
    """
    if origin_addon is None and destination_addon is None:
        raise ConstructionError("a construction needs an arbitrary")
    if gateway_fare.fare_type is FareType.NEGOTIATED:
        raise ConstructionError(
            "a negotiated fare is not a gateway fare", fare_basis=gateway_fare.fare_basis
        )

    _check(gateway_fare, origin_addon, End.ORIGIN)
    _check(gateway_fare, destination_addon, End.DESTINATION)

    origin = origin_addon.point if origin_addon else gateway_fare.origin
    destination = destination_addon.point if destination_addon else gateway_fare.destination
    amount = gateway_fare.amount
    for addon in (origin_addon, destination_addon):
        if addon is not None:
            amount = amount + addon.amount

    return Constructed(
        fare=Fare(
            carrier=gateway_fare.carrier,
            origin=origin,
            destination=destination,
            fare_basis=gateway_fare.fare_basis,
            amount=amount,
            owrt=gateway_fare.owrt,
            global_indicator=_indicator(gateway_fare),
            # The rule and the footnote come off the gateway fare. An add-on
            # is an amount and carries no conditions of its own, so reading
            # its blank rule number leaves the fare unrestricted.
            rule_number=gateway_fare.rule_number,
            routing_number=gateway_fare.routing_number,
            tariff_number=gateway_fare.tariff_number,
            footnote=gateway_fare.footnote,
            cabin=gateway_fare.cabin,
            fare_type=gateway_fare.fare_type,
            effective=gateway_fare.effective,
            discontinue=gateway_fare.discontinue,
            first_travel=gateway_fare.first_travel,
            last_travel=gateway_fare.last_travel,
        ),
        gateway_fare=gateway_fare,
        origin_addon=origin_addon,
        destination_addon=destination_addon,
        notes=tuple(str(a) for a in (origin_addon, destination_addon) if a is not None),
    )


def _check(gateway_fare: Fare, addon: AddOn | None, end: End) -> None:
    if addon is None:
        return
    if addon.end is not end:
        raise ConstructionError(
            "that arbitrary is filed for the other end",
            filed=addon.end.value,
            used=end.value,
        )
    if addon.carrier != gateway_fare.carrier:
        raise ConstructionError(
            "an arbitrary belongs to its own carrier",
            addon=addon.carrier,
            fare=gateway_fare.carrier,
        )
    wanted = gateway_fare.origin if end is End.ORIGIN else gateway_fare.destination
    if addon.gateway != wanted:
        raise ConstructionError(
            "the arbitrary does not meet the fare",
            gateway=addon.gateway,
            fare_point=wanted,
        )
    if addon.amount.currency != gateway_fare.amount.currency:
        raise ConstructionError(
            "an arbitrary is added in the fare's own currency",
            addon=addon.amount.currency,
            fare=gateway_fare.amount.currency,
        )


def _indicator(gateway_fare: Fare) -> GlobalIndicator:
    """The constructed fare keeps the gateway's indicator.

    An arbitrary is a short domestic hop at one end and does not change which
    ocean the journey crossed, so the indicator is the gateway fare's. This is
    the one field where the obvious alternative, working it out again from the
    new endpoints, gives the wrong answer: Nuremberg to New York over the
    Atlantic is still AT and re-deriving it from a domestic first sector is
    how a constructed fare ends up filed as EH.
    """
    return gateway_fare.global_indicator


def cheapest_for(
    carrier: str,
    origin: str,
    destination: str,
    gateway_fares: list[Fare],
    store: AddOnStore,
) -> Constructed | None:
    """The cheapest construction for a market, or nothing if there is none.

    Every gateway fare is tried against every arbitrary that reaches the two
    ends. It is a small search: a carrier files a handful of arbitraries per
    point and the fares are already narrowed to one market.
    """
    best: Constructed | None = None
    for fare in gateway_fares:
        # Nothing at an end is a real option: a market may need an arbitrary
        # at one end only, and the gateway fare already reaches the other.
        origins: list[AddOn | None] = [
            *store.for_point(carrier, origin, fare.origin, End.ORIGIN),
            None,
        ]
        endings: list[AddOn | None] = [
            *store.for_point(carrier, destination, fare.destination, End.DESTINATION),
            None,
        ]
        for left in origins:
            for right in endings:
                if left is None and right is None:
                    continue
                try:
                    made = construct(fare, left, right)
                except ConstructionError:
                    continue
                if best is None or made.fare.amount.amount < best.fare.amount.amount:
                    best = made
    return best


def prefer_published(published: list[Fare], built: Constructed | None) -> Fare | None:
    """What a market sells, when both a filing and a construction exist.

    The filing, on a tie and on anything close. If a carrier has bothered to
    file the market then that fare is what it sells, and a construction that
    happens to add up to a penny less is not a cheaper fare, it is a fare the
    carrier did not publish.
    """
    cheapest = min(published, key=lambda f: f.amount.amount, default=None)
    if cheapest is None:
        return built.fare if built is not None else None
    if built is None:
        return cheapest
    return cheapest if cheapest.amount.amount <= built.fare.amount.amount else built.fare


def one_way_only(fare: Fare) -> bool:
    """Whether a constructed fare may be doubled, which it may not.

    A carrier files round trip halves and files arbitraries as one way
    amounts, so a construction that doubles is adding the arbitrary twice for
    a passenger who only drove to the gateway once.
    """
    return fare.owrt is OwRt.ONE_WAY_ONLY
