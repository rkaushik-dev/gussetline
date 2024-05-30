from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.combinability import (
    Combinability,
    CombinabilityStore,
    check_end_on_end,
    check_unit,
)
from gussetline.components import FareComponent, PricingUnit
from gussetline.construction import assemble_one_way, assemble_round_trip, price_component
from gussetline.errors import CombinabilityError
from gussetline.exchange import Converter, RateTable
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import JourneyType
from gussetline.money import Money
from gussetline.tariff import Fare, OwRt
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


@pytest.fixture
def converter():
    table = RateTable("roe")
    table.add("EUR", date(2025, 1, 1), "1.000")
    return Converter(table)


def a_fare(origin="FRA", destination="JFK", carrier="LH", basis="KLWAP7RT", **over) -> Fare:
    base = {
        "carrier": carrier,
        "origin": origin,
        "destination": destination,
        "fare_basis": basis,
        "amount": Money.of("420.00", "EUR"),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": "0000",
    }
    base.update(over)
    return Fare(**base)


def out(converter, **over):
    return price_component(
        FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
        ),
        a_fare(**over),
        converter,
        TICKETING,
    )


def home(converter, **over):
    return price_component(
        FareComponent(
            (segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),)
        ),
        a_fare(origin="JFK", destination="FRA", **over),
        converter,
        TICKETING,
    )


def record(basis="KLWAP7RT", **over) -> Combinability:
    base = {"carrier": "LH", "fare_basis": basis, "round_trip": True}
    base.update(over)
    return Combinability(**base)


def test_everything_defaults_to_refused():
    plain = Combinability("LH", "YRT")
    assert not plain.round_trip
    assert not plain.circle_trip
    assert not plain.end_on_end
    assert plain.same_carrier_only


def test_a_one_way_is_always_permitted_by_the_record():
    assert Combinability("LH", "YRT").permits(JourneyType.ONE_WAY)


def test_permits_reads_the_flag_for_each_shape():
    assert record(round_trip=True).permits(JourneyType.ROUND_TRIP)
    assert not record(round_trip=True).permits(JourneyType.CIRCLE_TRIP)
    assert record(circle_trip=True).permits(JourneyType.CIRCLE_TRIP)
    assert record(open_jaw=True).permits(JourneyType.OPEN_JAW)


def test_a_record_reads_back_as_what_it_permits():
    assert str(record(round_trip=True, end_on_end=True)) == "LH KLWAP7RT RT/EOE"
    assert str(Combinability("LH", "YRT")) == "LH YRT nothing"


def test_the_store_finds_a_record_by_carrier_and_basis():
    store = CombinabilityStore()
    store.add(record())
    assert store.get("lh", "klwap7rt") is not None
    assert store.get("BA", "KLWAP7RT") is None
    assert len(store) == 1


def test_a_one_way_unit_passes_without_a_lookup(converter):
    unit = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    assert check_unit(unit, CombinabilityStore(), trace) == []
    assert not trace.failed


def test_a_round_trip_of_two_permitted_fares_passes(converter):
    store = CombinabilityStore()
    store.add(record())
    unit = assemble_round_trip(out(converter), home(converter))
    trace = Trace()
    assert check_unit(unit, store, trace) == []
    assert len(trace.for_stage(Stage.COMBINABILITY)) == 1


def test_a_fare_with_nothing_filed_combines_with_nothing(converter):
    unit = assemble_round_trip(out(converter), home(converter))
    refusals = check_unit(unit, CombinabilityStore())
    assert len(refusals) == 2
    assert "has no combinability filed" in refusals[0]


def test_a_fare_that_does_not_permit_the_shape_refuses(converter):
    store = CombinabilityStore()
    store.add(record(round_trip=False, circle_trip=True))
    unit = assemble_round_trip(out(converter), home(converter))
    trace = Trace()
    refusals = check_unit(unit, store, trace)
    assert refusals == [
        "LH KLWAP7RT does not permit RT",
        "LH KLWAP7RT does not permit RT",
    ]
    assert trace.stage_failed(Stage.COMBINABILITY)


def test_a_same_carrier_filing_refuses_a_mixed_carrier_unit(converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=True))
    store.add(record(carrier="UA", same_carrier_only=False))
    unit = assemble_round_trip(out(converter), home(converter, carrier="UA"))
    refusals = check_unit(unit, store)
    assert any("same carrier only" in r for r in refusals)


def test_a_mixed_carrier_unit_passes_when_neither_side_insists(converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=False))
    store.add(record(carrier="UA", same_carrier_only=False))
    unit = assemble_round_trip(out(converter), home(converter, carrier="UA"))
    assert check_unit(unit, store) == []


def test_a_same_rule_filing_refuses_two_different_bases(converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=False, same_rule_only=True))
    store.add(record(basis="QLWAP14RT", same_carrier_only=False))
    unit = assemble_round_trip(out(converter), home(converter, basis="QLWAP14RT"))
    refusals = check_unit(unit, store)
    assert any("same rule only" in r for r in refusals)


def test_combinability_runs_after_the_unit_is_priced(converter):
    unit = assemble_round_trip(out(converter), home(converter))
    unpriced = unit.replace(
        0,
        FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
        ),
    )
    with pytest.raises(CombinabilityError):
        check_unit(unpriced, CombinabilityStore())


def test_one_pricing_unit_needs_no_end_on_end_permission(converter):
    unit = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    assert check_end_on_end([unit], CombinabilityStore(), trace) == []
    assert not trace.failed


def test_two_units_both_permitting_end_on_end_pass(converter):
    store = CombinabilityStore()
    store.add(record(end_on_end=True))
    one = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(converter, owrt=OwRt.ONE_WAY_ONLY))
    assert check_end_on_end([one, two], store) == []


def test_one_fare_refusing_end_on_end_stops_the_whole_ticket(converter):
    store = CombinabilityStore()
    store.add(record(end_on_end=True))
    store.add(record(basis="QLWAP14RT", end_on_end=False))
    one = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(converter, basis="QLWAP14RT", owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    refusals = check_end_on_end([one, two], store, trace)
    assert refusals == ["LH QLWAP14RT does not permit end on end"]
    assert trace.stage_failed(Stage.COMBINABILITY)


def test_an_unfiled_fare_refuses_end_on_end_too(converter):
    one = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(converter, owrt=OwRt.ONE_WAY_ONLY))
    refusals = check_end_on_end([one, two], CombinabilityStore())
    assert len(refusals) == 2


def test_end_on_end_runs_after_pricing(converter):
    unpriced = PricingUnit(
        (
            FareComponent(
                (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
            ),
        ),
        JourneyType.ONE_WAY,
    )
    one = assemble_one_way(out(converter, owrt=OwRt.ONE_WAY_ONLY))
    with pytest.raises(CombinabilityError):
        check_end_on_end([one, unpriced], CombinabilityStore())
