from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import fare, segment
from gussetline.components import FareComponent, PricingUnit
from gussetline.construction import (
    assemble_circle_trip,
    assemble_round_trip,
    price_component,
)
from gussetline.errors import NoFareFound
from gussetline.itinerary import JourneyType
from gussetline.minimums import (
    MinimumResult,
    apply_hip,
    apply_minimum,
    backhaul_check,
    circle_trip_minimum,
    highest_of,
    hip_check,
    open_jaw_minimum,
)
from gussetline.tariff import OwRt, TariffStore
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


def component_via_chicago():
    return FareComponent(
        (
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "LAX", "2025-03-06 08:00", "2025-03-06 10:30"),
        )
    )


def straight_through():
    return FareComponent(
        (segment("LH", "456", "FRA", "LAX", "2025-03-04 10:00", "2025-03-04 13:20"),)
    )


def test_a_result_with_nothing_added_reads_as_its_reason():
    assert str(MinimumResult(Decimal(0), "no higher intermediate point")) == (
        "no higher intermediate point"
    )
    assert not MinimumResult(Decimal(0), "x").applied


def test_a_result_that_applied_names_the_pair():
    result = MinimumResult(Decimal("40.00"), "higher intermediate point", ("FRA", "CHI"))
    assert result.applied
    assert str(result) == "higher intermediate point on FRA-CHI, up 40.00 NUC"


def test_a_minimum_check_runs_after_the_component_is_priced(flat_converter):
    with pytest.raises(NoFareFound):
        hip_check(component_via_chicago(), TariffStore(), flat_converter, TICKETING, ("ORD",))


def test_a_component_with_no_stopover_has_nothing_to_compare(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    priced = price_component(
        straight_through(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    trace = Trace()
    result = hip_check(priced, store, flat_converter, TICKETING, (), trace)
    assert not result.applied
    assert "no stopover to compare" in trace.for_stage(Stage.MINIMUM)[0].message


def test_a_connection_is_not_a_stopover_and_does_not_trigger_the_check(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "900.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    # Chicago is flown through but is not named as a stopover.
    result = hip_check(priced, store, flat_converter, TICKETING, (), None)
    assert not result.applied


def test_a_dearer_intermediate_fare_raises_the_component(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "620.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    trace = Trace()
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",), trace)
    assert result.applied
    assert result.added == Decimal("120.00")
    assert result.pair == ("FRA", "CHI")
    assert trace.for_stage(Stage.MINIMUM)[0].details["pair"] == "FRA-CHI"


def test_a_cheaper_intermediate_fare_changes_nothing(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "410.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    assert not result.applied
    assert result.reason == "no higher intermediate point"


def test_the_check_looks_at_the_far_half_as_well(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("ORD", "LAX", "700.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    assert result.applied
    assert result.pair == ("CHI", "LAX")


def test_the_highest_of_several_candidates_wins(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "620.00"))
    store.add(fare("ORD", "LAX", "740.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    assert result.added == Decimal("240.00")
    assert result.pair == ("CHI", "LAX")


def test_an_intermediate_market_with_nothing_published_does_not_constrain(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    assert not result.applied


def test_two_stopovers_are_compared_against_each_other_as_well(flat_converter):
    elements = (
        segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
        segment("UA", "200", "ORD", "DEN", "2025-03-06 08:00", "2025-03-06 09:30"),
        segment("UA", "300", "DEN", "LAX", "2025-03-09 08:00", "2025-03-09 09:40"),
    )
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("ORD", "DEN", "900.00"))
    priced = price_component(
        FareComponent(elements), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD", "DEN"))
    assert result.applied
    assert result.pair == ("CHI", "DEN")


def test_a_stopover_named_that_is_not_on_the_component_is_ignored(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "620.00"))
    priced = price_component(
        straight_through(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    assert not result.applied


def test_applying_a_finding_adds_a_surcharge_and_keeps_the_fare_basis(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    store.add(fare("FRA", "ORD", "620.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    result = hip_check(priced, store, flat_converter, TICKETING, ("ORD",))
    raised = apply_hip(priced, result)
    assert raised.total_nuc == Decimal("620.00")
    assert raised.notes == ("HIP",)
    assert raised.fare is not None
    assert raised.fare.fare_basis == "KLWAP7RT"


def test_applying_nothing_leaves_the_component_alone(flat_converter):
    priced = price_component(
        straight_through(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    assert apply_hip(priced, MinimumResult(Decimal(0), "no")) is priced


def backtracking():
    """Frankfurt to Chicago the long way, out past Los Angeles first."""
    return FareComponent(
        (
            segment("LH", "456", "FRA", "LAX", "2025-03-04 10:00", "2025-03-04 13:20"),
            segment("UA", "300", "LAX", "ORD", "2025-03-06 08:00", "2025-03-06 14:00"),
        )
    )


def test_a_component_with_nothing_beyond_its_destination_has_no_backhaul(
    flat_converter, transatlantic_miles
):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "500.00"))
    priced = price_component(
        component_via_chicago(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    trace = Trace()
    result = backhaul_check(priced, store, flat_converter, TICKETING, transatlantic_miles, trace)
    assert not result.applied
    assert "nothing is beyond the destination" in trace.for_stage(Stage.MINIMUM)[0].message


def test_a_backtrack_to_a_dearer_point_is_charged(flat_converter, transatlantic_miles):
    store = TariffStore()
    store.add(fare("FRA", "ORD", "500.00"))
    store.add(fare("FRA", "LAX", "680.00"))
    priced = price_component(
        backtracking(), fare("FRA", "ORD", "500.00"), flat_converter, TICKETING
    )
    result = backhaul_check(priced, store, flat_converter, TICKETING, transatlantic_miles)
    assert result.applied
    assert result.added == Decimal("180.00")
    assert result.pair == ("FRA", "LAX")


def test_a_backtrack_to_a_cheaper_point_costs_nothing(flat_converter, transatlantic_miles):
    store = TariffStore()
    store.add(fare("FRA", "ORD", "500.00"))
    store.add(fare("FRA", "LAX", "420.00"))
    priced = price_component(
        backtracking(), fare("FRA", "ORD", "500.00"), flat_converter, TICKETING
    )
    trace = Trace()
    result = backhaul_check(priced, store, flat_converter, TICKETING, transatlantic_miles, trace)
    assert not result.applied
    assert "backtrack found but no dearer fare" in trace.for_stage(Stage.MINIMUM)[0].message


def test_the_backhaul_check_looks_at_connections_too(flat_converter, transatlantic_miles):
    # Los Angeles is flown through on a two hour connection and never named
    # as a stopover, and the check still fires, which is what separates it
    # from the higher intermediate point check.
    store = TariffStore()
    store.add(fare("FRA", "ORD", "500.00"))
    store.add(fare("FRA", "LAX", "680.00"))
    priced = price_component(
        backtracking(), fare("FRA", "ORD", "500.00"), flat_converter, TICKETING
    )
    hip = hip_check(priced, store, flat_converter, TICKETING, ())
    backhaul = backhaul_check(priced, store, flat_converter, TICKETING, transatlantic_miles)
    assert not hip.applied
    assert backhaul.applied


def test_the_backhaul_check_runs_after_pricing(flat_converter, transatlantic_miles):
    with pytest.raises(NoFareFound):
        backhaul_check(
            backtracking(), TariffStore(), flat_converter, TICKETING, transatlantic_miles
        )


def test_the_two_checks_do_not_stack_and_the_dearer_one_wins():
    hip = MinimumResult(Decimal("120.00"), "higher intermediate point", ("FRA", "CHI"))
    backhaul = MinimumResult(Decimal("180.00"), "backhaul", ("FRA", "LAX"))
    assert highest_of(hip, backhaul) is backhaul
    assert highest_of(backhaul, hip) is backhaul


def test_the_highest_of_nothing_is_nothing():
    assert not highest_of().applied
    assert not highest_of(MinimumResult(Decimal(0), "a"), MinimumResult(Decimal(0), "b")).applied


def test_a_minimum_lands_under_the_label_it_was_given(flat_converter):
    priced = price_component(
        straight_through(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    raised = apply_minimum(priced, MinimumResult(Decimal("40.00"), "backhaul"), "BHC")
    assert raised.notes == ("BHC",)
    assert raised.total_nuc == Decimal("540.00")


def test_applying_a_finding_that_did_not_apply_changes_nothing(flat_converter):
    priced = price_component(
        straight_through(), fare("FRA", "LAX", "500.00"), flat_converter, TICKETING
    )
    assert apply_minimum(priced, MinimumResult(Decimal(0), "no"), "BHC") is priced


def leg(carrier, number, origin, destination, departure, arrival):
    return FareComponent((segment(carrier, number, origin, destination, departure, arrival),))


def circle(flat_converter):
    """Frankfurt to New York to Los Angeles and home, three components."""
    parts = (
        price_component(
            leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            fare("FRA", "JFK", "300.00"),
            flat_converter,
            TICKETING,
        ),
        price_component(
            leg("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
            fare("JFK", "LAX", "150.00"),
            flat_converter,
            TICKETING,
        ),
        price_component(
            leg("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20"),
            fare("LAX", "FRA", "350.00"),
            flat_converter,
            TICKETING,
        ),
    )
    return assemble_circle_trip(parts)


def test_a_round_trip_unit_is_not_a_circle_trip_and_the_check_says_so(flat_converter):
    unit = assemble_round_trip(
        price_component(
            leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            fare("FRA", "JFK", "420.00"),
            flat_converter,
            TICKETING,
        ),
        price_component(
            leg("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
            fare("JFK", "FRA", "420.00"),
            flat_converter,
            TICKETING,
        ),
    )
    result = circle_trip_minimum(unit, TariffStore(), flat_converter, TICKETING)
    assert not result.applied
    assert result.reason == "not a circle trip"


def test_a_circle_trip_that_clears_every_round_trip_is_left_alone(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "JFK", "300.00"))
    store.add(fare("FRA", "LAX", "350.00"))
    trace = Trace()
    result = circle_trip_minimum(circle(flat_converter), store, flat_converter, TICKETING, trace)
    assert not result.applied
    assert "clears every round trip" in trace.for_stage(Stage.MINIMUM)[0].message


def test_a_circle_trip_that_undercuts_a_round_trip_is_raised_to_it(flat_converter):
    # The three components add to 800. A Frankfurt to Los Angeles round trip
    # half is filed at 480, so the round trip is 960 and that is the floor.
    store = TariffStore()
    store.add(fare("FRA", "JFK", "300.00"))
    store.add(fare("FRA", "LAX", "480.00"))
    result = circle_trip_minimum(circle(flat_converter), store, flat_converter, TICKETING)
    assert result.applied
    assert result.added == Decimal("160.00")
    assert result.pair == ("FRA", "LAX")


def test_a_one_way_filing_is_not_doubled_for_the_comparison(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "LAX", "480.00", owrt=OwRt.ONE_WAY_ONLY))
    result = circle_trip_minimum(circle(flat_converter), store, flat_converter, TICKETING)
    assert not result.applied


def test_a_market_with_nothing_published_does_not_set_a_floor(flat_converter):
    result = circle_trip_minimum(circle(flat_converter), TariffStore(), flat_converter, TICKETING)
    assert not result.applied


def test_the_circle_trip_check_runs_after_the_unit_is_priced(flat_converter):
    unit = circle(flat_converter)
    unpriced = unit.replace(
        0, leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")
    )
    with pytest.raises(NoFareFound):
        circle_trip_minimum(unpriced, TariffStore(), flat_converter, TICKETING)


def open_jaw(flat_converter):
    """Out to New York, home to Munich instead of Frankfurt."""
    parts = (
        price_component(
            leg("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            fare("FRA", "JFK", "300.00"),
            flat_converter,
            TICKETING,
        ),
        price_component(
            leg("LH", "417", "JFK", "MUC", "2025-03-18 18:30", "2025-03-19 08:05"),
            fare("JFK", "MUC", "320.00"),
            flat_converter,
            TICKETING,
        ),
    )
    return PricingUnit(parts, JourneyType.OPEN_JAW)


def test_a_round_trip_unit_is_not_an_open_jaw(flat_converter):
    result = open_jaw_minimum(circle(flat_converter), TariffStore(), flat_converter, TICKETING)
    assert not result.applied
    assert result.reason == "not an open jaw"


def test_an_open_jaw_that_clears_its_round_trip_is_left_alone(flat_converter):
    store = TariffStore()
    store.add(fare("FRA", "JFK", "300.00"))
    trace = Trace()
    result = open_jaw_minimum(open_jaw(flat_converter), store, flat_converter, TICKETING, trace)
    assert not result.applied
    assert "clears the round trip it varies" in trace.for_stage(Stage.MINIMUM)[0].message


def test_an_open_jaw_that_undercuts_its_round_trip_is_raised(flat_converter):
    # The two components add to 620. The Frankfurt to New York round trip is
    # 700, so the open jaw may not be sold for less than that.
    store = TariffStore()
    store.add(fare("FRA", "JFK", "350.00"))
    result = open_jaw_minimum(open_jaw(flat_converter), store, flat_converter, TICKETING)
    assert result.applied
    assert result.added == Decimal("80.00")
    assert result.pair == ("FRA", "NYC")


def test_an_open_jaw_with_no_round_trip_filed_has_no_floor(flat_converter):
    result = open_jaw_minimum(open_jaw(flat_converter), TariffStore(), flat_converter, TICKETING)
    assert not result.applied
