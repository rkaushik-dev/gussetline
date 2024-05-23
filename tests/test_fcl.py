from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment, surface
from gussetline.components import FareComponent
from gussetline.construction import (
    Construction,
    assemble_one_way,
    assemble_round_trip,
    price_component,
)
from gussetline.errors import ConstructionError
from gussetline.exchange import Converter, RateTable
from gussetline.fcl import (
    adds_up,
    compare,
    components_in,
    parse,
    render,
    render_component,
    roe_in,
    total_in,
)
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.tariff import Fare, OwRt
from gussetline.trace import Trace

TICKETING = date(2025, 2, 14)
ROE = Decimal("0.951")


@pytest.fixture
def converter():
    table = RateTable("roe")
    table.add("EUR", date(2025, 1, 1), "0.951")
    return Converter(table)


def a_fare(origin="FRA", destination="JFK", amount="420.00", **over) -> Fare:
    base = {
        "carrier": "LH",
        "origin": origin,
        "destination": destination,
        "fare_basis": "KLWAP7RT",
        "amount": Money.of(amount, "EUR"),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": "0000",
    }
    base.update(over)
    return Fare(**base)


def out(converter):
    return price_component(
        FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
        ),
        a_fare(),
        converter,
        TICKETING,
    )


def home(converter):
    return price_component(
        FareComponent(
            (segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),)
        ),
        a_fare("JFK", "FRA"),
        converter,
        TICKETING,
    )


def via_london(converter):
    return price_component(
        FareComponent(
            (
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
            )
        ),
        a_fare(),
        converter,
        TICKETING,
    )


def test_an_unpriced_component_has_no_line():
    with pytest.raises(ConstructionError):
        render_component(
            FareComponent(
                (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
            )
        )


def test_a_direct_component_is_board_carrier_off_amount(converter):
    assert render_component(out(converter)) == "FRA LH NYC 441.64"


def test_an_intermediate_point_is_written_plain_when_it_is_a_stopover(converter):
    assert render_component(via_london(converter)) == "FRA LH LON LH NYC 441.64"


def test_an_intermediate_point_is_marked_when_it_is_a_connection(converter):
    assert render_component(via_london(converter), ["LHR"]) == "FRA LH X/LON LH NYC 441.64"


def test_the_connection_mark_takes_airports_or_cities(converter):
    assert render_component(via_london(converter), ["LON"]) == "FRA LH X/LON LH NYC 441.64"


def test_the_final_point_is_never_marked_even_if_it_is_named(converter):
    assert render_component(out(converter), ["JFK"]) == "FRA LH NYC 441.64"


def test_a_surface_sector_is_written_with_slashes(converter):
    component = price_component(
        FareComponent(
            (
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                surface("JFK", "BOS"),
            )
        ),
        a_fare(destination="BOS"),
        converter,
        TICKETING,
    )
    assert render_component(component) == "FRA LH NYC // BOS 441.64"


def test_a_mileage_band_is_shown_and_folded_into_the_amount(converter):
    component = out(converter).with_surcharge(Decimal("22.08"), "5M")
    assert render_component(component) == "FRA LH NYC 5M 463.72"


def test_a_q_surcharge_is_written_in_front_and_left_out_of_the_amount(converter):
    component = out(converter).with_surcharge(Decimal("30.00"), "Q")
    assert render_component(component) == "FRA LH NYC Q30.00 441.64"


def test_a_higher_intermediate_point_is_folded_in_without_a_token(converter):
    component = out(converter).with_surcharge(Decimal("120.00"), "HIP")
    assert render_component(component) == "FRA LH NYC 561.64"


def test_both_kinds_of_charge_land_in_their_own_places(converter):
    component = (
        out(converter).with_surcharge(Decimal("30.00"), "Q").with_surcharge(Decimal("22.08"), "5M")
    )
    assert render_component(component) == "FRA LH NYC Q30.00 5M 463.72"


def build(converter):
    unit = assemble_round_trip(out(converter), home(converter))
    return Construction((unit,), "EUR", TICKETING, Trace())


def test_the_whole_line_joins_the_components_and_ends_with_the_rate(converter):
    assert render(build(converter), ROE) == (
        "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000"
    )


def test_the_second_component_does_not_repeat_the_point_it_starts_at(converter):
    line = render(build(converter), ROE)
    assert line.count("FRA") == 2


def test_a_one_way_line_carries_one_amount(converter):
    unit = assemble_one_way(
        price_component(
            FareComponent(
                (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
            ),
            a_fare(owrt=OwRt.ONE_WAY_ONLY),
            converter,
            TICKETING,
        )
    )
    line = render(Construction((unit,), "EUR", TICKETING), ROE)
    assert line == "FRA LH NYC 441.64 NUC441.64END ROE0.951000"
    assert components_in(line) == 1


def test_a_minimum_check_shows_in_the_total_and_not_in_a_component(converter):
    raised = build(converter).with_minimum(Decimal("40.00"), "circle trip minimum")
    line = render(raised, ROE)
    assert "NUC923.28END" in line
    assert " 441.64 " in line


def test_the_rate_is_written_to_six_decimals_whatever_it_is(converter):
    assert "ROE1.000000" in render(build(converter), Decimal("1"))
    assert "ROE152.440000" in render(build(converter), Decimal("152.44"))


def test_a_rate_of_zero_or_less_is_refused(converter):
    with pytest.raises(ConstructionError):
        render(build(converter), Decimal("0"))
    with pytest.raises(ConstructionError):
        render(build(converter), Decimal("-1"))


def test_the_total_and_the_rate_read_back_off_the_line(converter):
    line = render(build(converter), ROE)
    assert total_in(line) == Decimal("883.28")
    assert roe_in(line) == Decimal("0.951000")


def test_a_line_with_no_total_is_refused():
    with pytest.raises(ConstructionError):
        total_in("FRA LH NYC 441.64")
    with pytest.raises(ConstructionError):
        roe_in("FRA LH NYC 441.64 NUC441.64END")


def test_a_line_whose_parts_add_up_says_so(converter):
    assert adds_up(render(build(converter), ROE))


def test_a_q_surcharge_counts_toward_the_total(converter):
    component = out(converter).with_surcharge(Decimal("30.00"), "Q")
    unit = assemble_round_trip(component, home(converter))
    line = render(Construction((unit,), "EUR", TICKETING), ROE)
    assert "Q30.00" in line
    assert "NUC913.28END" in line
    assert adds_up(line)


def test_a_line_whose_total_was_not_rebuilt_is_caught():
    assert not adds_up("FRA LH NYC 441.64 LH FRA 441.64 NUC900.00END ROE0.951000")


def test_counting_components_ignores_the_totals_and_the_surcharge():
    line = "FRA LH NYC Q30.00 441.64 LH FRA 441.64 NUC913.28END ROE0.951000"
    assert components_in(line) == 2


def test_a_line_comes_apart_into_components():
    read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000")
    assert len(read.components) == 2
    assert read.components[0].points == ("FRA", "NYC")
    assert read.components[0].carriers == ("LH",)
    assert read.components[0].amount == Decimal("441.64")
    assert read.total == Decimal("883.28")
    assert read.roe == Decimal("0.951000")


def test_the_second_component_starts_where_the_first_ended():
    read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000")
    assert read.components[1].points == ("NYC", "FRA")
    assert read.components[1].board == "NYC"
    assert read.components[1].off == "FRA"


def test_a_marked_connection_is_read_back_as_one():
    read = parse("FRA LH X/LON LH NYC 441.64 NUC441.64END ROE0.951000")
    component = read.components[0]
    assert component.points == ("FRA", "LON", "NYC")
    assert component.connections == ("LON",)


def test_an_unmarked_intermediate_point_is_not_a_connection():
    read = parse("FRA LH LON LH NYC 441.64 NUC441.64END ROE0.951000")
    assert read.components[0].connections == ()


def test_a_q_surcharge_and_a_band_are_read_off_the_component():
    read = parse("FRA LH NYC Q30.00 5M 463.72 NUC493.72END ROE0.951000")
    component = read.components[0]
    assert component.q_surcharge == Decimal("30.00")
    assert component.band == "5M"
    assert component.amount == Decimal("463.72")


def test_a_surface_sector_survives_the_round_trip():
    read = parse("FRA LH NYC // BOS 441.64 NUC441.64END ROE0.951000")
    assert read.components[0].points == ("FRA", "NYC", "BOS")
    assert "//" in read.components[0].carriers


def test_a_parsed_line_knows_whether_it_adds_up():
    assert parse("FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000").adds_up
    assert not parse("FRA LH NYC 441.64 LH FRA 441.64 NUC900.00END ROE0.951000").adds_up


def test_the_gap_a_minimum_check_leaves_is_reported_as_unexplained():
    read = parse("FRA LH NYC 441.64 LH FRA 441.64 NUC923.28END ROE0.951000")
    assert read.unexplained == Decimal("40.00")


def test_parsing_refuses_only_the_two_things_it_cannot_guess():
    with pytest.raises(ConstructionError):
        parse("")
    with pytest.raises(ConstructionError):
        parse("FRA LH NYC 441.64 ROE0.951000")
    with pytest.raises(ConstructionError):
        parse("FRA LH NYC 441.64 NUC441.64END")


def test_what_we_render_is_what_we_read_back(converter):
    line = render(build(converter), ROE)
    read = parse(line)
    assert read.raw == line
    assert len(read.components) == 2
    assert read.total == total_in(line)
    assert read.roe == roe_in(line)


def test_two_identical_lines_have_nothing_to_say():
    line = "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000"
    assert compare(line, line) == []


def test_a_different_total_is_reported():
    found = compare(
        "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000",
        "FRA LH NYC 441.64 LH FRA 441.64 NUC903.28END ROE0.951000",
    )
    assert found == ["neutral total 883.28 against 903.28"]


def test_a_different_rate_is_reported():
    found = compare(
        "FRA LH NYC 441.64 NUC441.64END ROE0.951000",
        "FRA LH NYC 441.64 NUC441.64END ROE0.924000",
    )
    assert found == ["rate of exchange 0.951000 against 0.924000"]


def test_a_different_component_count_stops_there():
    found = compare(
        "FRA LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000",
        "FRA LH NYC 883.28 NUC883.28END ROE0.951000",
    )
    assert found == ["2 components against 1"]


def test_a_different_route_on_one_component_is_named():
    found = compare(
        "FRA LH LON LH NYC 441.64 NUC441.64END ROE0.951000",
        "FRA LH AMS LH NYC 441.64 NUC441.64END ROE0.951000",
    )
    assert found == ["component 1 routes FRA-LON-NYC against FRA-AMS-NYC"]


def test_a_different_amount_and_band_are_both_named():
    found = compare(
        "FRA LH NYC 5M 463.72 NUC463.72END ROE0.951000",
        "FRA LH NYC 441.64 NUC441.64END ROE0.951000",
    )
    assert "component 1 is 463.72 against 441.64" in found
    assert "component 1 band 5M against none" in found


def test_a_q_surcharge_only_one_side_charged_is_named():
    found = compare(
        "FRA LH NYC Q30.00 441.64 NUC471.64END ROE0.951000",
        "FRA LH NYC 441.64 NUC441.64END ROE0.951000",
    )
    assert "component 1 q surcharge 30.00 against 0" in found
