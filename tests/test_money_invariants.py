"""The rules about money that have to hold for every amount, not three of them.

Everything else in the suite tests a rounding against a number somebody chose,
which catches a rule written backwards and misses a rule that is right in the
middle of its range and wrong at the edges. These walk a generated table
instead: every currency the desk sells, every amount across the interesting
band, and the awkward ones written out by hand.

There is no property testing library here and there does not need to be one.
The space is small and completely known: three roundings, six currencies, and
amounts that matter to two decimals. A generated table covers it exactly and
reads as a table, which a shrinking failure from a fuzzer does not.

The direction of each rounding is the whole point and each one is wrong in a
different way. A fare rounded down is money the desk gave away on every ticket.
A tax rounded either way by a rule that is not half up disagrees with the
government's own arithmetic. A refund rounded up promises a fraction of a cent
nobody can pay.
"""

from __future__ import annotations

from decimal import ROUND_CEILING, ROUND_DOWN, ROUND_HALF_UP, Decimal

import pytest

from gussetline.money import (
    CURRENCIES,
    Money,
    currency,
    round_fare,
    round_refund,
    round_tax,
    truncate_nuc,
)

# Every currency the desk sells in, so a rounding that assumes two decimals
# fails here rather than on the first yen ticket.
CODES = sorted(CURRENCIES)

# The band that matters, plus the ends. A fare under a euro and one over ten
# thousand are both real and both rounded by the same rule.
AMOUNTS = [
    "0",
    "0.001",
    "0.004",
    "0.005",
    "0.006",
    "0.01",
    "0.49",
    "0.50",
    "0.51",
    "0.99",
    "1",
    "1.005",
    "1.015",
    "1.025",
    "7.777",
    "42.425",
    "42.435",
    "99.995",
    "420.00",
    "839.995",
    "1234.567",
    "9999.999",
]

PAIRS = [(code, Decimal(amount)) for code in CODES for amount in AMOUNTS]


def money(code: str, amount: Decimal) -> Money:
    return Money(amount, code)


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_fare_is_never_below_what_it_started_at(code, amount):
    # Rounded down is money the desk gave away on every ticket it sold.
    assert round_fare(money(code, amount)).amount >= amount


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_fare_lands_on_the_currency_own_fare_unit(code, amount):
    unit = currency(code).fare_unit
    assert round_fare(money(code, amount)).amount % unit == 0


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_fare_never_adds_more_than_one_unit(code, amount):
    unit = currency(code).fare_unit
    assert round_fare(money(code, amount)).amount - amount < unit


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_rounding_a_fare_twice_changes_nothing(code, amount):
    once = round_fare(money(code, amount))
    assert round_fare(once) == once


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_tax_matches_the_governments_own_arithmetic(code, amount):
    quantum = currency(code).quantum()
    assert round_tax(money(code, amount)).amount == amount.quantize(quantum, rounding=ROUND_HALF_UP)


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_tax_never_moves_by_more_than_half_a_unit(code, amount):
    quantum = currency(code).quantum()
    assert abs(round_tax(money(code, amount)).amount - amount) <= quantum / 2


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_rounding_a_tax_twice_changes_nothing(code, amount):
    once = round_tax(money(code, amount))
    assert round_tax(once) == once


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_refund_is_never_above_what_it_started_at(code, amount):
    # Rounded up promises a fraction of a cent nobody can pay.
    assert round_refund(money(code, amount)).amount <= amount


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounded_refund_lands_on_a_minor_unit(code, amount):
    quantum = currency(code).quantum()
    assert round_refund(money(code, amount)).amount % quantum == 0


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_rounding_a_refund_twice_changes_nothing(code, amount):
    once = round_refund(money(code, amount))
    assert round_refund(once) == once


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_refund_is_never_dearer_than_the_tax_on_the_same_amount(code, amount):
    # The three roundings are ordered, and they have to stay ordered or an
    # amount refunded is sometimes more than the amount collected.
    assert round_refund(money(code, amount)) <= round_tax(money(code, amount))


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_tax_is_never_dearer_than_the_fare_on_the_same_amount(code, amount):
    assert round_tax(money(code, amount)) <= round_fare(money(code, amount))


@pytest.mark.parametrize("amount", [Decimal(a) for a in AMOUNTS])
def test_neutral_units_drop_the_third_decimal_rather_than_rounding_it(amount):
    assert truncate_nuc(amount) == amount.quantize(Decimal("0.01"), rounding=ROUND_DOWN)


@pytest.mark.parametrize("amount", [Decimal(a) for a in AMOUNTS])
def test_truncating_neutral_units_twice_changes_nothing(amount):
    once = truncate_nuc(amount)
    assert truncate_nuc(once) == once


@pytest.mark.parametrize("amount", [Decimal(a) for a in AMOUNTS])
def test_a_truncated_amount_is_never_above_what_it_started_at(amount):
    assert truncate_nuc(amount) <= amount


def test_the_three_roundings_disagree_where_they_are_meant_to():
    # If they ever agree everywhere then two of them are not doing anything
    # and the whole file above is testing one function three times.
    awkward = Money(Decimal("42.425"), "EUR")
    assert len({round_fare(awkward), round_tax(awkward), round_refund(awkward)}) == 3


def test_every_currency_has_a_fare_unit_at_least_as_big_as_its_minor_unit():
    for code in CODES:
        held = currency(code)
        assert held.fare_unit >= held.quantum()


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_adding_two_rounded_taxes_stays_on_a_minor_unit(code, amount):
    # Where a total drifts off the unit, an invoice stops adding up.
    quantum = currency(code).quantum()
    total = round_tax(money(code, amount)) + round_tax(money(code, amount))
    assert total.amount % quantum == 0


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_rounding_never_changes_the_currency(code, amount):
    for rounded in (round_fare, round_tax, round_refund):
        assert rounded(money(code, amount)).currency == code


@pytest.mark.parametrize("code", CODES)
def test_rounding_nothing_gives_nothing(code):
    for rounded in (round_fare, round_tax, round_refund):
        assert rounded(Money.zero(code)).is_zero


@pytest.mark.parametrize(("code", "amount"), PAIRS)
def test_a_negative_amount_moves_towards_zero_and_never_past_it(code, amount):
    """What the three roundings do to a sign that should never reach them.

    A fare and a refund are both positive by construction, and a negative
    one only turns up where an arithmetic above has already gone wrong. The
    behaviour is still worth pinning, because a ceiling and a floor both
    read as "toward zero" on a negative in Decimal and somebody will one day
    decide that is a bug.

    It is not a bug and it is the safe direction. A magnitude that never
    grows means a broken arithmetic upstream produces a smaller wrong number
    rather than a larger one, and a rounding that never crosses zero means it
    cannot turn a refund into a charge.
    """
    if amount == 0:
        return
    negative = money(code, -amount)
    # Not the tax. Half up in Decimal means half away from zero, so a tax
    # of minus half a cent rounds to minus a whole one and the magnitude
    # grows. That is the right answer for a tax, which is symmetric about
    # zero by design, and it is covered by the half unit test above.
    for rounded in (round_fare, round_refund):
        result = rounded(negative).amount
        assert abs(result) <= abs(negative.amount)
        assert result <= 0


def test_the_generated_table_is_wide_enough_to_be_worth_having():
    # A guard on the guard. A table that quietly shrank to one currency and
    # three amounts would still pass every test above.
    assert len(CODES) >= 4
    assert len(PAIRS) >= 80


def test_a_currency_the_desk_does_not_sell_is_refused():
    from gussetline.errors import ValidationError

    with pytest.raises(ValidationError):
        currency("ZZZ")


def test_the_ceiling_and_the_floor_are_not_the_same_rounding():
    awkward = Decimal("1.001")
    assert awkward.quantize(Decimal("0.01"), rounding=ROUND_CEILING) != awkward.quantize(
        Decimal("0.01"), rounding=ROUND_DOWN
    )
