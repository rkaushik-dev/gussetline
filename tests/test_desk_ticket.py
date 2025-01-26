from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.errors import ValidationError
from gussetline.money import Money

BEFORE = date(2025, 6, 20)
BETWEEN = date(2025, 7, 10)
AFTER = date(2025, 8, 1)


def coupon(position=0, origin="FRA", destination="JFK", when="2025-07-04 10:25", **over):
    fields = {
        "position": position,
        "carrier": "LH",
        "flight_number": "400",
        "origin": origin,
        "destination": destination,
        "departure": datetime.strptime(when, "%Y-%m-%d %H:%M"),
        "booking_class": "K",
        "fare_basis": "KLWAP7RT",
    }
    fields.update(over)
    return Coupon(**fields)


def ticket(coupons=None, **over) -> Ticket:
    fields = {
        "number": "220-1234567890",
        "record_locator": "ABC123",
        "passenger_name": "MUELLER/ANNA",
        "coupons": coupons
        if coupons is not None
        else (
            coupon(0),
            coupon(
                1, origin="JFK", destination="FRA", when="2025-07-18 18:30", flight_number="401"
            ),
        ),
        "issued_on": date(2025, 2, 14),
        "validating_carrier": "LH",
        "base_fare": Money.of("840.00", "EUR"),
        "total_paid": Money.of("1090.83", "EUR"),
    }
    fields.update(over)
    return Ticket(**fields)


def test_a_coupon_position_is_not_negative():
    with pytest.raises(ValidationError):
        coupon(position=-1)


def test_a_coupon_goes_somewhere():
    with pytest.raises(ValidationError):
        coupon(destination="FRA")


def test_a_coupon_reads_back_with_its_status():
    assert str(coupon(0)) == "0 LH400 FRAJFK OK"


def test_an_open_coupon_before_its_date_has_not_flown():
    assert not coupon(0).flown_by(BEFORE)


def test_an_open_coupon_after_its_date_counts_as_flown():
    # The carrier has not sent the used message yet and the passenger has
    # still gone, and the penalty band has still moved.
    assert coupon(0).flown_by(AFTER)


def test_a_used_status_wins_over_the_calendar():
    assert coupon(0, status=CouponStatus.USED).flown_by(BEFORE)


def test_a_refunded_coupon_is_not_flown_however_old_it_is():
    assert not coupon(0, status=CouponStatus.REFUNDED).flown_by(AFTER)


def test_a_void_coupon_is_not_flown():
    assert not coupon(0, status=CouponStatus.VOID).flown_by(AFTER)


def test_only_the_open_status_is_open():
    assert CouponStatus.OPEN.is_open
    assert not CouponStatus.USED.is_open
    assert not CouponStatus.VOID.is_open


def test_a_ticket_needs_a_coupon():
    with pytest.raises(ValidationError):
        ticket(coupons=())


def test_coupons_have_to_be_in_order():
    with pytest.raises(ValidationError):
        ticket(coupons=(coupon(1), coupon(0)))


def test_two_coupons_at_the_same_position_are_refused():
    with pytest.raises(ValidationError):
        ticket(coupons=(coupon(0), coupon(0, flight_number="401")))


def test_the_ends_come_off_the_first_and_last_coupons():
    row = ticket()
    assert row.origin == "FRA"
    assert row.destination == "FRA"
    assert row.market == ("FRA", "FRA")
    assert row.departure == date(2025, 7, 4)


def test_a_ticket_reads_back_with_what_was_paid():
    assert str(ticket()) == "220-1234567890 FRAFRA 1090.83 EUR"


def test_nothing_flown_yet_is_untouched():
    row = ticket()
    assert row.is_untouched(BEFORE)
    assert not row.is_partially_flown(BEFORE)
    assert not row.is_finished(BEFORE)
    assert len(row.open_coupons(BEFORE)) == 2


def test_one_coupon_gone_is_partially_flown():
    row = ticket()
    assert not row.is_untouched(BETWEEN)
    assert row.is_partially_flown(BETWEEN)
    assert not row.is_finished(BETWEEN)
    assert len(row.open_coupons(BETWEEN)) == 1
    assert len(row.flown_coupons(BETWEEN)) == 1


def test_everything_flown_is_finished():
    row = ticket()
    assert row.is_finished(AFTER)
    assert not row.is_partially_flown(AFTER)
    assert row.open_coupons(AFTER) == ()


def test_the_whole_fare_is_repriceable_while_nothing_has_flown():
    assert ticket().repriceable_value(BEFORE) == Money.of("840.00", "EUR")


def test_half_the_fare_is_repriceable_once_half_has_flown():
    assert ticket().repriceable_value(BETWEEN) == Money.of("420.00", "EUR")


def test_a_finished_ticket_has_nothing_left_on_the_table():
    assert ticket().repriceable_value(AFTER) == Money.zero("EUR")


def test_a_three_coupon_ticket_apportions_into_thirds():
    row = ticket(
        coupons=(
            coupon(0),
            coupon(1, origin="JFK", destination="ORD", when="2025-07-10 08:00"),
            coupon(2, origin="ORD", destination="FRA", when="2025-07-18 16:20"),
        ),
        base_fare=Money.of("900.00", "EUR"),
    )
    assert row.repriceable_value(BEFORE) == Money.of("900.00", "EUR")
    assert row.repriceable_value(date(2025, 7, 8)) == Money.of("600.00", "EUR")


def test_the_next_departure_is_the_first_open_coupon():
    row = ticket()
    assert row.next_departure(BEFORE) == date(2025, 7, 4)
    assert row.next_departure(BETWEEN) == date(2025, 7, 18)
    assert row.next_departure(AFTER) is None


def test_the_fare_bases_come_back_once_each():
    row = ticket()
    assert row.fare_bases() == ("KLWAP7RT",)
    mixed = ticket(
        coupons=(
            coupon(0),
            coupon(
                1,
                origin="JFK",
                destination="FRA",
                when="2025-07-18 18:30",
                fare_basis="QLWAP14RT",
            ),
        )
    )
    assert mixed.fare_bases() == ("KLWAP7RT", "QLWAP14RT")


def test_a_reissued_ticket_names_the_one_it_came_from():
    assert ticket(reissued_from="220-0000000001").reissued_from == "220-0000000001"


def test_the_cabin_comes_off_the_first_coupon():
    from gussetline.flights import Cabin

    row = ticket(coupons=(coupon(0, cabin=Cabin.BUSINESS),))
    assert row.cabin is Cabin.BUSINESS


def test_apportioning_never_produces_a_fraction_of_a_cent():
    row = ticket(
        coupons=(
            coupon(0),
            coupon(1, origin="JFK", destination="ORD", when="2025-07-10 08:00"),
            coupon(2, origin="ORD", destination="FRA", when="2025-07-18 16:20"),
        ),
        base_fare=Money.of("1000.00", "EUR"),
    )
    value = row.repriceable_value(date(2025, 7, 8))
    assert value.amount == Decimal("666.67")
