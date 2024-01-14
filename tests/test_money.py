from __future__ import annotations

from decimal import Decimal

import pytest

from gussetline.errors import ValidationError
from gussetline.money import (
    CURRENCIES,
    NUC,
    Money,
    coerce_amount,
    currency,
    parse_money,
    round_fare,
    round_refund,
    round_tax,
    total,
    truncate_nuc,
)


def test_currency_lookup_is_case_and_space_insensitive():
    assert currency(" eur ").code == "EUR"


def test_nuc_is_reachable_by_name_but_is_not_a_selling_currency():
    assert currency("NUC") is NUC
    assert "NUC" not in CURRENCIES


def test_unknown_currency_is_refused():
    with pytest.raises(ValidationError):
        currency("XYZ")


def test_minor_unit_follows_the_exponent():
    assert currency("EUR").minor == Decimal("0.01")
    assert currency("JPY").minor == Decimal("1")
    assert currency("KWD").minor == Decimal("0.001")


def test_money_normalises_the_code_and_the_amount():
    m = Money("12.50", "eur")
    assert m.currency == "EUR"
    assert m.amount == Decimal("12.50")


def test_arithmetic_refuses_to_mix_currencies():
    with pytest.raises(ValidationError):
        Money.of(1, "EUR") + Money.of(1, "USD")
    with pytest.raises(ValidationError):
        Money.of(1, "EUR") < Money.of(1, "USD")


def test_addition_and_subtraction_stay_exact():
    assert (Money.of("0.10", "EUR") + Money.of("0.20", "EUR")).amount == Decimal("0.30")
    assert (Money.of("1.00", "EUR") - Money.of("0.30", "EUR")).amount == Decimal("0.70")


def test_multiplication_works_from_either_side():
    assert (Money.of("10", "EUR") * 3).amount == Decimal("30")
    assert (2 * Money.of("10", "EUR")).amount == Decimal("20")


def test_ordering_and_predicates():
    assert Money.of(1, "EUR") < Money.of(2, "EUR")
    assert Money.of(2, "EUR") >= Money.of(2, "EUR")
    assert Money.zero("EUR").is_zero
    assert (-Money.of(5, "EUR")).is_negative
    assert (-Money.of(5, "EUR")).abs() == Money.of(5, "EUR")


def test_fare_rounds_up_to_the_published_unit_not_the_minor_unit():
    assert round_fare(Money.of("412.01", "SEK")).amount == Decimal("415")
    assert round_fare(Money.of("18040", "JPY")).amount == Decimal("18100")
    assert round_fare(Money.of("1234", "HUF")).amount == Decimal("1300")


def test_a_fare_already_on_the_unit_does_not_move():
    assert round_fare(Money.of("415", "SEK")).amount == Decimal("415")
    assert round_fare(Money.of("18100", "JPY")).amount == Decimal("18100")


def test_tax_rounds_to_the_nearest_minor_unit_with_halves_up():
    assert round_tax(Money.of("12.344", "EUR")).amount == Decimal("12.34")
    assert round_tax(Money.of("12.345", "EUR")).amount == Decimal("12.35")
    assert round_tax(Money.of("310.5", "JPY")).amount == Decimal("311")


def test_refund_rounds_down_so_the_desk_never_owes_a_fraction():
    assert round_refund(Money.of("12.349", "EUR")).amount == Decimal("12.34")
    assert round_refund(Money.of("12.999", "KWD")).amount == Decimal("12.999")


def test_the_three_roundings_disagree_and_that_is_the_point():
    value = Money.of("101.006", "SEK")
    assert round_fare(value).amount == Decimal("105")
    assert round_tax(value).amount == Decimal("101.01")
    assert round_refund(value).amount == Decimal("101.00")


def test_neutral_units_truncate_the_third_decimal():
    assert truncate_nuc(Decimal("512.999")) == Decimal("512.99")
    assert truncate_nuc(Decimal("512.001")) == Decimal("512.00")


def test_total_adds_a_run_and_refuses_an_empty_one():
    assert total(Money.of(1, "EUR"), Money.of(2, "EUR"), Money.of(3, "EUR")).amount == Decimal(6)
    with pytest.raises(ValidationError):
        total()


@pytest.mark.parametrize("text", ["123.45EUR", "123.45 EUR", " 123.45  eur "])
def test_parse_money_reads_the_shapes_suppliers_send(text):
    assert parse_money(text) == Money.of("123.45", "EUR")


@pytest.mark.parametrize("text", ["EUR", "12", "abcEUR", ""])
def test_parse_money_refuses_the_rest(text):
    with pytest.raises(ValidationError):
        parse_money(text)


def test_coerce_amount_takes_ints_and_strings_and_decimals():
    assert coerce_amount(5) == Decimal(5)
    assert coerce_amount(" 5.25 ") == Decimal("5.25")
    assert coerce_amount(Decimal("5.25")) == Decimal("5.25")


def test_coerce_amount_refuses_a_float_because_a_fare_is_not_binary():
    with pytest.raises(ValidationError):
        coerce_amount(5.25)


def test_coerce_amount_refuses_a_bool_before_it_becomes_a_one():
    with pytest.raises(ValidationError):
        coerce_amount(True)
