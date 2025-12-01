from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.series import FIELDS, key, parts, read

DEPARTURE = date(2025, 8, 4)


def test_a_key_reads_as_the_four_things_it_is_about():
    assert key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY) == "FRA-NYC-2025-08-04-Y"


def test_an_airport_is_folded_to_the_city_the_tariff_prices():
    # The bug this module exists for. A watch on Newark and an observation on
    # New York are the same series, and two spellings of it is a night run
    # that finds no history and raises nothing, quietly, every night.
    assert key("FRA", "EWR", DEPARTURE, Cabin.ECONOMY) == key(
        "FRA", "JFK", DEPARTURE, Cabin.ECONOMY
    )


def test_a_city_given_as_a_city_is_left_alone():
    assert key("FRA", "NYC", DEPARTURE, Cabin.ECONOMY) == "FRA-NYC-2025-08-04-Y"


def test_both_ends_are_folded_and_not_only_the_destination():
    assert key("EWR", "FRA", DEPARTURE, Cabin.ECONOMY).startswith("NYC-")


def test_a_cabin_may_arrive_as_the_enum_or_as_what_the_database_holds():
    assert key("FRA", "JFK", DEPARTURE, Cabin.BUSINESS) == key("FRA", "JFK", DEPARTURE, "C")


def test_a_lowercase_cabin_is_the_same_cabin():
    assert key("FRA", "JFK", DEPARTURE, "y") == key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)


def test_a_departure_may_arrive_as_a_day_or_as_a_moment():
    when = datetime(2025, 8, 4, 10, 25)
    assert key("FRA", "JFK", when, Cabin.ECONOMY) == key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)


def test_the_time_of_day_is_not_part_of_the_series():
    # Two departures on one day are one series. A watch is on a day and a
    # market, because that is what the passenger can be moved between.
    morning = datetime(2025, 8, 4, 7, 5)
    evening = datetime(2025, 8, 4, 21, 40)
    assert key("FRA", "JFK", morning, "Y") == key("FRA", "JFK", evening, "Y")


def test_the_parts_are_the_key_before_it_is_joined():
    assert parts("FRA", "EWR", DEPARTURE, Cabin.ECONOMY) == ("FRA", "NYC", "2025-08-04", "Y")


def test_the_parts_fold_the_same_way_the_key_does():
    assert "-".join(parts("FRA", "EWR", DEPARTURE, "Y")) == key("FRA", "EWR", DEPARTURE, "Y")


def test_a_key_survives_being_read_back():
    assert read(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)) == ("FRA", "NYC", DEPARTURE, "Y")


def test_reading_counts_from_both_ends_because_the_date_has_dashes_of_its_own():
    assert read("FRA-NYC-2025-08-04-Y")[2] == DEPARTURE


def test_a_key_has_exactly_this_many_fields():
    assert len(key("FRA", "JFK", DEPARTURE, "Y").split("-")) == FIELDS


def test_something_that_is_not_a_key_is_refused_and_not_guessed_at():
    with pytest.raises(ValidationError, match="not a series key"):
        read("FRA-NYC")


def test_a_key_with_too_much_in_it_is_refused():
    with pytest.raises(ValidationError, match="not a series key"):
        read("FRA-NYC-2025-08-04-Y-Y")


def test_a_key_with_a_day_that_does_not_exist_is_refused():
    with pytest.raises(ValidationError, match="not a series key"):
        read("FRA-NYC-2025-02-30-Y")


def test_a_key_with_words_where_the_date_goes_is_refused():
    with pytest.raises(ValidationError, match="not a series key"):
        read("FRA-NYC-next-august-ish-Y")


def test_nothing_at_all_is_refused():
    with pytest.raises(ValidationError, match="not a series key"):
        read("")
