from __future__ import annotations

from datetime import date

import pytest

from gussetline.errors import ValidationError
from gussetline.flights import YearRoll
from gussetline.itinerary import SurfaceSector
from gussetline.spec import month_of, nominal_block, parse, render

JULY = date(2025, 7, 22)


def test_a_single_sector_reads():
    made = parse("FRA LH400 K 04AUG JFK", JULY)
    assert len(made.segments) == 1
    leg = made.segments[0]
    assert leg.marketing_carrier == "LH"
    assert leg.flight_number == "400"
    assert leg.booking_class == "K"
    assert leg.origin == "FRA"
    assert leg.destination == "JFK"
    assert leg.departure.date() == date(2025, 8, 4)


def test_a_round_trip_reads_both_ways():
    made = parse("FRA LH400 K 04AUG JFK LH401 K 18AUG FRA", JULY)
    assert [s.destination for s in made.segments] == ["JFK", "FRA"]
    assert made.origin == "FRA"
    assert made.destination == "FRA"


def test_the_entry_is_case_insensitive():
    assert len(parse("fra lh400 k 04aug jfk", JULY).segments) == 1


def test_commas_are_allowed_between_sectors():
    made = parse("FRA LH400 K 04AUG JFK, JFK LH401 K 18AUG FRA", JULY)
    assert len(made.segments) == 2


def test_an_entry_that_does_not_join_up_is_refused():
    with pytest.raises(ValidationError):
        parse("FRA LH400 K 04AUG JFK LH401 K 18AUG FRA LH900 K 20AUG JFK MUC", JULY)


def test_the_year_comes_from_the_day_being_priced():
    made = parse("FRA LH400 K 04AUG JFK", date(2025, 7, 22))
    assert made.segments[0].departure.year == 2025


def test_a_month_already_past_is_next_year():
    # Priced in December for a January departure. Reading that as this
    # January puts the outbound eleven months in the past.
    made = parse("FRA LH400 K 04JAN JFK", date(2025, 12, 20))
    assert made.segments[0].departure.date() == date(2026, 1, 4)


def test_the_year_rolls_forward_across_a_new_year_inside_one_entry():
    made = parse("FRA LH400 K 28DEC JFK LH401 K 04JAN FRA", date(2025, 12, 1))
    out, back = made.segments
    assert out.departure.date() == date(2025, 12, 28)
    assert back.departure.date() == date(2026, 1, 4)
    assert (back.departure.date() - out.departure.date()).days == 7


def test_the_year_never_rolls_backwards():
    made = parse("FRA LH400 K 28DEC JFK LH401 K 04JAN FRA LH900 K 20JAN JFK", date(2025, 12, 1))
    assert [s.departure.year for s in made.segments] == [2025, 2026, 2026]


def test_two_sectors_on_one_day_do_not_roll_anything():
    made = parse("FRA LH100 K 04AUG MUC LH400 K 04AUG JFK", JULY)
    assert [s.departure.date() for s in made.segments] == [date(2025, 8, 4)] * 2


def test_a_surface_sector_is_two_slashes():
    made = parse("FRA LH100 K 04AUG MUC // VIE LH500 K 18AUG FRA", JULY)
    assert len(made.elements) == 3
    assert isinstance(made.elements[1], SurfaceSector)
    assert made.elements[1].origin == "MUC"
    assert made.elements[1].destination == "VIE"


def test_a_surface_sector_with_nowhere_to_land_is_refused():
    with pytest.raises(ValidationError, match="somewhere to arrive"):
        parse("FRA LH100 K 04AUG MUC //", JULY)


def test_an_entry_that_opens_with_a_surface_sector_is_refused():
    with pytest.raises(ValidationError):
        parse("FRA // MUC LH400 K 04AUG JFK", JULY)


def test_a_time_belongs_to_the_date_and_not_the_flight():
    made = parse("FRA LH400 K 04AUG/1025 JFK", JULY)
    leg = made.segments[0]
    assert leg.departure.hour == 10
    assert leg.departure.minute == 25
    assert not leg.estimated


def test_a_sector_with_no_time_gets_a_made_up_one_and_says_so():
    leg = parse("FRA LH400 K 04AUG JFK", JULY).segments[0]
    assert leg.departure.hour == 12
    assert leg.estimated
    assert leg.elapsed_minutes == nominal_block("FRA", "JFK")


def test_a_made_up_block_is_never_under_an_hour():
    assert nominal_block("FRA", "MUC") == 60


def test_a_made_up_block_grows_with_the_distance():
    assert nominal_block("FRA", "JFK") > nominal_block("FRA", "MUC")


def test_a_westbound_crossing_lands_on_the_day_it_left():
    leg = parse("FRA LH400 K 04AUG JFK", JULY).segments[0]
    assert leg.day_change == 0


def test_an_eastbound_crossing_lands_the_next_day():
    leg = parse("JFK LH401 K 04AUG FRA", JULY).segments[0]
    assert leg.day_change == 1


def test_a_point_nobody_has_heard_of_is_refused_by_name():
    with pytest.raises(ValidationError, match="airport"):
        parse("FRA LH400 K 04AUG ZZZ", JULY)


def test_something_that_is_not_a_point_is_refused():
    with pytest.raises(ValidationError, match="not a point"):
        parse("FRANKFURT LH400 K 04AUG JFK", JULY)


def test_something_that_is_not_a_flight_is_refused():
    with pytest.raises(ValidationError, match="not a flight"):
        parse("FRA LUFTHANSA K 04AUG JFK", JULY)


def test_a_booking_class_of_more_than_one_letter_is_refused():
    with pytest.raises(ValidationError, match="one letter"):
        parse("FRA LH400 KK 04AUG JFK", JULY)


def test_a_month_nobody_files_is_refused():
    with pytest.raises(ValidationError, match="not a month"):
        parse("FRA LH400 K 04XXX JFK", JULY)


def test_a_day_that_does_not_exist_is_refused():
    with pytest.raises(ValidationError, match="no such day"):
        parse("FRA LH400 K 31FEB JFK", JULY)


def test_a_time_that_does_not_exist_is_refused():
    with pytest.raises(ValidationError, match="not a time"):
        parse("FRA LH400 K 04AUG/2599 JFK", JULY)


def test_a_sector_missing_its_last_token_is_refused_with_what_it_saw():
    with pytest.raises(ValidationError, match="a flight, a class, a date"):
        parse("FRA LH400 K 04AUG", JULY)


def test_an_empty_entry_is_refused():
    with pytest.raises(ValidationError, match="nothing to price"):
        parse("   ", JULY)


def test_a_point_on_its_own_is_not_an_itinerary():
    with pytest.raises(ValidationError):
        parse("FRA", JULY)


def test_a_flight_number_keeps_its_significant_digits():
    assert parse("FRA LH0400 K 04AUG JFK", JULY).segments[0].flight_number == "400"


def test_an_entry_writes_back_out_the_way_it_was_typed():
    entry = "FRA LH400 K 04AUG JFK LH401 K 18AUG FRA"
    assert render(parse(entry, JULY)) == entry


def test_a_surface_sector_survives_the_round_trip():
    entry = "FRA LH100 K 04AUG MUC // VIE LH500 K 18AUG FRA"
    assert render(parse(entry, JULY)) == entry


def test_a_month_reads_by_its_three_letters():
    assert month_of("aug") == 8
    with pytest.raises(ValidationError):
        month_of("august")


def test_a_year_roll_from_a_year_starts_where_it_is_told():
    roll = YearRoll(2025)
    assert roll.resolve(8, 4) == date(2025, 8, 4)
    assert roll.resolve(1, 4) == date(2026, 1, 4)


def test_a_year_roll_from_a_day_already_knows_the_month():
    roll = YearRoll.from_date(date(2025, 12, 20))
    assert roll.resolve(1, 4) == date(2026, 1, 4)


def test_a_year_roll_from_a_day_does_not_roll_a_later_month():
    roll = YearRoll.from_date(date(2025, 7, 22))
    assert roll.resolve(8, 4) == date(2025, 8, 4)
