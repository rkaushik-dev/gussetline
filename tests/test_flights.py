from __future__ import annotations

from datetime import datetime, timedelta

import pytest

from gussetline.errors import ValidationError
from gussetline.flights import Cabin, Segment, SegmentBuilder, infer_day_change


def seg(**over):
    base = {
        "marketing_carrier": "LH",
        "flight_number": "400",
        "origin": "FRA",
        "destination": "JFK",
        "departure": datetime(2025, 3, 4, 10, 25),
        "arrival": datetime(2025, 3, 4, 13, 5),
        "booking_class": "K",
        "cabin": Cabin.ECONOMY,
    }
    base.update(over)
    return Segment(**base)


def test_cabins_rank_and_outrank():
    assert Cabin.FIRST.outranks(Cabin.BUSINESS)
    assert Cabin.BUSINESS.outranks(Cabin.PREMIUM)
    assert Cabin.PREMIUM.outranks(Cabin.ECONOMY)
    assert not Cabin.ECONOMY.outranks(Cabin.ECONOMY)


def test_a_segment_normalises_its_codes():
    s = seg(marketing_carrier="lh", origin=" fra ", destination="jfk", booking_class="k")
    assert (s.marketing_carrier, s.origin, s.destination, s.booking_class) == (
        "LH",
        "FRA",
        "JFK",
        "K",
    )


def test_a_sector_cannot_end_where_it_started():
    with pytest.raises(ValidationError):
        seg(destination="FRA")


@pytest.mark.parametrize("value", ["", "KK", "1"])
def test_a_booking_class_is_one_letter(value):
    with pytest.raises(ValidationError):
        seg(booking_class=value)


def test_a_segment_needs_a_flight_number():
    with pytest.raises(ValidationError):
        seg(flight_number="   ")


def test_negative_day_change_and_stops_are_refused():
    with pytest.raises(ValidationError):
        seg(day_change=-1)
    with pytest.raises(ValidationError):
        seg(stops=-1)


def test_designator_joins_the_carrier_and_the_number():
    assert seg(flight_number=" 400 ").designator == "LH400"


def test_operating_carrier_defaults_to_the_marketing_one():
    assert seg().operated_by == "LH"
    assert not seg().is_codeshare
    assert seg(operating_carrier="LX").operated_by == "LX"
    assert seg(operating_carrier="LX").is_codeshare


def test_a_codeshare_on_itself_is_not_a_codeshare():
    assert not seg(operating_carrier="lh").is_codeshare


def test_utc_conversion_uses_each_end_of_the_sector():
    s = seg()
    # Frankfurt is UTC+1, New York is UTC-5.
    assert s.departure_utc == datetime(2025, 3, 4, 9, 25)
    assert s.arrival_utc == datetime(2025, 3, 4, 18, 5)
    assert s.elapsed == timedelta(hours=8, minutes=40)


def test_a_day_change_moves_the_arrival_date_and_the_block_time():
    s = seg(
        origin="FRA",
        destination="NRT",
        departure=datetime(2025, 3, 4, 17, 20),
        arrival=datetime(2025, 3, 5, 11, 55),
        day_change=1,
    )
    assert s.arrival_date == s.departure_date + timedelta(days=1)
    # Frankfurt is UTC+1 and Tokyo is UTC+9, so the wall clock difference of
    # eighteen and a half hours is ten hours thirty five of block.
    assert s.elapsed == timedelta(hours=10, minutes=35)


def test_the_arrival_time_of_day_is_read_from_the_arrival_not_the_date():
    s = seg(arrival=datetime(1999, 1, 1, 13, 5), day_change=0)
    assert s.arrival_utc == datetime(2025, 3, 4, 18, 5)


def test_nonstop_is_the_default_and_a_stop_shows():
    assert seg().is_nonstop
    assert not seg(stops=1).is_nonstop


def test_the_key_is_stable_and_carries_the_departure():
    assert seg().key() == ("LH400", "FRA", "JFK", "2025-03-04T10:25:00")


def test_string_form_reads_like_a_timetable_line():
    assert str(seg()) == "LH400 FRAJFK 04Mar25 1025"


def test_infer_day_change_on_a_same_day_sector():
    assert (
        infer_day_change("FRA", "JFK", datetime(2025, 3, 4, 10, 25), datetime(2025, 3, 4, 13, 5))
        == 0
    )


def test_infer_day_change_when_the_clock_goes_backwards():
    assert (
        infer_day_change("FRA", "NRT", datetime(2025, 3, 4, 17, 20), datetime(2025, 3, 5, 11, 55))
        == 1
    )


def test_the_block_time_answers_where_the_wall_clock_only_guesses():
    # Frankfurt to Auckland the long way, twenty four hours twenty five of
    # block, landing on the day after next in Auckland's own calendar.
    assert (
        infer_day_change(
            "FRA",
            "AKL",
            datetime(2025, 3, 4, 21, 40),
            datetime(2025, 3, 6, 9, 5),
            1465,
        )
        == 2
    )


def test_the_wall_clock_alone_would_have_said_one_day_there():
    assert (
        infer_day_change("FRA", "AKL", datetime(2025, 3, 4, 21, 40), datetime(2025, 3, 6, 9, 5))
        == 1
    )


def test_a_westbound_sector_can_land_before_it_left_in_wall_clock_terms():
    assert (
        infer_day_change("NRT", "LAX", datetime(2025, 3, 4, 17, 0), datetime(2025, 3, 4, 10, 30))
        == 0
    )


def test_infer_day_change_refuses_a_negative_block():
    with pytest.raises(ValidationError):
        infer_day_change("FRA", "JFK", datetime(2025, 3, 4, 10, 0), datetime(2025, 3, 4, 12, 0), -5)


def test_the_builder_names_every_field_it_is_still_missing():
    b = SegmentBuilder(marketing_carrier="LH", flight_number="400")
    assert b.missing() == [
        "origin",
        "destination",
        "departure",
        "arrival",
        "booking_class",
        "cabin",
    ]
    with pytest.raises(ValidationError) as caught:
        b.build()
    assert "origin" in caught.value.details["missing"]


def full_builder(**over):
    b = SegmentBuilder(
        marketing_carrier="LH",
        flight_number="400",
        origin="FRA",
        destination="JFK",
        departure=datetime(2025, 3, 4, 10, 25),
        arrival=datetime(2025, 3, 4, 13, 5),
        booking_class="K",
        cabin=Cabin.ECONOMY,
    )
    for name, value in over.items():
        setattr(b, name, value)
    return b


def test_the_builder_produces_a_segment_when_it_is_complete():
    assert full_builder().build().designator == "LH400"


def test_the_builder_falls_back_to_the_clock_when_nothing_else_is_sent():
    b = full_builder(destination="NRT", arrival=datetime(2025, 3, 5, 9, 5))
    assert b.build().day_change == 1


def test_the_builder_prefers_the_block_time_over_the_clock():
    b = full_builder(
        destination="AKL",
        departure=datetime(2025, 3, 4, 21, 40),
        arrival=datetime(2025, 3, 6, 9, 5),
        elapsed_minutes=1465,
    )
    assert b.build().day_change == 2


def test_an_explicit_day_change_wins_over_both():
    b = full_builder(
        destination="AKL",
        departure=datetime(2025, 3, 4, 21, 40),
        arrival=datetime(2025, 3, 6, 9, 5),
        elapsed_minutes=1465,
        day_change=1,
    )
    assert b.build().day_change == 1
