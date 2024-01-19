from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from gussetline.errors import ValidationError
from gussetline.exchange import Converter, RateTable, default_roe_table, quarter_start
from gussetline.money import Money


@pytest.fixture
def table():
    t = RateTable("test")
    t.add("EUR", date(2025, 1, 1), "0.951")
    t.add("EUR", date(2025, 4, 1), "0.924")
    t.add("JPY", date(2025, 1, 1), "152.44")
    t.add("USD", date(2025, 1, 1), "1.000")
    return t


def test_lookup_takes_the_latest_rate_on_or_before_the_date(table):
    assert table.rate_for("EUR", date(2025, 3, 31)) == Decimal("0.951")
    assert table.rate_for("EUR", date(2025, 4, 1)) == Decimal("0.924")
    assert table.rate_for("EUR", date(2025, 9, 9)) == Decimal("0.924")


def test_lookup_before_the_first_rate_refuses_rather_than_extrapolating(table):
    with pytest.raises(ValidationError) as caught:
        table.rate_for("EUR", date(2024, 12, 31))
    assert caught.value.details["first"] == "2025-01-01"


def test_a_currency_the_table_never_held_is_a_different_complaint(table):
    with pytest.raises(ValidationError) as caught:
        table.rate_for("SEK", date(2025, 5, 1))
    assert "no rate held" in caught.value.message


def test_adding_the_same_effective_date_twice_replaces_it(table):
    table.add("EUR", date(2025, 1, 1), "0.900")
    assert table.rate_for("EUR", date(2025, 2, 1)) == Decimal("0.900")
    assert table.codes() == ["EUR", "JPY", "USD"]


def test_rates_added_out_of_order_still_sort(table):
    table.add("EUR", date(2024, 7, 1), "0.913")
    assert table.rate_for("EUR", date(2024, 8, 1)) == Decimal("0.913")
    assert table.rate_for("EUR", date(2025, 2, 1)) == Decimal("0.951")


def test_a_rate_cannot_be_zero_or_negative(table):
    with pytest.raises(ValidationError):
        table.add("EUR", date(2025, 7, 1), "0")
    with pytest.raises(ValidationError):
        table.add("EUR", date(2025, 7, 1), "-1")


@pytest.mark.parametrize(
    ("day", "expected"),
    [
        (date(2025, 1, 1), date(2025, 1, 1)),
        (date(2025, 3, 31), date(2025, 1, 1)),
        (date(2025, 4, 1), date(2025, 4, 1)),
        (date(2025, 11, 30), date(2025, 10, 1)),
    ],
)
def test_quarter_start(day, expected):
    assert quarter_start(day) == expected


def test_to_nuc_truncates_the_third_decimal(table):
    conv = Converter(table)
    # 500.00 / 0.951 is 525.762355..., and the third decimal is dropped.
    assert conv.to_nuc(Money.of("500.00", "EUR"), date(2025, 2, 1)) == Decimal("525.76")


def test_neutral_units_pass_through_untouched(table):
    conv = Converter(table)
    assert conv.to_nuc(Money.of("100.005", "NUC"), date(2025, 2, 1)) == Decimal("100.00")


def test_from_nuc_rounds_up_to_the_fare_unit(table):
    conv = Converter(table)
    # 525.76 * 152.44 is 80146.86..., and yen fares round up to the hundred.
    assert conv.from_nuc(Decimal("525.76"), "JPY", date(2025, 2, 1)) == Money.of("80200", "JPY")


def test_convert_fare_goes_through_neutral_units_and_that_shows(table):
    conv = Converter(table)
    out = conv.convert_fare(Money.of("500.00", "EUR"), "JPY", date(2025, 2, 1))
    assert out == Money.of("80200", "JPY")


def test_a_fare_converted_to_its_own_currency_can_move_and_that_is_correct(table):
    conv = Converter(table)
    # The round trip through neutral units truncates once and rounds up once,
    # so it is not the identity, and pretending it is hides real drift.
    out = conv.convert_fare(Money.of("500.00", "EUR"), "EUR", date(2025, 2, 1))
    assert out.currency == "EUR"
    assert out.amount >= Decimal("499")


def test_tax_conversion_uses_the_bankers_table_not_the_roe():
    roe = RateTable("roe")
    roe.add("EUR", date(2025, 1, 1), "0.951")
    roe.add("USD", date(2025, 1, 1), "1.000")
    bankers = RateTable("bsr")
    bankers.add("EUR", date(2025, 1, 1), "0.980")
    bankers.add("USD", date(2025, 1, 1), "1.000")
    conv = Converter(roe, bankers)
    assert conv.convert_tax(Money.of("49.00", "EUR"), "USD", date(2025, 2, 1)) == Money.of(
        "50.00", "USD"
    )


def test_tax_conversion_into_the_same_currency_is_the_identity(table):
    conv = Converter(table)
    same = Money.of("12.34", "EUR")
    assert conv.convert_tax(same, "EUR", date(2025, 2, 1)) is same


def test_without_a_bankers_table_the_roe_stands_in(table):
    conv = Converter(table)
    assert conv.bankers is table


def test_the_shipped_table_covers_three_quarters_and_the_sold_currencies():
    roe = default_roe_table()
    assert "EUR" in roe.codes()
    assert roe.rate_for("EUR", date(2024, 11, 5)) == Decimal("0.913")
    assert roe.rate_for("EUR", date(2025, 2, 5)) == Decimal("0.951")
    assert roe.rate_for("EUR", date(2025, 6, 5)) == Decimal("0.924")
    assert roe.rate_for("USD", date(2025, 6, 5)) == Decimal("1.000")
