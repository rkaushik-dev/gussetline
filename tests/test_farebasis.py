from __future__ import annotations

from decimal import Decimal

import pytest

from gussetline.errors import ValidationError
from gussetline.farebasis import (
    parse_designator,
    parse_fare_basis,
    same_fare_family,
)


def test_a_bare_code_gives_the_prime_and_nothing_else():
    fb = parse_fare_basis("Y")
    assert fb.prime == "Y"
    assert fb.designator is None
    assert fb.season is None
    assert fb.unparsed == ""


def test_the_prime_is_the_booking_class():
    assert parse_fare_basis("KLWAP7RT").prime == "K"
    assert parse_fare_basis("klwap7rt").prime == "K"
    assert parse_fare_basis("KLWAP7RT").matches_class("k")


def test_a_full_code_comes_apart():
    fb = parse_fare_basis("KLWAP7RT")
    assert fb.season == "low"
    assert fb.weekend is True
    assert fb.advance_days == 7
    assert fb.round_trip is True
    assert fb.unparsed == ""


def test_the_weekday_variant_reads_as_not_weekend():
    assert parse_fare_basis("KLXAP7RT").weekend is False


def test_advance_purchase_takes_up_to_three_digits():
    assert parse_fare_basis("QAP21").advance_days == 21
    assert parse_fare_basis("QAP180").advance_days == 180


def test_a_maximum_stay_in_months_is_picked_up():
    fb = parse_fare_basis("QHX3M")
    assert fb.max_stay_months == 3
    assert fb.season == "high"
    assert fb.weekend is False


def test_a_minimum_stay_is_read_with_its_unit():
    assert parse_fare_basis("QLMIN3D").min_stay == (3, "days")
    assert parse_fare_basis("QLMIN6M").min_stay == (6, "months")
    assert parse_fare_basis("QL").min_stay is None


def test_a_minimum_stay_does_not_swallow_the_maximum():
    fb = parse_fare_basis("QLMIN3D6M")
    assert fb.min_stay == (3, "days")
    assert fb.max_stay_months == 6


def test_one_way_and_round_trip_are_exclusive():
    assert parse_fare_basis("YOW").round_trip is False
    assert parse_fare_basis("YRT").round_trip is True
    assert parse_fare_basis("Y").round_trip is None


def test_an_unrecognised_tail_is_kept_and_noted():
    fb = parse_fare_basis("YZZ9")
    assert fb.unparsed == "ZZ9"
    assert fb.notes == ("unrecognised ZZ9",)
    assert fb.prime == "Y"


def test_a_code_we_cannot_decompose_is_still_a_code():
    # This is the whole point. The desk keeps pricing when a carrier files
    # something nobody here has seen before.
    fb = parse_fare_basis("VNBQWERTY")
    assert fb.raw == "VNBQWERTY"
    assert fb.prime == "V"


@pytest.mark.parametrize("text", ["", "   ", "1YRT", "Y-RT", "Y" * 40])
def test_a_shape_that_is_not_a_fare_basis_is_refused(text):
    with pytest.raises(ValidationError):
        parse_fare_basis(text)


def test_a_designator_splits_off_at_the_slash():
    fb = parse_fare_basis("KLWAP7RT/CH25")
    assert fb.body == "KLWAP7RT"
    assert fb.designator is not None
    assert fb.designator.kind == "CH"
    assert fb.designator.percent == Decimal(25)
    assert fb.is_discounted


def test_a_designator_with_no_percentage_discounts_nothing():
    fb = parse_fare_basis("YRT/IT")
    assert fb.designator is not None
    assert fb.designator.kind == "IT"
    assert not fb.is_discounted
    assert fb.designator.apply_to(Decimal(500)) == Decimal(500)


def test_a_discount_comes_off_the_amount():
    designator = parse_designator("CH25")
    assert designator.apply_to(Decimal(400)) == Decimal(300)
    assert parse_designator("ID90").apply_to(Decimal(400)) == Decimal(40)


def test_a_hundred_percent_discount_is_allowed_and_more_is_not():
    assert parse_designator("ID100").apply_to(Decimal(400)) == Decimal(0)
    with pytest.raises(ValidationError):
        parse_designator("ID101")


@pytest.mark.parametrize("text", ["", "C", "CHILD25", "2C", "CH-25"])
def test_a_shape_that_is_not_a_designator_is_refused(text):
    with pytest.raises(ValidationError):
        parse_designator(text)


def test_the_body_ignores_the_designator_for_keying():
    assert parse_fare_basis("KLWAP7RT/CH25").body == "KLWAP7RT"
    assert parse_fare_basis("KLWAP7RT").body == "KLWAP7RT"


def test_two_codes_differing_only_after_the_slash_are_one_fare():
    adult = parse_fare_basis("KLWAP7RT")
    child = parse_fare_basis("KLWAP7RT/CH25")
    assert same_fare_family(adult, child)


def test_two_different_bodies_are_two_fares():
    assert not same_fare_family(parse_fare_basis("KLWAP7RT"), parse_fare_basis("KHWAP7RT"))


def test_string_form_gives_the_code_back():
    assert str(parse_fare_basis("KLWAP7RT/CH25")) == "KLWAP7RT/CH25"
    assert str(parse_designator("CH25")) == "CH25"
