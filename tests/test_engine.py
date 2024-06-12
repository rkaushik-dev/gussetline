from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.combinability import Combinability, CombinabilityStore
from gussetline.distance import MileageTable
from gussetline.engine import Tariffs, price
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter, RateTable
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.surcharges import QSurcharge, SurchargeTable
from gussetline.tariff import Fare, OwRt, TariffStore
from gussetline.trace import Stage

TICKETING = date(2025, 2, 14)


@pytest.fixture
def converter():
    table = RateTable("roe")
    table.add("EUR", date(2025, 1, 1), "1.000")
    return Converter(table)


@pytest.fixture
def miles():
    table = MileageTable()
    table.add("FRA", "NYC", 4400)
    table.add("FRA", "LON", 500)
    table.add("LON", "NYC", 4400)
    table.add("FRA", "CHI", 4300)
    table.add("CHI", "NYC", 740)
    return table


def a_fare(origin, destination, amount, basis="KLWAP7RT", **over) -> Fare:
    base = {
        "carrier": "LH",
        "origin": origin,
        "destination": destination,
        "fare_basis": basis,
        "amount": Money.of(amount, "EUR"),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": "0000",
    }
    base.update(over)
    return Fare(**base)


def combinable(basis="KLWAP7RT"):
    return Combinability(
        carrier="LH",
        fare_basis=basis,
        round_trip=True,
        circle_trip=True,
        open_jaw=True,
        end_on_end=True,
        same_carrier_only=False,
    )


@pytest.fixture
def tariffs(miles):
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "420.00"))
    fares.add(a_fare("JFK", "FRA", "420.00"))
    combos = CombinabilityStore()
    combos.add(combinable())
    return Tariffs(fares=fares, miles=miles, combinability=combos)


def round_trip():
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


def one_way():
    return Itinerary([segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")])


def test_a_round_trip_prices_end_to_end(converter, tariffs):
    result = price(round_trip(), tariffs, converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("840.00")
    assert result.base_fare == Money.of("840", "EUR")
    assert result.issuable


def test_the_line_reads_like_a_ticket(converter, tariffs):
    result = price(round_trip(), tariffs, converter, TICKETING, "EUR")
    assert result.line == "FRA LH NYC 420.00 LH FRA 420.00 NUC840.00END ROE1.000000"


def test_the_trace_carries_a_selection_for_each_component(converter, tariffs):
    result = price(round_trip(), tariffs, converter, TICKETING, "EUR")
    assert len(result.trace.for_stage(Stage.SELECTION)) >= 2
    assert not result.trace.failed


def test_a_one_way_prices_on_one_component(converter, miles):
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "310.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(one_way(), Tariffs(fares=fares, miles=miles), converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("310.00")
    assert result.line == "FRA LH NYC 310.00 NUC310.00END ROE1.000000"


def test_a_market_with_nothing_published_refuses(converter, miles):
    with pytest.raises(NoFareFound):
        price(round_trip(), Tariffs(fares=TariffStore(), miles=miles), converter, TICKETING, "EUR")


def test_a_connection_is_marked_on_the_line(converter, miles):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "420.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(trip, Tariffs(fares=fares, miles=miles), converter, TICKETING, "EUR")
    assert "X/LON" in result.line


def test_a_surcharge_lands_before_the_minimum_checks(converter, tariffs, miles):
    charges = SurchargeTable()
    charges.add(
        QSurcharge(carrier="LH", origin="FRA", destination="JFK", amount=Money.of("30.00", "EUR"))
    )
    loaded = Tariffs(
        fares=tariffs.fares,
        miles=miles,
        surcharges=charges,
        combinability=tariffs.combinability,
    )
    result = price(round_trip(), loaded, converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("900.00")
    assert "Q30.00" in result.line
    assert len(result.trace.for_stage(Stage.SURCHARGE)) == 2


def test_a_higher_intermediate_point_raises_the_component(converter, miles):
    trip = Itinerary(
        [
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
        ]
    )
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "420.00", owrt=OwRt.ONE_WAY_ONLY))
    fares.add(a_fare("FRA", "ORD", "560.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(trip, Tariffs(fares=fares, miles=miles), converter, TICKETING, "EUR", breaks=())
    assert result.total_nuc == Decimal("560.00")
    assert any("higher intermediate point" in s.message for s in result.trace)


def test_a_fare_with_no_combinability_filed_prices_but_is_not_issuable(converter, miles):
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "420.00"))
    fares.add(a_fare("JFK", "FRA", "420.00"))
    result = price(round_trip(), Tariffs(fares=fares, miles=miles), converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("840.00")
    assert not result.issuable
    assert "has no combinability filed" in result.refusals[0]


def test_an_explicit_break_is_used_instead_of_the_turnaround(converter, tariffs, miles):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(
        a_fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(a_fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    combos = CombinabilityStore()
    combos.add(combinable())
    loaded = Tariffs(fares=fares, miles=miles, combinability=combos)
    result = price(trip, loaded, converter, TICKETING, "EUR", breaks=(0,))
    assert result.total_nuc == Decimal("390.00")
    assert result.line.startswith("FRA LH LON 90.00 LH NYC 300.00")


def test_the_conversion_into_the_selling_currency_happens_once(converter, tariffs):
    result = price(round_trip(), tariffs, converter, TICKETING, "EUR")
    conversions = [s for s in result.trace.for_stage(Stage.CONVERSION) if "becomes" in s.message]
    assert len(conversions) == 1


def test_a_priced_result_reads_back_as_the_fare_and_the_line(converter, tariffs):
    result = price(round_trip(), tariffs, converter, TICKETING, "EUR")
    assert str(result).startswith("840 EUR on FRA LH NYC")


def test_a_split_that_does_not_fit_the_shape_prices_end_on_end(converter, miles):
    # Two components on a journey that is a one way. Grouping them into one
    # unit would drop the second, so they become two units joined end on end.
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(
        a_fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(a_fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    combos = CombinabilityStore()
    combos.add(combinable())
    result = price(
        trip,
        Tariffs(fares=fares, miles=miles, combinability=combos),
        converter,
        TICKETING,
        "EUR",
        breaks=(0,),
    )
    assert result.total_nuc == Decimal("390.00")
    assert len(result.construction.units) == 2
    assert result.issuable


def test_the_band_applies_to_the_fare_the_minimum_check_left_governing(converter):
    # The Chicago fare governs after the higher intermediate point check, and
    # the five percent band is five percent of Chicago and not of New York.
    table = MileageTable()
    table.add("FRA", "NYC", 4000)
    table.add("FRA", "CHI", 4300)
    table.add("CHI", "NYC", 740)
    trip = Itinerary(
        [
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
        ]
    )
    fares = TariffStore()
    fares.add(a_fare("FRA", "JFK", "400.00", owrt=OwRt.ONE_WAY_ONLY))
    fares.add(a_fare("FRA", "ORD", "600.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(trip, Tariffs(fares=fares, miles=table), converter, TICKETING, "EUR", breaks=())
    # 600 governing, five percent of that is 30, not five percent of 400.
    assert result.total_nuc == Decimal("630.00")
    assert "5M" in result.line


def test_every_component_reaches_the_line_when_the_split_is_wide(converter, miles):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(
        a_fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(a_fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        trip, Tariffs(fares=fares, miles=miles), converter, TICKETING, "EUR", breaks=(0,)
    )
    assert result.line.startswith("FRA LH LON 90.00 LH NYC 300.00")
    assert result.line.endswith("NUC390.00END ROE1.000000")
