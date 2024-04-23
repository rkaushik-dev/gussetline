from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.construction import price_component
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter, RateTable
from gussetline.indicators import GlobalIndicator
from gussetline.minimums import MinimumResult, apply_hip, hip_check
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
