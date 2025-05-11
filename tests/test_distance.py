from __future__ import annotations

import pytest

from gussetline.distance import MileageTable, default_mileage_table, great_circle, point_of
from gussetline.errors import MileageUnavailable, ValidationError
from gussetline.geography import airports_in
from gussetline.indicators import GlobalIndicator


def test_great_circle_is_symmetric():
    assert great_circle("FRA", "JFK") == great_circle("JFK", "FRA")


def test_great_circle_of_a_point_with_itself_is_zero():
    assert great_circle("FRA", "FRA") == 0


def test_great_circle_lands_near_the_published_figures():
    # Not equal to the published TPM, and that is the whole reason the table
    # exists, but a great circle that is out by hundreds means a bad row.
    assert abs(great_circle("FRA", "JFK") - 3851) < 120
    assert abs(great_circle("LHR", "JFK") - 3451) < 120
    assert abs(great_circle("SIN", "SYD") - 3915) < 150


def test_great_circle_crosses_the_meridian_and_the_date_line_sanely():
    assert 5000 < great_circle("LAX", "NRT") < 6000
    assert 9000 < great_circle("LHR", "AKL") < 12000


@pytest.fixture
def table():
    t = MileageTable()
    t.add("FRA", "JFK", 3851)
    t.add("LON", "JFK", 3451)
    return t


def test_a_lookup_folds_airports_up_to_the_metro(table):
    assert table.published("LHR", "JFK") == 3451
    assert table.published("LGW", "EWR") == 3451
    assert table.published("STN", "LGA") == 3451


def test_a_pair_is_unordered(table):
    assert table.published("JFK", "FRA") == 3851


def test_a_pair_we_do_not_hold_raises_and_names_the_cities(table):
    with pytest.raises(MileageUnavailable) as caught:
        table.published("FRA", "BKK")
    assert caught.value.details["origin"] in ("BKK", "FRA")


def test_has_answers_without_raising(table):
    assert table.has("FRA", "JFK")
    assert not table.has("FRA", "BKK")


def test_a_mileage_has_to_be_positive(table):
    with pytest.raises(MileageUnavailable):
        table.add("FRA", "BKK", 0)
    with pytest.raises(MileageUnavailable):
        table.add("FRA", "BKK", -100)


def test_tpm_falls_back_to_great_circle_and_says_so(table):
    published = table.tpm("FRA", "JFK")
    assert published.miles == 3851
    assert published.published
    assert not published.is_fallback

    fallback = table.tpm("FRA", "BKK")
    assert fallback.miles == great_circle("FRA", "BKK")
    assert fallback.is_fallback


def test_a_pair_filed_without_an_indicator_answers_for_any_of_them(table):
    assert table.published("FRA", "JFK", GlobalIndicator.AT) == 3851
    assert table.published("FRA", "JFK", GlobalIndicator.PA) == 3851


def test_a_pair_filed_per_indicator_answers_only_for_that_one():
    t = MileageTable()
    t.add("FRA", "TYO", 5928, GlobalIndicator.TS)
    t.add("FRA", "TYO", 7169, GlobalIndicator.PA)
    assert t.published("FRA", "TYO", GlobalIndicator.TS) == 5928
    assert t.published("FRA", "TYO", GlobalIndicator.PA) == 7169
    with pytest.raises(MileageUnavailable):
        t.published("FRA", "TYO", GlobalIndicator.AT)
    with pytest.raises(MileageUnavailable):
        t.published("FRA", "TYO")


def test_the_specific_filing_wins_over_the_general_one():
    t = MileageTable()
    t.add("FRA", "TYO", 6000)
    t.add("FRA", "TYO", 5928, GlobalIndicator.TS)
    assert t.published("FRA", "TYO", GlobalIndicator.TS) == 5928
    assert t.published("FRA", "TYO", GlobalIndicator.PA) == 6000
    assert t.published("FRA", "TYO") == 6000


def test_the_shipped_table_holds_the_tokyo_pair_both_ways_round():
    table = default_mileage_table()
    east = table.tpm("FRA", "TYO", GlobalIndicator.TS)
    west = table.tpm("FRA", "TYO", GlobalIndicator.PA)
    assert east.published and west.published
    assert east.miles < west.miles


def test_the_shipped_table_reaches_the_metro_members():
    table = default_mileage_table()
    assert table.tpm("LHR", "JFK").published
    assert table.tpm("HND", "LAX").published
    assert table.size() > 40


def test_an_unlisted_pair_still_prices_off_the_shipped_table():
    table = default_mileage_table()
    reading = table.tpm("BGI", "NBO")
    assert reading.is_fallback
    assert reading.miles > 0


def test_a_city_code_measures_from_one_of_its_airports():
    # A metro code has no coordinates. Measuring from it raises an unknown
    # airport, which the mileage check then reports as a bad itinerary
    # instead of as a mileage nobody published.
    assert point_of("NYC") in airports_in("NYC")
    assert point_of("JFK") == "JFK"


def test_a_code_that_is_neither_an_airport_nor_a_city_is_refused():
    with pytest.raises(ValidationError):
        point_of("ZZZ")


def test_an_unpublished_city_pair_falls_back_instead_of_raising():
    reading = MileageTable().tpm("MUC", "NYC")
    assert reading.is_fallback
    assert reading.miles > 3000


def test_a_published_pair_is_not_a_fallback():
    table = MileageTable()
    table.add("MUC", "NYC", 4100)
    reading = table.tpm("MUC", "NYC")
    assert reading.published
    assert reading.miles == 4100
