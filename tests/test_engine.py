from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import combinable, fare, segment
from gussetline.combinability import CombinabilityStore
from gussetline.distance import MileageTable
from gussetline.engine import Tariffs, price
from gussetline.errors import NoFareFound
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.surcharges import QSurcharge, SurchargeTable
from gussetline.tariff import OwRt, TariffStore
from gussetline.trace import Stage

TICKETING = date(2025, 2, 14)


@pytest.fixture
def tariffs(transatlantic_miles):
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "420.00"))
    fares.add(fare("JFK", "FRA", "420.00"))
    combos = CombinabilityStore()
    combos.add(combinable())
    return Tariffs(fares=fares, miles=transatlantic_miles, combinability=combos)


def round_trip():
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


def one_way():
    return Itinerary([segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")])


def test_a_round_trip_prices_end_to_end(flat_converter, tariffs):
    result = price(round_trip(), tariffs, flat_converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("840.00")
    assert result.base_fare == Money.of("840", "EUR")
    assert result.issuable


def test_the_line_reads_like_a_ticket(flat_converter, tariffs):
    result = price(round_trip(), tariffs, flat_converter, TICKETING, "EUR")
    assert result.line == "FRA LH NYC 420.00 LH FRA 420.00 NUC840.00END ROE1.000000"


def test_the_trace_carries_a_selection_for_each_component(flat_converter, tariffs):
    result = price(round_trip(), tariffs, flat_converter, TICKETING, "EUR")
    assert len(result.trace.for_stage(Stage.SELECTION)) >= 2
    assert not result.trace.failed


def test_a_one_way_prices_on_one_component(flat_converter, transatlantic_miles):
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "310.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        one_way(),
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        "EUR",
    )
    assert result.total_nuc == Decimal("310.00")
    assert result.line == "FRA LH NYC 310.00 NUC310.00END ROE1.000000"


def test_a_market_with_nothing_published_refuses(flat_converter, transatlantic_miles):
    with pytest.raises(NoFareFound):
        price(
            round_trip(),
            Tariffs(fares=TariffStore(), miles=transatlantic_miles),
            flat_converter,
            TICKETING,
            "EUR",
        )


def test_a_connection_is_marked_on_the_line(flat_converter, transatlantic_miles):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "420.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        trip,
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        "EUR",
    )
    assert "X/LON" in result.line


def test_a_surcharge_lands_before_the_minimum_checks(flat_converter, tariffs, transatlantic_miles):
    charges = SurchargeTable()
    charges.add(
        QSurcharge(carrier="LH", origin="FRA", destination="JFK", amount=Money.of("30.00", "EUR"))
    )
    loaded = Tariffs(
        fares=tariffs.fares,
        miles=transatlantic_miles,
        surcharges=charges,
        combinability=tariffs.combinability,
    )
    result = price(round_trip(), loaded, flat_converter, TICKETING, "EUR")
    assert result.total_nuc == Decimal("900.00")
    assert "Q30.00" in result.line
    assert len(result.trace.for_stage(Stage.SURCHARGE)) == 2


def test_a_higher_intermediate_point_raises_the_component(flat_converter, transatlantic_miles):
    trip = Itinerary(
        [
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
        ]
    )
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "420.00", owrt=OwRt.ONE_WAY_ONLY))
    fares.add(fare("FRA", "ORD", "560.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        trip,
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        "EUR",
        breaks=(),
    )
    assert result.total_nuc == Decimal("560.00")
    assert any("higher intermediate point" in s.message for s in result.trace)


def test_a_fare_with_no_combinability_filed_prices_but_is_not_issuable(
    flat_converter, transatlantic_miles
):
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "420.00"))
    fares.add(fare("JFK", "FRA", "420.00"))
    result = price(
        round_trip(),
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        "EUR",
    )
    assert result.total_nuc == Decimal("840.00")
    assert not result.issuable
    assert "has no combinability filed" in result.refusals[0]


def test_an_explicit_break_is_used_instead_of_the_turnaround(
    flat_converter, tariffs, transatlantic_miles
):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(
        fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    combos = CombinabilityStore()
    combos.add(combinable())
    loaded = Tariffs(fares=fares, miles=transatlantic_miles, combinability=combos)
    result = price(trip, loaded, flat_converter, TICKETING, "EUR", breaks=(0,))
    assert result.total_nuc == Decimal("390.00")
    assert result.line.startswith("FRA LH LON 90.00 LH NYC 300.00")


def test_the_conversion_into_the_selling_currency_happens_once(flat_converter, tariffs):
    result = price(round_trip(), tariffs, flat_converter, TICKETING, "EUR")
    conversions = [s for s in result.trace.for_stage(Stage.CONVERSION) if "becomes" in s.message]
    assert len(conversions) == 1


def test_a_priced_result_reads_back_as_the_fare_and_the_line(flat_converter, tariffs):
    result = price(round_trip(), tariffs, flat_converter, TICKETING, "EUR")
    assert str(result).startswith("840 EUR on FRA LH NYC")


def test_a_split_that_does_not_fit_the_shape_prices_end_on_end(flat_converter, transatlantic_miles):
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
        fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    combos = CombinabilityStore()
    combos.add(combinable())
    result = price(
        trip,
        Tariffs(fares=fares, miles=transatlantic_miles, combinability=combos),
        flat_converter,
        TICKETING,
        "EUR",
        breaks=(0,),
    )
    assert result.total_nuc == Decimal("390.00")
    assert len(result.construction.units) == 2
    assert result.issuable


def test_the_band_applies_to_the_fare_the_minimum_check_left_governing(flat_converter):
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
    fares.add(fare("FRA", "JFK", "400.00", owrt=OwRt.ONE_WAY_ONLY))
    fares.add(fare("FRA", "ORD", "600.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        trip,
        Tariffs(fares=fares, miles=table),
        flat_converter,
        TICKETING,
        "EUR",
        breaks=(),
    )
    # 600 governing, five percent of that is 30, not five percent of 400.
    assert result.total_nuc == Decimal("630.00")
    assert "5M" in result.line


def test_every_component_reaches_the_line_when_the_split_is_wide(
    flat_converter, transatlantic_miles
):
    trip = Itinerary(
        [
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        ]
    )
    fares = TariffStore()
    fares.add(
        fare("FRA", "LHR", "90.00", owrt=OwRt.ONE_WAY_ONLY, global_indicator=GlobalIndicator.EH)
    )
    fares.add(fare("LHR", "JFK", "300.00", owrt=OwRt.ONE_WAY_ONLY))
    result = price(
        trip,
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        "EUR",
        breaks=(0,),
    )
    assert result.line.startswith("FRA LH LON 90.00 LH NYC 300.00")
    assert result.line.endswith("NUC390.00END ROE1.000000")
