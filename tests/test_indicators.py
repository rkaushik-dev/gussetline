from __future__ import annotations

import pytest

from gussetline.errors import ValidationError
from gussetline.indicators import (
    GlobalIndicator,
    global_indicator,
    indicator_matches,
    is_international,
)


def test_a_domestic_journey_is_domestic_even_with_a_stop():
    assert global_indicator("JFK", "LAX") is GlobalIndicator.DO
    assert global_indicator("JFK", "LAX", ["ORD"]) is GlobalIndicator.DO
    assert global_indicator("LHR", "EDI") is GlobalIndicator.DO


def test_europe_to_north_america_is_atlantic():
    assert global_indicator("FRA", "JFK") is GlobalIndicator.AT
    assert global_indicator("LHR", "ORD", ["JFK"]) is GlobalIndicator.AT


def test_north_america_to_asia_is_pacific_unless_it_goes_the_other_way():
    assert global_indicator("LAX", "NRT") is GlobalIndicator.PA
    assert global_indicator("SFO", "HKG", ["NRT"]) is GlobalIndicator.PA
    assert global_indicator("JFK", "DEL", ["FRA"]) is GlobalIndicator.AT


def test_the_same_city_pair_gets_two_indicators_and_that_is_the_point():
    east = global_indicator("JFK", "SIN", ["FRA"])
    west = global_indicator("JFK", "SIN", ["LAX"])
    assert east is GlobalIndicator.AT
    assert west is GlobalIndicator.PA
    assert east is not west


def test_touching_all_three_areas_is_a_round_the_world_construction():
    assert global_indicator("FRA", "NRT", ["JFK", "LAX"]) is GlobalIndicator.AP


def test_within_europe_is_eastern_hemisphere():
    assert global_indicator("FRA", "MAD") is GlobalIndicator.EH
    assert global_indicator("LHR", "IST") is GlobalIndicator.EH


def test_europe_to_asia_is_eastern_hemisphere_by_default():
    assert global_indicator("FRA", "NRT") is GlobalIndicator.EH
    assert global_indicator("LHR", "SIN", ["DXB"]) is GlobalIndicator.EH


def test_routing_through_moscow_makes_it_trans_siberian():
    assert global_indicator("FRA", "NRT", ["SVO"]) is GlobalIndicator.TS
    assert global_indicator("LHR", "ICN", ["MOW"]) is GlobalIndicator.TS


def test_within_the_far_east_has_its_own_indicator():
    assert global_indicator("NRT", "SIN") is GlobalIndicator.FE
    assert global_indicator("HKG", "SYD") is GlobalIndicator.FE


def test_india_is_area_three_but_not_far_east():
    assert global_indicator("DEL", "SIN") is GlobalIndicator.EH


def test_within_the_americas_across_borders_is_western_hemisphere():
    assert global_indicator("JFK", "GRU") is GlobalIndicator.WH
    assert global_indicator("YYZ", "MEX") is GlobalIndicator.WH


def test_south_america_to_africa_is_the_south_atlantic():
    assert global_indicator("GRU", "JNB") is GlobalIndicator.SA
    assert global_indicator("JNB", "EZE") is GlobalIndicator.SA


def test_north_america_to_africa_is_the_plain_atlantic():
    assert global_indicator("JFK", "JNB") is GlobalIndicator.AT


def test_an_empty_end_is_refused():
    with pytest.raises(ValidationError):
        global_indicator("", "JFK")
    with pytest.raises(ValidationError):
        global_indicator("FRA", "")


def test_is_international_reads_the_country_not_the_area():
    assert is_international("FRA", "MUC") is False
    assert is_international("FRA", "ZRH") is True
    assert is_international("LHR", "EDI") is False


def test_a_broader_indicator_prices_a_narrower_journey():
    assert indicator_matches(GlobalIndicator.EH, GlobalIndicator.TS)
    assert indicator_matches(GlobalIndicator.EH, GlobalIndicator.FE)
    assert indicator_matches(GlobalIndicator.AT, GlobalIndicator.SA)


def test_a_narrower_indicator_does_not_price_a_broader_journey():
    assert not indicator_matches(GlobalIndicator.TS, GlobalIndicator.EH)
    assert not indicator_matches(GlobalIndicator.SA, GlobalIndicator.AT)
    assert not indicator_matches(GlobalIndicator.PA, GlobalIndicator.AT)


def test_an_indicator_always_matches_itself():
    for value in GlobalIndicator:
        assert indicator_matches(value, value)
