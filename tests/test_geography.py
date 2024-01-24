from __future__ import annotations

import pytest

from gussetline.errors import ValidationError
from gussetline.geography import (
    AIRPORTS,
    COUNTRIES,
    METROS,
    Area,
    SubArea,
    airport,
    airport_or_city_country,
    airports_in,
    area_of,
    city_of,
    country,
    same_country,
    sub_area_of,
)


def test_every_airport_names_a_country_we_hold():
    missing = sorted({a.country for a in AIRPORTS.values()} - set(COUNTRIES))
    assert missing == []


def test_every_country_sits_in_exactly_one_area():
    for row in COUNTRIES.values():
        assert row.area in (Area.ONE, Area.TWO, Area.THREE)


def test_areas_follow_the_sub_area_and_not_the_hemisphere():
    assert country("EG").area is Area.TWO
    assert country("AE").area is Area.TWO
    assert country("JP").area is Area.THREE
    assert country("BR").area is Area.ONE


def test_airport_lookup_is_case_and_space_insensitive():
    assert airport(" lhr ").code == "LHR"


def test_a_metro_code_is_not_an_airport():
    with pytest.raises(ValidationError):
        airport("LON")
    with pytest.raises(ValidationError):
        airport("NYC")


def test_city_of_folds_the_airports_of_a_metro_together():
    assert city_of("LHR") == "LON"
    assert city_of("LGW") == "LON"
    assert city_of("EWR") == "NYC"
    assert city_of("ORY") == "PAR"


def test_city_of_a_standalone_airport_is_its_own_code():
    assert city_of("ZRH") == "ZRH"
    assert city_of("DXB") == "DXB"


def test_a_metro_code_answers_for_itself():
    assert city_of("LON") == "LON"


def test_airports_in_lists_the_metro_members_sorted():
    assert airports_in("LON") == ("LCY", "LGW", "LHR", "STN")
    assert airports_in("NYC") == ("EWR", "JFK", "LGA")
    assert airports_in("TYO") == ("HND", "NRT")


def test_airports_in_a_single_airport_city_is_that_airport():
    assert airports_in("ZRH") == ("ZRH",)


def test_airports_in_refuses_something_that_is_neither():
    with pytest.raises(ValidationError):
        airports_in("QQQ")


def test_metros_are_derived_and_not_typed_twice():
    for city, members in METROS.items():
        for code in members:
            assert AIRPORTS[code].city == city


def test_is_metro_marks_the_airport_that_carries_its_own_city_code():
    assert airport("ZRH").is_metro
    assert not airport("LHR").is_metro


def test_sub_area_and_area_work_from_an_airport_or_a_city():
    assert sub_area_of("LHR") is SubArea.EUROPE
    assert sub_area_of("LON") is SubArea.EUROPE
    assert area_of("JFK") is Area.ONE
    assert area_of("NYC") is Area.ONE
    assert sub_area_of("NRT") is SubArea.JAPAN_KOREA
    assert sub_area_of("SIN") is SubArea.SOUTH_EAST_ASIA


def test_the_middle_east_is_area_two_and_south_asia_is_area_three():
    assert area_of("DXB") is Area.TWO
    assert area_of("DOH") is Area.TWO
    assert area_of("DEL") is Area.THREE
    assert area_of("BOM") is Area.THREE


def test_country_of_a_point_reads_the_same_either_way():
    assert airport_or_city_country("LHR") == "GB"
    assert airport_or_city_country("LON") == "GB"


def test_same_country_folds_metros():
    assert same_country("LHR", "MAN")
    assert same_country("LON", "EDI")
    assert not same_country("LHR", "DUB")


def test_utc_offsets_are_whole_minutes_and_india_is_the_reason():
    assert airport("DEL").utc_offset_minutes == 330
    assert airport("CMB").utc_offset_minutes == 330
    assert airport("AKL").utc_offset_minutes == 720


def test_coordinates_are_inside_the_sphere():
    for a in AIRPORTS.values():
        assert -90.0 <= a.latitude <= 90.0
        assert -180.0 <= a.longitude <= 180.0


def test_unknown_lookups_say_what_was_asked_for():
    with pytest.raises(ValidationError) as caught:
        country("QQ")
    assert caught.value.details["code"] == "QQ"
