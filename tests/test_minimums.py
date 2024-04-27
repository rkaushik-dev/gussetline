from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.construction import price_component
from gussetline.distance import MileageTable
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter, RateTable
from gussetline.indicators import GlobalIndicator
from gussetline.minimums import (
    MinimumResult,
    apply_hip,
    apply_minimum,
    backhaul_check,
    highest_of,
    hip_check,
)
from gussetline.money import Money
from gussetline.tariff import Fare, OwRt, TariffStore
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


@pytest.fixture
def converter():
    table = RateTable("roe")
    for code in ("EUR", "USD"):
        table.add(code, date(2025, 1, 1), "1.000")
    return Converter(table)


def a_fare(origin, destination, amount, **over) -> Fare:
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


def test_a_minimum_check_runs_after_the_component_is_priced(converter):
    with pytest.raises(NoFareFound):
        hip_check(component_via_chicago(), TariffStore(), converter, TICKETING, ("ORD",))


def test_a_component_with_no_stopover_has_nothing_to_compare(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    priced = price_component(
        straight_through(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    trace = Trace()
    result = hip_check(priced, store, converter, TICKETING, (), trace)
    assert not result.applied
    assert "no stopover to compare" in trace.for_stage(Stage.MINIMUM)[0].message


def test_a_connection_is_not_a_stopover_and_does_not_trigger_the_check(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "900.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    # Chicago is flown through but is not named as a stopover.
    result = hip_check(priced, store, converter, TICKETING, (), None)
    assert not result.applied


def test_a_dearer_intermediate_fare_raises_the_component(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "620.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    trace = Trace()
    result = hip_check(priced, store, converter, TICKETING, ("ORD",), trace)
    assert result.applied
    assert result.added == Decimal("120.00")
    assert result.pair == ("FRA", "CHI")
    assert trace.for_stage(Stage.MINIMUM)[0].details["pair"] == "FRA-CHI"


def test_a_cheaper_intermediate_fare_changes_nothing(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "410.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    assert not result.applied
    assert result.reason == "no higher intermediate point"


def test_the_check_looks_at_the_far_half_as_well(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("ORD", "LAX", "700.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    assert result.applied
    assert result.pair == ("CHI", "LAX")


def test_the_highest_of_several_candidates_wins(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "620.00"))
    store.add(a_fare("ORD", "LAX", "740.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    assert result.added == Decimal("240.00")
    assert result.pair == ("CHI", "LAX")


def test_an_intermediate_market_with_nothing_published_does_not_constrain(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    assert not result.applied


def test_two_stopovers_are_compared_against_each_other_as_well(converter):
    elements = (
        segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
        segment("UA", "200", "ORD", "DEN", "2025-03-06 08:00", "2025-03-06 09:30"),
        segment("UA", "300", "DEN", "LAX", "2025-03-09 08:00", "2025-03-09 09:40"),
    )
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("ORD", "DEN", "900.00"))
    priced = price_component(
        FareComponent(elements), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD", "DEN"))
    assert result.applied
    assert result.pair == ("CHI", "DEN")


def test_a_stopover_named_that_is_not_on_the_component_is_ignored(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "620.00"))
    priced = price_component(
        straight_through(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    assert not result.applied


def test_applying_a_finding_adds_a_surcharge_and_keeps_the_fare_basis(converter):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    store.add(a_fare("FRA", "ORD", "620.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    result = hip_check(priced, store, converter, TICKETING, ("ORD",))
    raised = apply_hip(priced, result)
    assert raised.total_nuc == Decimal("620.00")
    assert raised.notes == ("HIP",)
    assert raised.fare is not None
    assert raised.fare.fare_basis == "KLWAP7RT"


def test_applying_nothing_leaves_the_component_alone(converter):
    priced = price_component(
        straight_through(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    assert apply_hip(priced, MinimumResult(Decimal(0), "no")) is priced


@pytest.fixture
def miles():
    table = MileageTable()
    table.add("FRA", "CHI", 4300)
    table.add("FRA", "LAX", 5800)
    table.add("FRA", "DEN", 5100)
    table.add("CHI", "LAX", 1700)
    return table


def backtracking():
    """Frankfurt to Chicago the long way, out past Los Angeles first."""
    return FareComponent(
        (
            segment("LH", "456", "FRA", "LAX", "2025-03-04 10:00", "2025-03-04 13:20"),
            segment("UA", "300", "LAX", "ORD", "2025-03-06 08:00", "2025-03-06 14:00"),
        )
    )


def test_a_component_with_nothing_beyond_its_destination_has_no_backhaul(converter, miles):
    store = TariffStore()
    store.add(a_fare("FRA", "LAX", "500.00"))
    priced = price_component(
        component_via_chicago(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    trace = Trace()
    result = backhaul_check(priced, store, converter, TICKETING, miles, trace)
    assert not result.applied
    assert "nothing is beyond the destination" in trace.for_stage(Stage.MINIMUM)[0].message


def test_a_backtrack_to_a_dearer_point_is_charged(converter, miles):
    store = TariffStore()
    store.add(a_fare("FRA", "ORD", "500.00"))
    store.add(a_fare("FRA", "LAX", "680.00"))
    priced = price_component(backtracking(), a_fare("FRA", "ORD", "500.00"), converter, TICKETING)
    result = backhaul_check(priced, store, converter, TICKETING, miles)
    assert result.applied
    assert result.added == Decimal("180.00")
    assert result.pair == ("FRA", "LAX")


def test_a_backtrack_to_a_cheaper_point_costs_nothing(converter, miles):
    store = TariffStore()
    store.add(a_fare("FRA", "ORD", "500.00"))
    store.add(a_fare("FRA", "LAX", "420.00"))
    priced = price_component(backtracking(), a_fare("FRA", "ORD", "500.00"), converter, TICKETING)
    trace = Trace()
    result = backhaul_check(priced, store, converter, TICKETING, miles, trace)
    assert not result.applied
    assert "backtrack found but no dearer fare" in trace.for_stage(Stage.MINIMUM)[0].message


def test_the_backhaul_check_looks_at_connections_too(converter, miles):
    # Los Angeles is flown through on a two hour connection and never named
    # as a stopover, and the check still fires, which is what separates it
    # from the higher intermediate point check.
    store = TariffStore()
    store.add(a_fare("FRA", "ORD", "500.00"))
    store.add(a_fare("FRA", "LAX", "680.00"))
    priced = price_component(backtracking(), a_fare("FRA", "ORD", "500.00"), converter, TICKETING)
    hip = hip_check(priced, store, converter, TICKETING, ())
    backhaul = backhaul_check(priced, store, converter, TICKETING, miles)
    assert not hip.applied
    assert backhaul.applied


def test_the_backhaul_check_runs_after_pricing(converter, miles):
    with pytest.raises(NoFareFound):
        backhaul_check(backtracking(), TariffStore(), converter, TICKETING, miles)


def test_the_two_checks_do_not_stack_and_the_dearer_one_wins():
    hip = MinimumResult(Decimal("120.00"), "higher intermediate point", ("FRA", "CHI"))
    backhaul = MinimumResult(Decimal("180.00"), "backhaul", ("FRA", "LAX"))
    assert highest_of(hip, backhaul) is backhaul
    assert highest_of(backhaul, hip) is backhaul


def test_the_highest_of_nothing_is_nothing():
    assert not highest_of().applied
    assert not highest_of(MinimumResult(Decimal(0), "a"), MinimumResult(Decimal(0), "b")).applied


def test_a_minimum_lands_under_the_label_it_was_given(converter):
    priced = price_component(
        straight_through(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    raised = apply_minimum(priced, MinimumResult(Decimal("40.00"), "backhaul"), "BHC")
    assert raised.notes == ("BHC",)
    assert raised.total_nuc == Decimal("540.00")


def test_applying_a_finding_that_did_not_apply_changes_nothing(converter):
    priced = price_component(
        straight_through(), a_fare("FRA", "LAX", "500.00"), converter, TICKETING
    )
    assert apply_minimum(priced, MinimumResult(Decimal(0), "no"), "BHC") is priced
