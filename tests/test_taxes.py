from __future__ import annotations

from decimal import Decimal

import pytest

from conftest import segment
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.taxes import (
    AppliedTax,
    Applies,
    Basis,
    TaxRule,
    TaxTable,
    assess,
    carrier_total,
    default_tax_table,
    government_total,
)


def rule(**over) -> TaxRule:
    base = {
        "code": "DE",
        "country": "DE",
        "description": "German air transport tax",
        "basis": Basis.FLAT,
        "amount": Decimal("70.83"),
        "currency": "EUR",
        "applies": Applies.PER_INTERNATIONAL_DEPARTURE,
    }
    base.update(over)
    return TaxRule(**base)


@pytest.mark.parametrize("code", ["D", "DEUT", "d-", ""])
def test_a_code_that_is_not_two_or_three_characters_is_refused(code):
    with pytest.raises(ValidationError):
        rule(code=code)


def test_a_tax_is_not_negative():
    with pytest.raises(ValidationError):
        rule(amount=Decimal("-1"))


def test_a_percentage_over_a_hundred_is_refused():
    with pytest.raises(ValidationError):
        rule(basis=Basis.PERCENT_OF_BASE, amount=Decimal("101"))


def test_yq_and_yr_are_marked_as_carrier_money():
    assert rule(code="YQ", country="ZZ").is_carrier_imposed
    assert rule(code="YR", country="ZZ").is_carrier_imposed
    assert not rule().is_carrier_imposed


def test_the_table_keeps_carrier_rules_out_of_the_country_index():
    table = TaxTable()
    table.add(rule())
    table.add(rule(code="YQ", country="ZZ"))
    assert len(table.for_country("DE")) == 1
    assert len(table.carrier_rules()) == 1
    assert len(table) == 2


def test_a_country_with_nothing_filed_answers_empty():
    assert TaxTable().for_country("DE") == ()


def one_way():
    return Itinerary([segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")])


def connecting():
    return Itinerary(
        [
            segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
        ]
    )


def stopping_over():
    return Itinerary(
        [
            segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
            segment("LH", "400", "FRA", "JFK", "2025-03-06 10:25", "2025-03-06 13:05"),
        ]
    )


def test_a_flat_departure_tax_is_charged_once():
    table = TaxTable()
    table.add(rule())
    owed = assess(one_way(), Money.of("500", "EUR"), table)
    assert len(owed) == 1
    assert owed[0].code == "DE"
    assert owed[0].amount == Money.of("70.83", "EUR")
    assert owed[0].point == "FRA"


def test_a_domestic_sector_does_not_owe_the_international_departure_tax():
    table = TaxTable()
    table.add(rule())
    domestic = Itinerary(
        [segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00")]
    )
    assert assess(domestic, Money.of("500", "EUR"), table) == ()


def test_a_connecting_passenger_is_exempt_and_a_stopping_one_is_not():
    table = TaxTable()
    table.add(rule(exempt_transit=True))
    assert assess(connecting(), Money.of("500", "EUR"), table) == ()
    charged = assess(stopping_over(), Money.of("500", "EUR"), table)
    assert [t.code for t in charged] == ["DE"]


def test_the_exemption_never_applies_at_the_point_the_journey_starts():
    table = TaxTable()
    table.add(rule(exempt_transit=True))
    owed = assess(one_way(), Money.of("500", "EUR"), table)
    assert len(owed) == 1


def test_without_the_exemption_the_connection_owes_it_anyway():
    table = TaxTable()
    table.add(rule(exempt_transit=False))
    assert len(assess(connecting(), Money.of("500", "EUR"), table)) == 1


def test_a_percentage_tax_reads_the_base_fare():
    table = TaxTable()
    table.add(
        rule(
            code="US",
            country="DE",
            basis=Basis.PERCENT_OF_BASE,
            amount=Decimal("7.5"),
            applies=Applies.PER_TICKET,
        )
    )
    owed = assess(one_way(), Money.of("500", "EUR"), table)
    assert owed[0].amount == Money.of("37.50", "EUR")


def test_a_per_coupon_tax_is_charged_on_every_element():
    table = TaxTable()
    table.add(rule(code="ZP", applies=Applies.PER_COUPON, amount=Decimal("5.00")))
    owed = assess(connecting(), Money.of("500", "EUR"), table)
    assert [t.point for t in owed] == ["MUC", "FRA"]


def test_a_per_coupon_tax_keys_on_the_country_the_journey_starts_in():
    # Filed by the United States, and this journey never departs from there,
    # so no coupon on it owes the charge however many sectors it has.
    table = TaxTable()
    table.add(rule(code="ZP", country="US", applies=Applies.PER_COUPON, amount=Decimal("5.00")))
    assert assess(connecting(), Money.of("500", "EUR"), table) == ()


def test_an_arrival_tax_charges_at_the_far_end():
    table = TaxTable()
    table.add(rule(code="XA", country="US", applies=Applies.PER_ARRIVAL, amount=Decimal("7.00")))
    owed = assess(one_way(), Money.of("500", "EUR"), table)
    assert [t.point for t in owed] == ["JFK"]


def test_the_carrier_surcharge_rides_along_and_stays_separate():
    table = TaxTable()
    table.add(rule())
    table.add(rule(code="YQ", country="ZZ", amount=Decimal("180.00"), applies=Applies.PER_TICKET))
    owed = assess(one_way(), Money.of("500", "EUR"), table)
    assert government_total(owed, "EUR") == Money.of("70.83", "EUR")
    assert carrier_total(owed, "EUR") == Money.of("180.00", "EUR")


def test_the_totals_are_zero_when_nothing_is_owed():
    assert government_total((), "EUR") == Money.zero("EUR")
    assert carrier_total((), "EUR") == Money.zero("EUR")


def test_an_applied_tax_reads_back_as_code_and_amount():
    applied = AppliedTax("DE", Money.of("70.83", "EUR"), "FRA")
    assert str(applied) == "DE 70.83 EUR"


def test_the_shipped_table_covers_the_markets_the_desk_sells():
    table = default_tax_table()
    assert len(table) > 8
    assert {r.code for r in table.for_country("US")} == {"US", "ZP", "XF"}
    assert [r.code for r in table.carrier_rules()] == ["YQ"]


def test_the_shipped_table_prices_a_frankfurt_departure():
    owed = assess(one_way(), Money.of("500", "EUR"), default_tax_table())
    codes = sorted(t.code for t in owed)
    assert codes == ["DE", "RA", "YQ"]
