from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.construction import (
    Construction,
    assemble_circle_trip,
    assemble_one_way,
    assemble_round_trip,
    fare_to_nuc,
    price_component,
)
from gussetline.errors import ConstructionError
from gussetline.exchange import Converter, default_roe_table
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.tariff import Fare, OwRt
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


@pytest.fixture
def converter():
    return Converter(default_roe_table())


def a_fare(**over) -> Fare:
    base = {
        "carrier": "LH",
        "origin": "FRA",
        "destination": "JFK",
        "fare_basis": "KLWAP7RT",
        "amount": Money.of("420.00", "EUR"),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": "0001",
    }
    base.update(over)
    return Fare(**base)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def home():
    return FareComponent(
        (segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),)
    )


def test_a_fare_converts_into_neutral_units_and_truncates(converter):
    # 420.00 EUR at the first quarter rate of 0.951 is 441.640378...
    assert fare_to_nuc(a_fare(), converter, TICKETING) == Decimal("441.64")


def test_a_round_trip_half_converts_as_filed_and_stays_a_half(converter):
    half = fare_to_nuc(a_fare(owrt=OwRt.ROUND_TRIP_HALF), converter, TICKETING)
    whole = fare_to_nuc(a_fare(owrt=OwRt.ONE_WAY_ONLY), converter, TICKETING)
    assert half == whole


def test_the_quarter_the_ticket_was_sold_in_decides_the_rate(converter):
    first = fare_to_nuc(a_fare(), converter, date(2025, 2, 14))
    second = fare_to_nuc(a_fare(), converter, date(2025, 5, 14))
    assert first != second


def test_pricing_a_component_attaches_the_fare_and_records_it(converter):
    trace = Trace()
    priced = price_component(out(), a_fare(), converter, TICKETING, trace)
    assert priced.fare is not None
    assert priced.nuc == Decimal("441.64")
    assert len(trace.for_stage(Stage.CONVERSION)) == 1
    assert not trace.failed


def test_pricing_without_a_trace_is_allowed(converter):
    assert price_component(out(), a_fare(), converter, TICKETING).nuc == Decimal("441.64")


def priced(component, converter, **over):
    return price_component(component, a_fare(**over), converter, TICKETING)


def test_a_round_trip_unit_needs_both_halves_priced(converter):
    with pytest.raises(ConstructionError):
        assemble_round_trip(out(), priced(home(), converter))


def test_a_one_way_only_fare_cannot_be_half_of_a_round_trip(converter):
    with pytest.raises(ConstructionError) as caught:
        assemble_round_trip(
            priced(out(), converter, owrt=OwRt.ONE_WAY_ONLY),
            priced(home(), converter),
        )
    assert caught.value.details["outbound"] == "KLWAP7RT"


def test_a_round_trip_unit_assembles_and_records(converter):
    trace = Trace()
    unit = assemble_round_trip(priced(out(), converter), priced(home(), converter), trace)
    assert len(unit) == 2
    assert unit.total_nuc == Decimal("883.28")
    assert len(trace.for_stage(Stage.SELECTION)) == 1


def test_a_round_trip_half_cannot_stand_as_a_one_way(converter):
    with pytest.raises(ConstructionError):
        assemble_one_way(priced(out(), converter))


def test_a_one_way_only_fare_stands_alone(converter):
    unit = assemble_one_way(priced(out(), converter, owrt=OwRt.ONE_WAY_ONLY))
    assert len(unit) == 1


def test_a_one_way_unit_needs_its_component_priced():
    with pytest.raises(ConstructionError):
        assemble_one_way(out())


def test_a_circle_trip_needs_three_components(converter):
    with pytest.raises(ConstructionError):
        assemble_circle_trip((priced(out(), converter), priced(home(), converter)))


def test_a_circle_trip_assembles_from_three(converter):
    middle = FareComponent(
        (segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),)
    )
    back = FareComponent(
        (segment("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20"),)
    )
    unit = assemble_circle_trip(
        (
            priced(out(), converter),
            price_component(middle, a_fare(origin="JFK", destination="LAX"), converter, TICKETING),
            price_component(back, a_fare(origin="LAX", destination="FRA"), converter, TICKETING),
        )
    )
    assert len(unit) == 3


def test_a_circle_trip_refuses_an_unpriced_component(converter):
    middle = FareComponent(
        (segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),)
    )
    back = FareComponent(
        (segment("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20"),)
    )
    with pytest.raises(ConstructionError):
        assemble_circle_trip((priced(out(), converter), middle, back))


def build(converter) -> Construction:
    unit = assemble_round_trip(priced(out(), converter), priced(home(), converter))
    return Construction((unit,), "EUR", TICKETING, Trace("FRAJFK"))


def test_a_construction_needs_a_unit():
    with pytest.raises(ConstructionError):
        Construction((), "EUR", TICKETING)


def test_a_construction_refuses_an_unpriced_unit(converter):
    unit = assemble_round_trip(priced(out(), converter), priced(home(), converter))
    unpriced = unit.replace(0, out())
    with pytest.raises(ConstructionError):
        Construction((unpriced,), "EUR", TICKETING)


def test_the_base_is_the_components_added_up(converter):
    assert build(converter).base_nuc == Decimal("883.28")
    assert build(converter).total_nuc == Decimal("883.28")


def test_a_minimum_check_raises_the_total_and_leaves_the_base(converter):
    raised = build(converter).with_minimum(Decimal("40.00"), "circle trip minimum")
    assert raised.base_nuc == Decimal("883.28")
    assert raised.total_nuc == Decimal("923.28")
    assert raised.trace.for_stage(Stage.MINIMUM)[0].details["added"] == "40.00"


def test_minimum_checks_accumulate(converter):
    raised = (
        build(converter)
        .with_minimum(Decimal("40.00"), "HIP")
        .with_minimum(Decimal("10.00"), "backhaul")
    )
    assert raised.total_nuc == Decimal("933.28")


def test_a_minimum_check_never_reduces_a_fare(converter):
    with pytest.raises(ConstructionError):
        build(converter).with_minimum(Decimal("-1"), "nonsense")


def test_the_conversion_into_the_selling_currency_happens_once(converter):
    # 883.28 NUC at 0.951 is 840.00 EUR, and the euro fare unit is one, so it
    # rounds up to 840. Converting each half and adding would give 841.
    assert build(converter).base_fare(converter) == Money.of("840", "EUR")


def test_the_components_are_reachable_across_the_units(converter):
    assert len(build(converter).components) == 2


def test_a_construction_reads_back_with_its_target_currency(converter):
    assert str(build(converter)) == "883.28 NUC into EUR"
