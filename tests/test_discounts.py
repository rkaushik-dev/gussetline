from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from gussetline.discounts import (
    Discount,
    DiscountStore,
    Passenger,
    PassengerType,
    default_discount_store,
    discounted_nuc,
)
from gussetline.errors import ValidationError
from gussetline.trace import Stage, Trace

DEPARTURE = date(2025, 3, 4)


def test_an_infant_does_not_occupy_a_seat_and_everybody_else_does():
    assert not PassengerType.INFANT.occupies_seat
    assert PassengerType.INFANT_WITH_SEAT.occupies_seat
    assert PassengerType.CHILD.occupies_seat


def test_children_and_infants_need_an_adult_and_youths_do_not():
    assert PassengerType.CHILD.needs_adult
    assert PassengerType.INFANT.needs_adult
    assert not PassengerType.YOUTH.needs_adult
    assert not PassengerType.ADULT.needs_adult


def test_age_is_whole_years_on_the_date_asked_for():
    born = Passenger(PassengerType.CHILD, date(2013, 6, 15))
    assert born.age_on(date(2025, 6, 14)) == 11
    assert born.age_on(date(2025, 6, 15)) == 12
    assert born.age_on(date(2025, 6, 16)) == 12


def test_a_passenger_with_no_birth_date_has_no_age():
    assert Passenger(PassengerType.CHILD).age_on(DEPARTURE) is None


def test_a_passenger_reads_back_as_its_code():
    assert str(Passenger(PassengerType.CHILD)) == "CHD"


def test_a_discount_is_between_nothing_and_everything():
    with pytest.raises(ValidationError):
        Discount("LH", PassengerType.CHILD, Decimal(-1))
    with pytest.raises(ValidationError):
        Discount("LH", PassengerType.CHILD, Decimal(101))
    assert Discount("LH", PassengerType.CHILD, Decimal(0)).multiplier == Decimal(1)
    assert Discount("LH", PassengerType.CHILD, Decimal(100)).multiplier == Decimal(0)


def test_an_age_range_that_closes_before_it_opens_is_refused():
    with pytest.raises(ValidationError):
        Discount("LH", PassengerType.CHILD, Decimal(25), min_age=11, max_age=2)


def test_the_multiplier_and_the_amount_agree():
    child = Discount("LH", PassengerType.CHILD, Decimal(25))
    assert child.multiplier == Decimal("0.75")
    assert child.apply_to(Decimal("441.64")) == Decimal("331.23")


def test_a_ninety_percent_discount_leaves_a_tenth():
    infant = Discount("LH", PassengerType.INFANT, Decimal(90))
    assert infant.apply_to(Decimal("441.64")) == Decimal("44.16")


def test_a_filing_covers_only_its_own_passenger_type():
    child = Discount("LH", PassengerType.CHILD, Decimal(25))
    assert child.covers(Passenger(PassengerType.CHILD), DEPARTURE)
    assert not child.covers(Passenger(PassengerType.INFANT), DEPARTURE)


def test_a_filing_with_no_age_range_covers_anybody_of_that_type():
    senior = Discount("LH", PassengerType.SENIOR, Decimal(10))
    assert senior.covers(Passenger(PassengerType.SENIOR), DEPARTURE)


def test_an_age_range_is_read_on_the_departure_date():
    child = Discount("LH", PassengerType.CHILD, Decimal(25), min_age=2, max_age=11)
    turning_twelve = Passenger(PassengerType.CHILD, date(2013, 6, 15))
    assert child.covers(turning_twelve, date(2025, 6, 14))
    assert not child.covers(turning_twelve, date(2025, 6, 15))


def test_an_age_range_and_no_birth_date_do_not_match():
    child = Discount("LH", PassengerType.CHILD, Decimal(25), min_age=2, max_age=11)
    assert not child.covers(Passenger(PassengerType.CHILD), DEPARTURE)


def test_a_passenger_under_the_range_is_not_covered():
    child = Discount("LH", PassengerType.CHILD, Decimal(25), min_age=2, max_age=11)
    assert not child.covers(Passenger(PassengerType.CHILD, date(2024, 6, 15)), DEPARTURE)


def test_a_discount_reads_back_with_its_designator():
    assert str(Discount("LH", PassengerType.CHILD, Decimal(25), "CH25")) == "LH CH25 25 percent"
    assert str(Discount("LH", PassengerType.CHILD, Decimal(25))) == "LH CHD 25 percent"


def test_the_store_keeps_the_order_it_was_loaded_in():
    store = DiscountStore()
    narrow = store.add(Discount("LH", PassengerType.CHILD, Decimal(50), "CH50", max_age=4))
    store.add(Discount("LH", PassengerType.CHILD, Decimal(25), "CH25", max_age=11))
    found = store.find("LH", Passenger(PassengerType.CHILD, date(2022, 1, 1)), DEPARTURE)
    assert found is narrow


def test_a_passenger_outside_the_first_filing_falls_to_the_second():
    store = DiscountStore()
    store.add(Discount("LH", PassengerType.CHILD, Decimal(50), "CH50", max_age=4))
    wide = store.add(Discount("LH", PassengerType.CHILD, Decimal(25), "CH25", max_age=11))
    found = store.find("LH", Passenger(PassengerType.CHILD, date(2016, 1, 1)), DEPARTURE)
    assert found is wide


def test_another_carriers_filing_does_not_apply():
    store = DiscountStore()
    store.add(Discount("LH", PassengerType.CHILD, Decimal(25)))
    assert store.find("BA", Passenger(PassengerType.CHILD), DEPARTURE) is None
    assert len(store) == 1


def test_an_adult_is_never_discounted():
    store = DiscountStore()
    store.add(Discount("LH", PassengerType.CHILD, Decimal(25)))
    nuc = discounted_nuc(Decimal("441.64"), "LH", Passenger(PassengerType.ADULT), DEPARTURE, store)
    assert nuc == Decimal("441.64")


def test_a_child_with_a_filing_pays_the_discounted_fare():
    store = DiscountStore()
    store.add(Discount("LH", PassengerType.CHILD, Decimal(25), "CH25"))
    trace = Trace()
    nuc = discounted_nuc(
        Decimal("441.64"), "LH", Passenger(PassengerType.CHILD), DEPARTURE, store, trace
    )
    assert nuc == Decimal("331.23")
    assert trace.for_stage(Stage.SELECTION)[0].details["designator"] == "CH25"


def test_a_child_with_no_filing_pays_the_adult_fare_and_it_is_noted():
    trace = Trace()
    nuc = discounted_nuc(
        Decimal("441.64"), "LH", Passenger(PassengerType.CHILD), DEPARTURE, DiscountStore(), trace
    )
    assert nuc == Decimal("441.64")
    assert "no CHD discount filed" in trace.for_stage(Stage.SELECTION)[0].message
    assert not trace.failed


def test_the_shipped_store_prices_the_usual_four_types():
    store = default_discount_store()
    infant = Passenger(PassengerType.INFANT, date(2024, 6, 1))
    child = Passenger(PassengerType.CHILD, date(2017, 6, 1))
    youth = Passenger(PassengerType.YOUTH, date(2007, 6, 1))
    assert discounted_nuc(Decimal("400.00"), "LH", infant, DEPARTURE, store) == Decimal("40.00")
    assert discounted_nuc(Decimal("400.00"), "LH", child, DEPARTURE, store) == Decimal("300.00")
    assert discounted_nuc(Decimal("400.00"), "LH", youth, DEPARTURE, store) == Decimal("360.00")


def test_the_shipped_store_covers_the_carriers_the_desk_sells():
    store = default_discount_store()
    assert len(store.for_carrier("LH")) == 4
    assert store.for_carrier("EK") == ()


def test_an_infant_with_a_seat_is_a_different_discount_from_one_without():
    store = default_discount_store()
    lap = Passenger(PassengerType.INFANT, date(2024, 6, 1))
    seated = Passenger(PassengerType.INFANT_WITH_SEAT, date(2024, 6, 1))
    assert discounted_nuc(Decimal("400.00"), "LH", lap, DEPARTURE, store) == Decimal("40.00")
    assert discounted_nuc(Decimal("400.00"), "LH", seated, DEPARTURE, store) == Decimal("300.00")
