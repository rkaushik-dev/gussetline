"""Turning provisions into rows and back, one codec per category.

A provision row has six named columns and a spill field, and every category
uses them differently: category 6 puts its days in the first number, category
16 puts its fee in the amount, category 31 puts three flags in two booleans
and the third in the spill. Nothing about that is guessable from the row, so
the mapping is written out per kind, in one place, in both directions.

Both directions together is the whole point. A codec split across two files
drifts within a month: somebody adds a field, fills it on the way in, forgets
the way out, and the value comes back empty for a year because nothing reads
it until the day something does. Here the pair sits in one function and the
round trip test walks every kind in the table below.

The spill field is a blob and it is meant to be the exception. A field goes
there only when no report will ever filter on it. Anything a report groups by
gets a column, because the alternative is a query that reads every row in the
table and parses it in Python.

Category 15 is the one that needed it, and it needed it for the two sale dates
and the form of payment. The country lists are in the text columns because
somebody does want to ask which fares may be sold in Austria.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import date
from decimal import Decimal
from typing import Any

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.persistence.models import ProvisionRow
from gussetline.rules.base import Provision
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Applies, Penalty
from gussetline.rules.purchase import AdvancePurchase, InstantPurchase
from gussetline.rules.sales import FormOfPayment, SalesRestriction
from gussetline.rules.stay import MaximumStay, MinimumStay

# What separates one spilled field from the next, and a key from its value.
PAIR = ";"
EQUALS = "="


def pack(values: dict[str, Any]) -> str:
    """Write the spill field. Empty values are left out rather than stored."""
    parts = []
    for key in sorted(values):
        value = values[key]
        if value in (None, "", ()):
            continue
        rendered = value.isoformat() if isinstance(value, date) else str(value)
        if PAIR in rendered or EQUALS in rendered:
            raise ValidationError("a spilled value cannot carry a separator", key=key)
        parts.append(f"{key}{EQUALS}{rendered}")
    return PAIR.join(parts)


def unpack(raw: str) -> dict[str, str]:
    """Read the spill field back. A field that is not there is not there."""
    found: dict[str, str] = {}
    for part in raw.split(PAIR):
        if EQUALS not in part:
            continue
        key, value = part.split(EQUALS, 1)
        if key.strip():
            found[key.strip()] = value
    return found


def _day(raw: str | None) -> date | None:
    return None if not raw else date.fromisoformat(raw)


def _advance_to_row(provision: AdvancePurchase) -> dict[str, Any]:
    return {
        "number_one": provision.reservation_days,
        "number_two": provision.ticketing_days_after_reservation,
        "detail": pack({"before": provision.ticketing_days_before_departure}),
    }


def _advance_from_row(row: ProvisionRow) -> AdvancePurchase:
    spilled = unpack(row.detail)
    before = spilled.get("before")
    return AdvancePurchase(
        reservation_days=row.number_one,
        ticketing_days_after_reservation=row.number_two,
        ticketing_days_before_departure=int(before) if before else None,
    )


def _instant_to_row(provision: InstantPurchase) -> dict[str, Any]:
    return {"number_one": provision.hours}


def _instant_from_row(row: ProvisionRow) -> InstantPurchase:
    return InstantPurchase(hours=row.number_one or 0)


def _minimum_to_row(provision: MinimumStay) -> dict[str, Any]:
    return {"number_one": provision.days, "text_one": provision.weekday or ""}


def _minimum_from_row(row: ProvisionRow) -> MinimumStay:
    return MinimumStay(days=row.number_one, weekday=row.text_one or None)


def _maximum_to_row(provision: MaximumStay) -> dict[str, Any]:
    return {
        "number_one": provision.days,
        "number_two": provision.months,
        "flag_one": provision.from_turnaround,
    }


def _maximum_from_row(row: ProvisionRow) -> MaximumStay:
    return MaximumStay(
        days=row.number_one,
        months=row.number_two,
        from_turnaround=row.flag_one,
    )


def _sales_to_row(provision: SalesRestriction) -> dict[str, Any]:
    return {
        "text_one": "/".join(provision.countries),
        "text_two": "/".join(provision.excluded_countries),
        "detail": pack(
            {
                "payment": provision.payment.value,
                "first": provision.first_sale,
                "last": provision.last_sale,
                "agencies": "|".join(provision.agencies),
            }
        ),
    }


def _sales_from_row(row: ProvisionRow) -> SalesRestriction:
    spilled = unpack(row.detail)
    agencies = spilled.get("agencies", "")
    return SalesRestriction(
        countries=tuple(part for part in row.text_one.split("/") if part),
        excluded_countries=tuple(part for part in row.text_two.split("/") if part),
        payment=FormOfPayment(spilled.get("payment", FormOfPayment.ANY.value)),
        first_sale=_day(spilled.get("first")),
        last_sale=_day(spilled.get("last")),
        agencies=tuple(part for part in agencies.split("|") if part),
    )


def _penalty_to_row(provision: Penalty) -> dict[str, Any]:
    before = provision.before_departure
    after = provision.after_departure
    return {
        "text_one": provision.action.value,
        "text_two": provision.applies.value,
        "amount": before.amount if before is not None else None,
        "currency": before.currency if before is not None else (after.currency if after else ""),
        "flag_one": provision.permitted,
        "flag_two": provision.no_show_forfeits,
        "detail": pack({"after": after.amount if after is not None else None}),
    }


def _penalty_from_row(row: ProvisionRow) -> Penalty:
    spilled = unpack(row.detail)
    after = spilled.get("after")
    return Penalty(
        action=Action(row.text_one),
        applies=Applies(row.text_two),
        before_departure=(
            Money(Decimal(str(row.amount)), row.currency) if row.amount is not None else None
        ),
        after_departure=Money(Decimal(after), row.currency) if after else None,
        permitted=row.flag_one,
        no_show_forfeits=row.flag_two,
    )


def _changes_to_row(provision: VoluntaryChange) -> dict[str, Any]:
    return {
        "text_one": provision.basis.value,
        "text_two": provision.residual.value,
        "flag_one": provision.permitted,
        "flag_two": provision.keep_fare_breaks,
        "detail": pack(
            {
                "same_carrier": int(provision.same_carrier_only),
                "once": int(provision.once_only),
            }
        ),
    }


def _changes_from_row(row: ProvisionRow) -> VoluntaryChange:
    spilled = unpack(row.detail)
    return VoluntaryChange(
        permitted=row.flag_one,
        basis=RepricingBasis(row.text_one),
        residual=Residual(row.text_two),
        keep_fare_breaks=row.flag_two,
        same_carrier_only=spilled.get("same_carrier") == "1",
        once_only=spilled.get("once") == "1",
    )


# One row per kind we can store. The name is the row's own ``kind`` column and
# it is what a migration would have to rename, so it is spelled out rather
# than taken off the class.
CODECS: dict[str, tuple[type[Provision], Callable[[Any], dict[str, Any]], Callable[..., Any]]] = {
    "advance_purchase": (AdvancePurchase, _advance_to_row, _advance_from_row),
    "instant_purchase": (InstantPurchase, _instant_to_row, _instant_from_row),
    "minimum_stay": (MinimumStay, _minimum_to_row, _minimum_from_row),
    "maximum_stay": (MaximumStay, _maximum_to_row, _maximum_from_row),
    "sales_restriction": (SalesRestriction, _sales_to_row, _sales_from_row),
    "penalty": (Penalty, _penalty_to_row, _penalty_from_row),
    "voluntary_change": (VoluntaryChange, _changes_to_row, _changes_from_row),
}

BY_TYPE: dict[type[Provision], str] = {kind: name for name, (kind, _, _) in CODECS.items()}


def kind_of(provision: Provision) -> str:
    """The name this provision is stored under, or a refusal naming its class."""
    name = BY_TYPE.get(type(provision))
    if name is None:
        raise ValidationError("no codec for that provision", provision=type(provision).__name__)
    return name


def storable(provision: Provision) -> bool:
    """Whether this provision can be written at all."""
    return type(provision) in BY_TYPE


def provision_to_row(provision: Provision, rule_id: int | None = None) -> ProvisionRow:
    """One provision as the row that holds it."""
    name = kind_of(provision)
    _, encode, _ = CODECS[name]
    # The spill is set even when it is empty. A column default only applies
    # at insert, so a row that has not been flushed yet reads it as None, and
    # every decoder here calls unpack on it.
    row = ProvisionRow(category=int(provision.category), kind=name, detail="")
    if rule_id is not None:
        row.rule_id = rule_id
    for field, value in encode(provision).items():
        setattr(row, field, value)
    return row


def row_to_provision(row: ProvisionRow) -> Provision:
    """The row back out as the provision the rule holds."""
    if row.kind not in CODECS:
        raise ValidationError("no codec for that row", kind=row.kind)
    _, _, decode = CODECS[row.kind]
    return decode(row)
