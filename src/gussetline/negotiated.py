"""Negotiated fares, which are the ones that are not in the tariff at all.

A published fare is filed for everybody. A negotiated fare is filed for one
agency or one corporate account, it is not visible to anybody else, and it is
the reason two agencies quote different numbers for the same flight on the same
morning.

Three things follow and all of them bite.

A negotiated fare is only visible to a caller who presents the code it was
filed against. Not preferred, not cheaper: invisible. A desk that shows one to
a caller without the code has published somebody else's commercial terms, which
is a contract breach and not a pricing bug.

A negotiated fare beats a published one at the same price, which is the opposite
of the add-on rule and for the opposite reason. An arbitrary is a construction
the carrier did not publish, so the published fare wins. A negotiated fare is
what the carrier agreed with this customer specifically, so it wins.

And a negotiated fare inherits its conditions from a published one it names.
The carrier does not refile twenty categories to change a number, it files an
amount and points at the rule the public fare uses. Reading its own blank rule
number as unrestricted gives the customer a fully flexible ticket at a discount
fare price, which is the most expensive way to be wrong here.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from gussetline.carriers import normalise_code
from gussetline.errors import ValidationError
from gussetline.geography import city_of
from gussetline.money import Money
from gussetline.tariff import Fare, FareType

# What a caller presents to see the fares filed against it.
MAX_CODE = 20


@dataclass(frozen=True, slots=True)
class Account:
    """Who a negotiated fare is filed for."""

    code: str
    name: str = ""

    def __post_init__(self) -> None:
        cleaned = self.code.strip().upper()
        if not cleaned:
            raise ValidationError("an account has a code")
        if len(cleaned) > MAX_CODE:
            raise ValidationError("that is not an account code", code=self.code)
        object.__setattr__(self, "code", cleaned)

    def __str__(self) -> str:
        return f"{self.code} {self.name}".strip()


@dataclass(frozen=True, slots=True)
class Negotiated:
    """One privately filed fare, and the public fare it takes its rule from."""

    account: Account
    fare: Fare
    inherits_from: str = ""
    notes: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.fare.fare_type not in (FareType.NEGOTIATED, FareType.PRIVATE):
            raise ValidationError(
                "a negotiated fare is filed as one", fare_type=self.fare.fare_type.value
            )

    @property
    def carrier(self) -> str:
        return self.fare.carrier

    @property
    def market(self) -> tuple[str, str]:
        return self.fare.market

    def __str__(self) -> str:
        return f"{self.fare} for {self.account.code}"


class NegotiatedStore:
    """Private fares, keyed on the account they are invisible without."""

    def __init__(self) -> None:
        self._by_account: dict[tuple[str, str, str, str], list[Negotiated]] = {}

    def add(self, filed: Negotiated) -> Negotiated:
        key = (
            filed.account.code,
            normalise_code(filed.carrier),
            filed.fare.origin,
            filed.fare.destination,
        )
        self._by_account.setdefault(key, []).append(filed)
        return filed

    def add_many(self, filed: list[Negotiated]) -> None:
        for one in filed:
            self.add(one)

    def for_account(
        self,
        codes: list[str],
        carrier: str,
        origin: str,
        destination: str,
        on: date | None = None,
    ) -> list[Negotiated]:
        """Every private fare a caller presenting these codes may see.

        Nothing at all when no code is presented. Not the cheapest published
        one, not a fallback: a caller without a code has no business seeing
        another customer's terms and gets an empty list.
        """
        found: list[Negotiated] = []
        for code in dict.fromkeys(c.strip().upper() for c in codes if c and c.strip()):
            key = (code, normalise_code(carrier), city_of(origin), city_of(destination))
            for filed in self._by_account.get(key, []):
                if on is None or filed.fare.sellable_on(on):
                    found.append(filed)
        return sorted(found, key=lambda f: (f.fare.amount.amount, f.fare.fare_basis))

    def accounts(self) -> list[str]:
        return sorted({code for code, _, _, _ in self._by_account})

    def __len__(self) -> int:
        return sum(len(found) for found in self._by_account.values())


def with_inherited_rule(filed: Negotiated, published: list[Fare]) -> Fare:
    """The private fare, carrying the rule of the public fare it names.

    A carrier does not refile twenty categories to change a number. Reading
    the private fare's own blank rule number as unrestricted hands the
    customer a fully flexible ticket at a discount price, which is the most
    expensive way to be wrong here.
    """
    if not filed.inherits_from:
        if filed.fare.rule_number:
            return filed.fare
        raise ValidationError(
            "a negotiated fare needs a rule of its own or one to inherit",
            fare_basis=filed.fare.fare_basis,
        )

    parent = next((f for f in published if f.fare_basis == filed.inherits_from), None)
    if parent is None:
        raise ValidationError(
            "the fare it inherits from is not loaded",
            fare_basis=filed.fare.fare_basis,
            inherits_from=filed.inherits_from,
        )

    return Fare(
        carrier=filed.fare.carrier,
        origin=filed.fare.origin,
        destination=filed.fare.destination,
        fare_basis=filed.fare.fare_basis,
        amount=filed.fare.amount,
        owrt=filed.fare.owrt,
        global_indicator=filed.fare.global_indicator,
        rule_number=parent.rule_number,
        routing_number=filed.fare.routing_number or parent.routing_number,
        tariff_number=parent.tariff_number,
        footnote=parent.footnote,
        cabin=filed.fare.cabin,
        fare_type=filed.fare.fare_type,
        effective=filed.fare.effective,
        discontinue=filed.fare.discontinue,
        first_travel=filed.fare.first_travel,
        last_travel=filed.fare.last_travel,
    )


def best_for(
    published: list[Fare],
    private: list[Negotiated],
) -> Fare | None:
    """What this caller may actually buy, out of both kinds.

    A negotiated fare wins a tie, which is the opposite of the add-on rule
    and for the opposite reason: an arbitrary is a construction the carrier
    did not publish, and a negotiated fare is what the carrier agreed with
    this customer by name.
    """
    cheapest_public = min(published, key=lambda f: f.amount.amount, default=None)
    cheapest_private = min(private, key=lambda f: f.fare.amount.amount, default=None)

    if cheapest_private is None:
        return cheapest_public
    if cheapest_public is None:
        return cheapest_private.fare
    if cheapest_private.fare.amount.amount <= cheapest_public.amount.amount:
        return cheapest_private.fare
    return cheapest_public


def saving_against(published: list[Fare], filed: Negotiated) -> Money:
    """What the private fare is worth over the best public one. Never negative."""
    cheapest = min(published, key=lambda f: f.amount.amount, default=None)
    if cheapest is None:
        return Money.zero(filed.fare.amount.currency)
    gap = cheapest.amount - filed.fare.amount
    return gap if not gap.is_negative else Money.zero(filed.fare.amount.currency)


def visible_to(filed: Negotiated, codes: list[str]) -> bool:
    """Whether a caller presenting these codes may see this fare at all."""
    return filed.account.code in {c.strip().upper() for c in codes if c and c.strip()}
