from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from gussetline.desk.recommend import Recommendation, State
from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.desk.watch import Observation, Retired, Watch
from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.money import Money
from gussetline.persistence.deskmap import (
    apply_recommendation,
    check_series_key,
    coupon_to_row,
    latest_check,
    observation_row_to_reading,
    recommendation_to_row,
    row_to_coupon,
    row_to_recommendation,
    row_to_ticket,
    row_to_watch,
    series_key_for_row,
    ticket_to_row,
    watch_to_row,
)
from gussetline.persistence.models import ObservationRow
from gussetline.rules.changes import Residual

DEPARTURE = date(2025, 8, 4)


def coupon(position=0, origin="FRA", destination="JFK", status=CouponStatus.OPEN) -> Coupon:
    return Coupon(
        position=position,
        carrier="LH",
        flight_number="400",
        origin=origin,
        destination=destination,
        departure=datetime(2025, 8, 4, 10, 25),
        booking_class="K",
        fare_basis="KLWAP7RT",
        status=status,
    )


def ticket(*coupons: Coupon, **over) -> Ticket:
    made = {
        "number": "220-1234567890",
        "record_locator": "ABC123",
        "passenger_name": "MUELLER/ANNA",
        "coupons": coupons or (coupon(),),
        "issued_on": date(2025, 2, 14),
        "validating_carrier": "LH",
        "base_fare": Money.of("840.00", "EUR"),
        "total_paid": Money.of("1090.83", "EUR"),
    }
    made.update(over)
    return Ticket(**made)


def watch(**over) -> Watch:
    made = {
        "ticket": ticket(),
        "origin": "FRA",
        "destination": "JFK",
        "departure": DEPARTURE,
        "threshold": Money.of("50.00", "EUR"),
    }
    made.update(over)
    return Watch(**made)


def recommendation(**over) -> Recommendation:
    made = {
        "ticket_number": "220-1234567890",
        "series_key": "FRA-NYC-2025-08-04-Y",
        "raised_on": date(2025, 7, 1),
        "paid": Money.of("840.00", "EUR"),
        "new_fare": Money.of("700.00", "EUR"),
        "penalty": Money.of("50.00", "EUR"),
        "benefit": Money.of("90.00", "EUR"),
        "residual": Residual.REFUNDABLE,
        "fare_bases": ("KLWAP7RT", "MLWAP7RT"),
        "fingerprint": "f" * 32,
    }
    made.update(over)
    return Recommendation(**made)


def test_a_coupon_survives_the_round_trip():
    assert row_to_coupon(coupon_to_row(coupon())) == coupon()


def test_a_used_coupon_survives_the_round_trip():
    used = coupon(status=CouponStatus.USED)
    assert row_to_coupon(coupon_to_row(used)) == used


def test_a_used_coupon_is_marked_flown_on_the_row_as_well():
    assert coupon_to_row(coupon(status=CouponStatus.USED)).flown
    assert not coupon_to_row(coupon()).flown


def test_a_ticket_survives_the_round_trip():
    made = ticket(coupon(0), coupon(1, "JFK", "FRA"))
    assert row_to_ticket(ticket_to_row(made)) == made


def test_a_ticket_row_takes_its_market_off_its_own_coupons():
    row = ticket_to_row(ticket(coupon(0, "FRA", "MUC"), coupon(1, "MUC", "JFK")))
    assert row.origin == "FRA"
    assert row.destination == "JFK"
    assert row.departure == DEPARTURE


def test_a_ticket_keeps_its_coupons_in_order():
    made = ticket(coupon(0), coupon(1, "JFK", "FRA"))
    assert [c.position for c in ticket_to_row(made).coupons] == [0, 1]


def test_a_fare_calculation_too_long_for_the_column_is_cut_rather_than_refused():
    row = ticket_to_row(ticket(fare_calculation="X" * 900))
    assert len(row.fare_calculation) == 512


def test_a_watch_survives_the_round_trip():
    made = watch()
    row = watch_to_row(made)
    row.ticket = ticket_to_row(made.ticket)
    assert row_to_watch(row) == made


def test_a_retired_watch_keeps_its_reason():
    made = watch()
    made.retire(Retired.FLOWN)
    row = watch_to_row(made)
    row.ticket = ticket_to_row(made.ticket)
    back = row_to_watch(row)
    assert not back.active
    assert back.retired_reason is Retired.FLOWN


def test_a_reason_nobody_can_read_leaves_the_watch_retired():
    # A reason we cannot read is not a reason to start spending shopping
    # requests on the watch again.
    made = watch()
    row = watch_to_row(made)
    row.active = False
    row.retired_reason = "somebody typed this"
    row.ticket = ticket_to_row(made.ticket)
    back = row_to_watch(row)
    assert not back.active
    assert back.retired_reason is Retired.BY_HAND


def test_a_watch_comes_back_with_no_history_unless_it_is_given_some():
    made = watch()
    row = watch_to_row(made)
    row.ticket = ticket_to_row(made.ticket)
    assert row_to_watch(row).history == []


def test_a_watch_takes_whatever_history_the_caller_decided_on():
    made = watch()
    row = watch_to_row(made)
    row.ticket = ticket_to_row(made.ticket)
    readings = [Observation(seen_on=date(2025, 7, 1), amount=Money.of("700.00", "EUR"))]
    assert row_to_watch(row, readings).history == readings


def test_a_watch_in_another_cabin_survives_the_round_trip():
    made = watch(cabin=Cabin.BUSINESS)
    row = watch_to_row(made)
    row.ticket = ticket_to_row(made.ticket)
    assert row_to_watch(row).cabin is Cabin.BUSINESS


def test_an_observation_row_reads_as_what_the_customer_would_pay():
    # The total and not the base fare. A drop in the fare that a rise in tax
    # swallows is not a drop the desk can quote.
    row = ObservationRow(
        series_key="FRA-NYC-2025-08-04-Y",
        source="gds",
        fingerprint="a" * 32,
        origin="FRA",
        destination="JFK",
        departure=DEPARTURE,
        cabin="Y",
        base_amount=Decimal("620.00"),
        base_currency="EUR",
        total_amount=Decimal("790.83"),
        first_seen=datetime(2025, 7, 1, 6, 0),
        last_seen=datetime(2025, 7, 2, 6, 0),
    )
    reading = observation_row_to_reading(row)
    assert reading.amount == Money.of("790.83", "EUR")
    assert reading.seen_on == date(2025, 7, 2)
    assert reading.source == "gds"


def test_a_recommendation_survives_the_round_trip():
    made = recommendation()
    row = recommendation_to_row(made)
    assert row_to_recommendation(row, made.ticket_number, made.series_key) == made


def test_a_settled_recommendation_survives_the_round_trip():
    made = recommendation()
    made.take(date(2025, 7, 2), "anna", "customer agreed")
    row = recommendation_to_row(made)
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.state is State.TAKEN
    assert back.decided_by == "anna"
    assert back.note == "customer agreed"


def test_the_fare_bases_survive_as_a_list_and_not_as_one_string():
    made = recommendation()
    row = recommendation_to_row(made)
    assert row.fare_bases == "KLWAP7RT/MLWAP7RT"
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.fare_bases == ("KLWAP7RT", "MLWAP7RT")


def test_a_recommendation_with_no_fare_bases_comes_back_with_none():
    made = recommendation(fare_bases=())
    row = recommendation_to_row(made)
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.fare_bases == ()


def test_the_watch_id_is_set_when_it_is_given():
    assert recommendation_to_row(recommendation(), watch_id=7).watch_id == 7


def test_moving_a_stored_recommendation_writes_only_what_can_move():
    made = recommendation()
    row = recommendation_to_row(made)
    made.kill(date(2025, 7, 3), "bruno")
    apply_recommendation(row, made)
    assert row.state == "killed"
    assert row.decided_by == "bruno"
    # What the desk said is copied once and never rewritten.
    assert row.benefit_amount == Money.of("90.00", "EUR").amount


def test_a_series_key_is_worked_out_from_the_watch_own_fields():
    made = watch()
    row = watch_to_row(made)
    assert series_key_for_row(row) == "FRA-NYC-2025-08-04-Y"
    check_series_key(row)


def test_a_series_key_that_disagrees_with_its_watch_is_refused():
    # A key that does not match its own market makes the watch invisible to
    # every observation the collectors store for it.
    row = watch_to_row(watch())
    row.series_key = "FRA-LON-2025-08-04-Y"
    with pytest.raises(ValidationError, match="does not match"):
        check_series_key(row)


def test_the_last_check_reads_as_a_day_or_as_nothing():
    row = watch_to_row(watch())
    assert latest_check(row) is None
    row.last_checked = datetime(2025, 7, 2, 6, 0)
    assert latest_check(row) == date(2025, 7, 2)


def test_the_allowance_survives_the_round_trip():
    made = recommendation()
    made.baggage = "2PC"
    row = recommendation_to_row(made)
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.baggage == "2PC"


def test_the_allowance_does_not_swallow_the_note_it_rides_with():
    made = recommendation()
    made.baggage = "1PC"
    made.take(date(2025, 7, 2), "anna", "customer agreed")
    row = recommendation_to_row(made)
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.note == "customer agreed"
    assert back.baggage == "1PC"


def test_a_recommendation_with_no_allowance_reads_back_with_none():
    made = recommendation()
    row = recommendation_to_row(made)
    back = row_to_recommendation(row, made.ticket_number, made.series_key)
    assert back.baggage == ""
    assert back.note == ""


def test_a_reading_carries_what_the_fare_included():
    row = ObservationRow(
        series_key="FRA-NYC-2025-08-04-Y",
        source="gds",
        fingerprint="a" * 32,
        origin="FRA",
        destination="JFK",
        departure=DEPARTURE,
        cabin="Y",
        base_amount=Decimal("620.00"),
        base_currency="EUR",
        total_amount=Decimal("790.83"),
        bag_pieces=2,
        first_seen=datetime(2025, 7, 1, 6, 0),
        last_seen=datetime(2025, 7, 2, 6, 0),
    )
    reading = observation_row_to_reading(row)
    assert reading.baggage.pieces == 2
    assert "2PC" in str(reading)
