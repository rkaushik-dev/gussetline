"""Taxes, fees and the two carrier surcharges that pretend to be taxes.

YQ and YR are not taxes. They are money the carrier keeps, filed in the tax
box because that is where there was room. They are the first thing to check
when a supplier total is high and the base fare looks right, and they are the
first thing a rival drops when it wants to look cheap, so the desk breaks them
out separately and never lets them into the tax subtotal.

The rule that produces most of the work here is the transit exemption. A
departure tax is owed by a passenger who departs, and a passenger who is only
connecting is not departing in the sense the tax means. So whether a country's
tax applies depends on whether the break at that point was a connection or a
stopover, which is a reading of the clock made in the itinerary. One rule
deciding what another rule measures is the normal state of this file.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.geography import airport_or_city_country
from gussetline.itinerary import Itinerary
from gussetline.money import Money, round_tax

_CODE = re.compile(r"^[A-Z0-9]{2,3}$")

# The two codes that are carrier money rather than government money.
CARRIER_IMPOSED = frozenset({"YQ", "YR"})


class Basis(str, Enum):
    """What the amount in the filing means."""

    FLAT = "flat"
    PERCENT_OF_BASE = "percent_of_base"


class Applies(str, Enum):
    """How many times it is charged."""

    PER_TICKET = "per_ticket"
    PER_COUPON = "per_coupon"
    PER_DEPARTURE = "per_departure"
    PER_ARRIVAL = "per_arrival"
    PER_INTERNATIONAL_DEPARTURE = "per_international_departure"


@dataclass(frozen=True, slots=True)
class TaxRule:
    """One filed tax, and the conditions under which it is owed."""

    code: str
    country: str
    description: str
    basis: Basis
    amount: Decimal
    currency: str
    applies: Applies
    exempt_transit: bool = False
    transit_hours: int = 24

    def __post_init__(self) -> None:
        object.__setattr__(self, "code", self.code.strip().upper())
        object.__setattr__(self, "country", self.country.strip().upper())
        if not _CODE.match(self.code):
            raise ValidationError("not a tax code", code=self.code)
        if self.amount < 0:
            raise ValidationError("a tax is not negative", code=self.code)
        if self.basis is Basis.PERCENT_OF_BASE and self.amount > 100:
            raise ValidationError("a percentage tax over a hundred", code=self.code)

    @property
    def is_carrier_imposed(self) -> bool:
        return self.code in CARRIER_IMPOSED


@dataclass(frozen=True, slots=True)
class AppliedTax:
    """A tax that was actually owed, and the point that made it owed."""

    code: str
    amount: Money
    point: str
    description: str = ""
    carrier_imposed: bool = False

    def __str__(self) -> str:
        return f"{self.code} {self.amount}"


class TaxTable:
    """The filed taxes, looked up by the country a point sits in."""

    def __init__(self) -> None:
        self._by_country: dict[str, list[TaxRule]] = {}
        self._carrier: list[TaxRule] = []

    def add(self, rule: TaxRule) -> TaxRule:
        if rule.is_carrier_imposed:
            self._carrier.append(rule)
        else:
            self._by_country.setdefault(rule.country, []).append(rule)
        return rule

    def add_many(self, rules: Iterable[TaxRule]) -> None:
        for rule in rules:
            self.add(rule)

    def for_country(self, code: str) -> tuple[TaxRule, ...]:
        return tuple(self._by_country.get(code.strip().upper(), ()))

    def carrier_rules(self) -> tuple[TaxRule, ...]:
        return tuple(self._carrier)

    def __len__(self) -> int:
        return sum(len(rules) for rules in self._by_country.values()) + len(self._carrier)


def _charge(rule: TaxRule, base: Money) -> Money:
    if rule.basis is Basis.FLAT:
        return round_tax(Money(rule.amount, rule.currency))
    return round_tax(Money(base.amount * rule.amount / Decimal(100), base.currency))


def assess(itinerary: Itinerary, base: Money, table: TaxTable) -> tuple[AppliedTax, ...]:
    """Work out every tax and surcharge owed on a journey.

    The itinerary is walked point by point rather than country by country,
    because a tax is owed at a point and the same country can be entered
    twice on one journey and owe twice.
    """
    owed: list[AppliedTax] = []
    stopovers = set(itinerary.stopovers())
    connections = set(itinerary.connections())

    for index, element in enumerate(itinerary.elements):
        departing = element.origin
        arriving = element.destination
        first = index == 0
        last = index == len(itinerary.elements) - 1

        for rule in table.for_country(airport_or_city_country(departing)):
            if rule.applies is Applies.PER_DEPARTURE:
                if _exempt(rule, departing, first, connections):
                    continue
                owed.append(_apply(rule, base, departing))
            elif rule.applies is Applies.PER_INTERNATIONAL_DEPARTURE:
                if airport_or_city_country(departing) == airport_or_city_country(arriving):
                    continue
                if _exempt(rule, departing, first, connections):
                    continue
                owed.append(_apply(rule, base, departing))

        for rule in table.for_country(airport_or_city_country(arriving)):
            if rule.applies is Applies.PER_ARRIVAL:
                if rule.exempt_transit and not last and arriving not in stopovers:
                    continue
                owed.append(_apply(rule, base, arriving))

    origin_country = airport_or_city_country(itinerary.origin)
    for rule in table.for_country(origin_country):
        if rule.applies is Applies.PER_TICKET:
            owed.append(_apply(rule, base, itinerary.origin))

    for rule in table.for_country(origin_country):
        if rule.applies is Applies.PER_COUPON:
            for element in itinerary.elements:
                owed.append(_apply(rule, base, element.origin))

    for rule in table.carrier_rules():
        owed.append(_apply(rule, base, itinerary.origin))

    return tuple(owed)


def _exempt(rule: TaxRule, point: str, first: bool, connections: set[str]) -> bool:
    """A connecting passenger does not depart in the sense a departure tax means."""
    if not rule.exempt_transit:
        return False
    if first:
        return False
    return point in connections


def _apply(rule: TaxRule, base: Money, point: str) -> AppliedTax:
    return AppliedTax(
        code=rule.code,
        amount=_charge(rule, base),
        point=point,
        description=rule.description,
        carrier_imposed=rule.is_carrier_imposed,
    )


def government_total(taxes: Iterable[AppliedTax], currency: str) -> Money:
    """Everything the government gets. Carrier surcharges are not in it."""
    running = Money.zero(currency)
    for tax in taxes:
        if tax.carrier_imposed:
            continue
        running = running + tax.amount
    return running


def carrier_total(taxes: Iterable[AppliedTax], currency: str) -> Money:
    running = Money.zero(currency)
    for tax in taxes:
        if tax.carrier_imposed:
            running = running + tax.amount
    return running


def default_tax_table() -> TaxTable:
    """The taxes the desk sees on the markets it actually sells."""
    table = TaxTable()
    table.add_many(
        [
            TaxRule(
                "DE",
                "DE",
                "German air transport tax",
                Basis.FLAT,
                Decimal("70.83"),
                "EUR",
                Applies.PER_INTERNATIONAL_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "RA",
                "DE",
                "German security charge",
                Basis.FLAT,
                Decimal("10.00"),
                "EUR",
                Applies.PER_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "GB",
                "GB",
                "UK air passenger duty",
                Basis.FLAT,
                Decimal("88.00"),
                "GBP",
                Applies.PER_INTERNATIONAL_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "UB",
                "GB",
                "UK passenger service charge",
                Basis.FLAT,
                Decimal("31.30"),
                "GBP",
                Applies.PER_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "US",
                "US",
                "US transportation tax",
                Basis.PERCENT_OF_BASE,
                Decimal("7.5"),
                "USD",
                Applies.PER_TICKET,
            ),
            TaxRule(
                "ZP",
                "US",
                "US flight segment tax",
                Basis.FLAT,
                Decimal("5.00"),
                "USD",
                Applies.PER_COUPON,
            ),
            TaxRule(
                "XF",
                "US",
                "US passenger facility charge",
                Basis.FLAT,
                Decimal("4.50"),
                "USD",
                Applies.PER_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "FR",
                "FR",
                "French civil aviation tax",
                Basis.FLAT,
                Decimal("14.34"),
                "EUR",
                Applies.PER_INTERNATIONAL_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "JP",
                "JP",
                "Japan international tourist tax",
                Basis.FLAT,
                Decimal("1000"),
                "JPY",
                Applies.PER_INTERNATIONAL_DEPARTURE,
            ),
            TaxRule(
                "AE",
                "AE",
                "UAE passenger service charge",
                Basis.FLAT,
                Decimal("75.00"),
                "AED",
                Applies.PER_DEPARTURE,
                exempt_transit=True,
            ),
            TaxRule(
                "YQ",
                "ZZ",
                "Carrier imposed fuel surcharge",
                Basis.FLAT,
                Decimal("180.00"),
                "EUR",
                Applies.PER_TICKET,
            ),
        ]
    )
    return table
