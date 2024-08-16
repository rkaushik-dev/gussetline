"""Categories 1 and 15, who may buy the fare and where it may be sold.

These two are the reason a consolidator exists and also the reason it gets
audited. A private fare filed for sale in Germany only is not a fare a desk in
London may issue, however good the price is, and the carrier will find out
because the country of sale is on the ticket.

Category 1 is about the passenger and category 15 is about the transaction,
and they fail differently. A passenger who does not qualify can sometimes be
swapped for one who does. A ticket sold in the wrong country cannot be fixed
after the fact at all, so the desk wants that one before the sale rather than
in an audit three months later.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from gussetline.discounts import PassengerType
from gussetline.errors import ValidationError
from gussetline.geography import COUNTRIES
from gussetline.rules.base import Category, Context, Outcome, Provision, fails, passes, silent


class FormOfPayment(str, Enum):
    ANY = "any"
    CASH = "cash"
    CARD = "card"
    INVOICE = "invoice"


@dataclass(frozen=True, slots=True)
class Sale:
    """The transaction a category 15 provision is checked against."""

    country: str
    agency: str = ""
    payment: FormOfPayment = FormOfPayment.ANY
    on: date | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "country", self.country.strip().upper())
        if self.country not in COUNTRIES:
            raise ValidationError("unknown country of sale", country=self.country)
        object.__setattr__(self, "agency", self.agency.strip().upper())


@dataclass(frozen=True, slots=True)
class Eligibility(Provision):
    """Category 1. Which passengers the fare is for."""

    passenger_types: tuple[PassengerType, ...] = field(default_factory=tuple)
    min_age: int | None = None
    max_age: int | None = None
    residency: str | None = None
    membership: str | None = None
    holds_membership: bool = False
    resident_of: str | None = None

    category: Category = Category.ELIGIBILITY

    def __post_init__(self) -> None:
        for name in ("residency", "resident_of"):
            value = getattr(self, name)
            if value is not None:
                upper = value.strip().upper()
                if upper not in COUNTRIES:
                    raise ValidationError("unknown country", field=name, country=upper)
                object.__setattr__(self, name, upper)

    @property
    def is_empty(self) -> bool:
        return (
            not self.passenger_types
            and self.min_age is None
            and self.max_age is None
            and self.residency is None
            and self.membership is None
        )

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)

        passenger = context.passenger

        if self.passenger_types and passenger.kind not in self.passenger_types:
            return fails(
                self.category,
                f"the fare is for {'/'.join(p.value for p in self.passenger_types)} "
                f"and this is a {passenger.kind.value}",
                wanted=[p.value for p in self.passenger_types],
                had=passenger.kind.value,
            )

        if self.min_age is not None or self.max_age is not None:
            age = passenger.age_on(context.journey_departure)
            if age is None:
                return fails(
                    self.category,
                    "the fare has an age condition and no date of birth was given",
                )
            if self.min_age is not None and age < self.min_age:
                return fails(self.category, f"the fare wants {self.min_age} and up", age=age)
            if self.max_age is not None and age > self.max_age:
                return fails(self.category, f"the fare wants up to {self.max_age}", age=age)

        if self.residency is not None and self.resident_of != self.residency:
            return fails(
                self.category,
                f"the fare is for residents of {self.residency}",
                wanted=self.residency,
                had=self.resident_of or "unstated",
            )

        if self.membership is not None and not self.holds_membership:
            return fails(
                self.category,
                f"the fare wants {self.membership} membership",
                membership=self.membership,
            )

        return passes(self.category, "the passenger qualifies")


@dataclass(frozen=True, slots=True)
class SalesRestriction(Provision):
    """Category 15. Where and how the ticket may be sold."""

    countries: tuple[str, ...] = field(default_factory=tuple)
    excluded_countries: tuple[str, ...] = field(default_factory=tuple)
    agencies: tuple[str, ...] = field(default_factory=tuple)
    payment: FormOfPayment = FormOfPayment.ANY
    first_sale: date | None = None
    last_sale: date | None = None
    sale: Sale | None = None

    category: Category = Category.SALES_RESTRICTIONS

    def __post_init__(self) -> None:
        for name in ("countries", "excluded_countries"):
            values = tuple(c.strip().upper() for c in getattr(self, name))
            for code in values:
                if code not in COUNTRIES:
                    raise ValidationError("unknown country", field=name, country=code)
            object.__setattr__(self, name, values)
        object.__setattr__(self, "agencies", tuple(a.strip().upper() for a in self.agencies))
        if self.countries and self.excluded_countries:
            raise ValidationError("a filing names permitted countries or excluded ones, not both")

    @property
    def is_empty(self) -> bool:
        return (
            not self.countries
            and not self.excluded_countries
            and not self.agencies
            and self.payment is FormOfPayment.ANY
            and self.first_sale is None
            and self.last_sale is None
        )

    def evaluate(self, context: Context) -> Outcome:
        if self.is_empty:
            return silent(self.category)
        if self.sale is None:
            return fails(
                self.category,
                "the fare restricts the sale and no sale details were given",
            )

        sale = self.sale

        if self.countries and sale.country not in self.countries:
            return fails(
                self.category,
                f"sold in {sale.country} and the fare is for sale in {'/'.join(self.countries)}",
                sold_in=sale.country,
                permitted=list(self.countries),
            )
        if self.excluded_countries and sale.country in self.excluded_countries:
            return fails(
                self.category,
                f"the fare is not for sale in {sale.country}",
                sold_in=sale.country,
            )
        if self.agencies and sale.agency not in self.agencies:
            return fails(
                self.category,
                f"{sale.agency or 'this agency'} is not on the fare's list",
                agency=sale.agency,
            )
        if self.payment is not FormOfPayment.ANY and sale.payment is not self.payment:
            return fails(
                self.category,
                f"the fare wants {self.payment.value} and the sale was {sale.payment.value}",
                wanted=self.payment.value,
                had=sale.payment.value,
            )

        sold_on = sale.on if sale.on is not None else context.ticketing
        if self.first_sale is not None and sold_on < self.first_sale:
            return fails(
                self.category,
                f"the fare goes on sale {self.first_sale.isoformat()}",
                first_sale=self.first_sale.isoformat(),
            )
        if self.last_sale is not None and sold_on > self.last_sale:
            return fails(
                self.category,
                f"the fare came off sale {self.last_sale.isoformat()}",
                last_sale=self.last_sale.isoformat(),
            )

        return passes(self.category, f"sale in {sale.country} is permitted")
