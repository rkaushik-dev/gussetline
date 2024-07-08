from __future__ import annotations

from datetime import date

import pytest

from conftest import fare, segment
from gussetline.combinability import (
    Combinability,
    CombinabilityStore,
    check_end_on_end,
    check_unit,
)
from gussetline.components import FareComponent, PricingUnit
from gussetline.construction import assemble_one_way, assemble_round_trip, price_component
from gussetline.errors import CombinabilityError
from gussetline.itinerary import JourneyType
from gussetline.tariff import OwRt
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


def out(flat_converter, **over):
    return price_component(
        FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
        ),
        fare(**over),
        flat_converter,
        TICKETING,
    )


def home(flat_converter, **over):
    return price_component(
        FareComponent(
            (segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),)
        ),
        fare(origin="JFK", destination="FRA", **over),
        flat_converter,
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


def test_a_one_way_unit_passes_without_a_lookup(flat_converter):
    unit = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    assert check_unit(unit, CombinabilityStore(), trace) == []
    assert not trace.failed


def test_a_round_trip_of_two_permitted_fares_passes(flat_converter):
    store = CombinabilityStore()
    store.add(record())
    unit = assemble_round_trip(out(flat_converter), home(flat_converter))
    trace = Trace()
    assert check_unit(unit, store, trace) == []
    assert len(trace.for_stage(Stage.COMBINABILITY)) == 1


def test_a_fare_with_nothing_filed_combines_with_nothing(flat_converter):
    unit = assemble_round_trip(out(flat_converter), home(flat_converter))
    refusals = check_unit(unit, CombinabilityStore())
    assert len(refusals) == 2
    assert "has no combinability filed" in refusals[0]


def test_a_fare_that_does_not_permit_the_shape_refuses(flat_converter):
    store = CombinabilityStore()
    store.add(record(round_trip=False, circle_trip=True))
    unit = assemble_round_trip(out(flat_converter), home(flat_converter))
    trace = Trace()
    refusals = check_unit(unit, store, trace)
    assert refusals == [
        "LH KLWAP7RT does not permit RT",
        "LH KLWAP7RT does not permit RT",
    ]
    assert trace.stage_failed(Stage.COMBINABILITY)


def test_a_same_carrier_filing_refuses_a_mixed_carrier_unit(flat_converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=True))
    store.add(record(carrier="UA", same_carrier_only=False))
    unit = assemble_round_trip(out(flat_converter), home(flat_converter, carrier="UA"))
    refusals = check_unit(unit, store)
    assert any("same carrier only" in r for r in refusals)


def test_a_mixed_carrier_unit_passes_when_neither_side_insists(flat_converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=False))
    store.add(record(carrier="UA", same_carrier_only=False))
    unit = assemble_round_trip(out(flat_converter), home(flat_converter, carrier="UA"))
    assert check_unit(unit, store) == []


def test_a_same_rule_filing_refuses_two_different_bases(flat_converter):
    store = CombinabilityStore()
    store.add(record(same_carrier_only=False, same_rule_only=True))
    store.add(record(basis="QLWAP14RT", same_carrier_only=False))
    unit = assemble_round_trip(out(flat_converter), home(flat_converter, basis="QLWAP14RT"))
    refusals = check_unit(unit, store)
    assert any("same rule only" in r for r in refusals)


def test_combinability_runs_after_the_unit_is_priced(flat_converter):
    unit = assemble_round_trip(out(flat_converter), home(flat_converter))
    unpriced = unit.replace(
        0,
        FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
        ),
    )
    with pytest.raises(CombinabilityError):
        check_unit(unpriced, CombinabilityStore())


def test_one_pricing_unit_needs_no_end_on_end_permission(flat_converter):
    unit = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    assert check_end_on_end([unit], CombinabilityStore(), trace) == []
    assert not trace.failed


def test_two_units_both_permitting_end_on_end_pass(flat_converter):
    store = CombinabilityStore()
    store.add(record(end_on_end=True))
    one = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    assert check_end_on_end([one, two], store) == []


def test_one_fare_refusing_end_on_end_stops_the_whole_ticket(flat_converter):
    store = CombinabilityStore()
    store.add(record(end_on_end=True))
    store.add(record(basis="QLWAP14RT", end_on_end=False))
    one = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(flat_converter, basis="QLWAP14RT", owrt=OwRt.ONE_WAY_ONLY))
    trace = Trace()
    refusals = check_end_on_end([one, two], store, trace)
    assert refusals == ["LH QLWAP14RT does not permit end on end"]
    assert trace.stage_failed(Stage.COMBINABILITY)


def test_an_unfiled_fare_refuses_end_on_end_too(flat_converter):
    one = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    two = assemble_one_way(home(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    refusals = check_end_on_end([one, two], CombinabilityStore())
    assert len(refusals) == 2


def test_end_on_end_runs_after_pricing(flat_converter):
    unpriced = PricingUnit(
        (
            FareComponent(
                (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
            ),
        ),
        JourneyType.ONE_WAY,
    )
    one = assemble_one_way(out(flat_converter, owrt=OwRt.ONE_WAY_ONLY))
    with pytest.raises(CombinabilityError):
        check_end_on_end([one, unpriced], CombinabilityStore())
