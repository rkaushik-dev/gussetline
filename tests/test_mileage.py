from __future__ import annotations

from decimal import Decimal

import pytest

from gussetline.distance import MileageTable
from gussetline.errors import MileageUnavailable
from gussetline.indicators import GlobalIndicator
from gussetline.mileage import (
    MAX_SURCHARGE,
    ExtraMileageAllowance,
    MileageResult,
    band_for,
    check,
    maximum_permitted,
)
from gussetline.trace import Stage, Trace


@pytest.fixture
def table():
    t = MileageTable()
    t.add("FRA", "JFK", 4000)
    t.add("FRA", "LON", 400)
    t.add("LON", "JFK", 3500)
    t.add("FRA", "DXB", 3000)
    t.add("DXB", "JFK", 6800)
    return t


@pytest.mark.parametrize(
    ("percentage", "surcharge"),
    [
        (80, 0),
        (100, 0),
        (101, 5),
        (105, 5),
        (106, 10),
        (110, 10),
        (115, 15),
        (120, 20),
        (125, 25),
    ],
)
def test_the_ladder_has_six_steps_and_they_are_inclusive_at_the_top(percentage, surcharge):
    assert band_for(percentage) == surcharge


def test_past_the_top_of_the_ladder_there_is_no_band():
    assert band_for(126) is None
    assert band_for(300) is None


def test_the_maximum_is_twenty_percent_above_the_shortest_mileage(table):
    assert maximum_permitted("FRA", "JFK", table) == 4800


def test_a_direct_sector_is_well_inside_the_maximum(table):
    result = check("FRA", "JFK", [], table)
    assert result.flown == 4000
    assert result.maximum == 4800
    assert result.percentage == 84
    assert result.surcharge_percent == 0
    assert result.permitted
    assert result.band == "M"


def test_a_detour_adds_up_the_sectors_and_not_the_end_to_end(table):
    result = check("FRA", "JFK", ["LON"], table)
    assert result.flown == 3900
    assert result.percentage == 82


def test_a_long_detour_lands_on_a_surcharge_band(table):
    # Frankfurt to Dubai to New York is 9800 against a 4800 maximum.
    result = check("FRA", "JFK", ["DXB"], table)
    assert result.flown == 9800
    assert not result.permitted
    assert result.surcharge_percent == MAX_SURCHARGE


def test_a_journey_just_over_the_maximum_takes_the_first_step():
    t = MileageTable()
    t.add("FRA", "JFK", 4000)
    t.add("FRA", "LON", 500)
    t.add("LON", "JFK", 4400)
    result = check("FRA", "JFK", ["LON"], t)
    assert result.flown == 4900
    assert result.percentage == 103
    assert result.surcharge_percent == 5
    assert result.band == "5M"


def test_the_percentage_rounds_up_so_a_fraction_does_not_undercharge():
    result = MileageResult(
        flown=4801, maximum=4800, deduction=0, surcharge_percent=0, permitted=True
    )
    assert result.percentage == 101


def test_a_journey_exactly_on_the_maximum_owes_nothing():
    result = MileageResult(
        flown=4800, maximum=4800, deduction=0, surcharge_percent=0, permitted=True
    )
    assert result.percentage == 100
    assert band_for(result.percentage) == 0


def test_the_multiplier_and_the_surcharge_amount_agree():
    result = MileageResult(
        flown=5000, maximum=4800, deduction=0, surcharge_percent=10, permitted=True
    )
    assert result.multiplier == Decimal("1.10")
    assert result.surcharge_on(Decimal("441.64")) == Decimal("44.16")


def test_no_surcharge_means_no_amount():
    result = MileageResult(
        flown=4000, maximum=4800, deduction=0, surcharge_percent=0, permitted=True
    )
    assert result.surcharge_on(Decimal("441.64")) == Decimal(0)


def test_a_maximum_of_zero_does_not_divide_by_it():
    result = MileageResult(flown=100, maximum=0, deduction=0, surcharge_percent=0, permitted=True)
    assert result.percentage == 0


def test_an_allowance_comes_off_the_flown_figure_and_can_clear_a_band(table):
    allowances = ExtraMileageAllowance()
    allowances.add_pair("FRA", "JFK", 1200)
    plain = check("FRA", "JFK", ["DXB"], table)
    allowed = check("FRA", "JFK", ["DXB"], table, allowances=allowances)
    assert plain.chargeable == 9800
    assert allowed.chargeable == 8600
    assert allowed.deduction == 1200


def test_a_pair_allowance_reads_either_way_round(table):
    allowances = ExtraMileageAllowance()
    allowances.add_pair("FRA", "JFK", 500)
    assert allowances.deduction_for("JFK", "FRA", []) == 500


def test_a_point_deduction_only_applies_when_the_point_is_flown():
    allowances = ExtraMileageAllowance()
    allowances.add_point("FRA", "JFK", "LON", 300)
    assert allowances.deduction_for("FRA", "JFK", ["LON"]) == 300
    assert allowances.deduction_for("FRA", "JFK", ["AMS"]) == 0
    assert allowances.deduction_for("FRA", "JFK", []) == 0


def test_a_pair_allowance_and_a_point_deduction_stack():
    allowances = ExtraMileageAllowance()
    allowances.add_pair("FRA", "JFK", 400)
    allowances.add_point("FRA", "JFK", "LON", 300)
    assert allowances.deduction_for("FRA", "JFK", ["LON"]) == 700


def test_a_deduction_cannot_push_the_chargeable_below_zero():
    result = MileageResult(
        flown=100, maximum=4800, deduction=500, surcharge_percent=0, permitted=True
    )
    assert result.chargeable == 0


def test_a_check_needs_two_points(table):
    with pytest.raises(MileageUnavailable):
        check("FRA", "", [], table)


def test_a_pass_is_recorded_with_both_figures(table):
    trace = Trace()
    check("FRA", "JFK", [], table, trace=trace)
    step = trace.for_stage(Stage.MILEAGE)[0]
    assert not step.failed
    assert step.details == {"flown": 4000, "maximum": 4800}


def test_going_off_the_ladder_is_recorded_as_a_failure(table):
    trace = Trace()
    result = check("FRA", "JFK", ["DXB"], table, trace=trace)
    assert not result.permitted
    assert trace.stage_failed(Stage.MILEAGE)
    assert "over the mileage ladder" in trace.reasons()[0]


def test_a_fallback_mileage_is_noted_but_does_not_fail(table):
    trace = Trace()
    result = check("FRA", "BKK", [], table, trace=trace)
    assert result.fallback_used
    assert not trace.failed
    assert any("great circle" in s.message for s in trace.for_stage(Stage.MILEAGE))


def test_the_indicator_reaches_the_table():
    t = MileageTable()
    t.add("FRA", "TYO", 5928, GlobalIndicator.TS)
    t.add("FRA", "TYO", 7169, GlobalIndicator.PA)
    east = check("FRA", "NRT", [], t, indicator=GlobalIndicator.TS)
    west = check("FRA", "NRT", [], t, indicator=GlobalIndicator.PA)
    assert east.maximum < west.maximum
    assert not east.fallback_used


def test_a_result_reads_back_with_its_band(table):
    assert str(check("FRA", "JFK", [], table)) == "84 percent of maximum, M"
    assert str(check("FRA", "JFK", ["DXB"], table)) == ("205 percent of maximum, over the ladder")
