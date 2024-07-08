from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from conftest import fare, segment
from gussetline.audit import (
    Finding,
    audit,
    audit_line,
    by_finding,
    recovered_total,
)
from gussetline.combinability import Combinability, CombinabilityStore
from gussetline.engine import Tariffs, price
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.tariff import TariffStore
from gussetline.taxes import AppliedTax
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


@pytest.fixture
def tariffs(transatlantic_miles):
    fares = TariffStore()
    fares.add(fare("FRA", "JFK", "420.00"))
    fares.add(fare("JFK", "FRA", "420.00"))
    combos = CombinabilityStore()
    combos.add(
        Combinability(
            carrier="LH",
            fare_basis="KLWAP7RT",
            round_trip=True,
            end_on_end=True,
            same_carrier_only=False,
        )
    )
    return Tariffs(fares=fares, miles=transatlantic_miles, combinability=combos)


def trip():
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


def an_offer(base="840.00", taxes=(), quoted=None, itinerary=None) -> Offer:
    return Offer(
        source="gds",
        itinerary=itinerary if itinerary is not None else trip(),
        breakdown=PriceBreakdown(Money.of(base, "EUR"), tuple(taxes), quoted),
        fare_bases=("KLWAP7RT",),
        observed_at=datetime(2025, 2, 14, 9, 0),
    )


def tax(code="DE", amount="70.83"):
    return AppliedTax(code, Money.of(amount, "EUR"), "FRA")


def test_an_offer_that_matches_the_tariff_agrees(flat_converter, tariffs):
    result = audit(an_offer("840.00"), tariffs, flat_converter, TICKETING)
    assert result.finding is Finding.AGREES
    assert result.ours == Money.of("840", "EUR")
    assert not result.material
    assert not result.claimable


def test_a_gap_under_one_unit_is_not_material(flat_converter, tariffs):
    result = audit(an_offer("840.99"), tariffs, flat_converter, TICKETING)
    assert result.finding is Finding.AGREES
    assert not result.material


def test_a_supplier_charging_more_is_a_claim(flat_converter, tariffs):
    result = audit(an_offer("900.00"), tariffs, flat_converter, TICKETING)
    assert result.finding is Finding.SUPPLIER_HIGH
    assert result.difference == Money.of("60", "EUR")
    assert result.material
    assert result.claimable
    assert result.recovered == Money.of("60", "EUR")


def test_a_supplier_charging_less_is_our_tariff_being_stale(flat_converter, tariffs):
    result = audit(an_offer("700.00"), tariffs, flat_converter, TICKETING)
    assert result.finding is Finding.SUPPLIER_LOW
    assert result.material
    assert not result.claimable
    assert result.recovered == Money.zero("EUR")


def test_a_breakdown_that_does_not_add_up_is_a_tax_difference(flat_converter, tariffs):
    offer = an_offer("900.00", taxes=(tax(),), quoted=Money.of("1000.00", "EUR"))
    result = audit(offer, tariffs, flat_converter, TICKETING)
    assert result.finding is Finding.TAX_DIFFERENCE
    assert not result.claimable


def test_a_gap_that_lands_on_a_minimum_check_is_marked_as_ours(flat_converter, transatlantic_miles):
    # Three components adding to 750, and the Frankfurt to Chicago round trip
    # is 800, so the circle trip minimum raises ours. The supplier is lower
    # and it is our own check that made the gap, not their price.
    fares = TariffStore()
    fares.add(fare("FRA", "ORD", "400.00"))
    fares.add(fare("ORD", "JFK", "50.00", carrier="UA", global_indicator=GlobalIndicator.DO))
    fares.add(fare("JFK", "FRA", "300.00"))
    loop = Itinerary(
        [
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )
    result = audit(
        an_offer("600.00", itinerary=loop),
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        breaks=(0, 1),
    )
    assert result.priced is not None
    assert result.priced.construction.minimum_added > 0
    assert result.finding is Finding.MINIMUM_CHECK
    assert not result.claimable


def test_an_itinerary_the_tariff_cannot_price_says_so(flat_converter, transatlantic_miles):
    result = audit(
        an_offer("840.00"),
        Tariffs(fares=TariffStore(), miles=transatlantic_miles),
        flat_converter,
        TICKETING,
    )
    assert result.finding is Finding.NOT_PRICEABLE
    assert result.ours is None
    assert not result.material
    assert result.reasons


def test_a_failure_to_reprice_is_recorded_on_the_trace(flat_converter, transatlantic_miles):
    trail = Trace()
    audit(
        an_offer("840.00"),
        Tariffs(fares=TariffStore(), miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        trace=trail,
    )
    assert trail.stage_failed(Stage.AUDIT)


def test_an_agreement_is_recorded_as_a_pass(flat_converter, tariffs):
    trail = Trace()
    audit(an_offer("840.00"), tariffs, flat_converter, TICKETING, trace=trail)
    step = trail.for_stage(Stage.AUDIT)[0]
    assert not step.failed
    assert step.details["ours"] == "840 EUR"


def test_a_supplier_low_finding_is_information_and_not_a_failure(flat_converter, tariffs):
    trail = Trace()
    audit(an_offer("700.00"), tariffs, flat_converter, TICKETING, trace=trail)
    assert not trail.stage_failed(Stage.AUDIT)


def test_a_supplier_high_finding_is_a_failure(flat_converter, tariffs):
    trail = Trace()
    audit(an_offer("900.00"), tariffs, flat_converter, TICKETING, trace=trail)
    assert trail.stage_failed(Stage.AUDIT)


def test_an_audit_reads_back_in_one_line(flat_converter, tariffs):
    assert (
        str(audit(an_offer("840.00"), tariffs, flat_converter, TICKETING))
        == "gds agrees at 840.00 EUR"
    )
    assert str(audit(an_offer("900.00"), tariffs, flat_converter, TICKETING)) == (
        "gds supplier_high by 60.00 EUR"
    )


def test_two_lines_that_agree_have_nothing_to_say(flat_converter, tariffs):
    priced = price(trip(), tariffs, flat_converter, TICKETING, "EUR")
    assert audit_line(priced.line, priced) == ()


def test_a_line_that_routes_differently_is_reported(flat_converter, tariffs):
    priced = price(trip(), tariffs, flat_converter, TICKETING, "EUR")
    theirs = "FRA LH BOS 420.00 LH FRA 420.00 NUC840.00END ROE1.000000"
    found = audit_line(theirs, priced)
    assert any("routes" in line for line in found)


def test_the_recovered_total_only_counts_claims(flat_converter, tariffs):
    audits = [
        audit(an_offer("900.00"), tariffs, flat_converter, TICKETING),
        audit(an_offer("700.00"), tariffs, flat_converter, TICKETING),
        audit(an_offer("840.00"), tariffs, flat_converter, TICKETING),
    ]
    assert recovered_total(audits, "EUR") == Money.of("60", "EUR")


def test_the_recovered_total_of_nothing_is_zero():
    assert recovered_total([], "EUR") == Money.zero("EUR")


def test_a_run_is_counted_by_finding(flat_converter, tariffs):
    audits = [
        audit(an_offer("900.00"), tariffs, flat_converter, TICKETING),
        audit(an_offer("910.00"), tariffs, flat_converter, TICKETING),
        audit(an_offer("840.00"), tariffs, flat_converter, TICKETING),
    ]
    assert by_finding(audits) == {"supplier_high": 2, "agrees": 1}


def test_the_split_the_supplier_used_is_the_one_we_reprice_against(
    flat_converter, transatlantic_miles
):
    fares = TariffStore()
    fares.add(fare("FRA", "ORD", "400.00"))
    fares.add(fare("ORD", "JFK", "50.00", carrier="UA", global_indicator=GlobalIndicator.DO))
    fares.add(fare("JFK", "FRA", "300.00"))
    loop = Itinerary(
        [
            segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
            segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )
    # The default split breaks somewhere the tariff has no fare for, and the
    # audit says so rather than quietly pricing a different journey.
    guessed = audit(
        an_offer("600.00", itinerary=loop),
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
    )
    assert guessed.finding is Finding.NOT_PRICEABLE

    told = audit(
        an_offer("600.00", itinerary=loop),
        Tariffs(fares=fares, miles=transatlantic_miles),
        flat_converter,
        TICKETING,
        breaks=(0, 1),
    )
    assert told.finding is not Finding.NOT_PRICEABLE


def test_the_priced_result_is_kept_for_the_letter(flat_converter, tariffs):
    result = audit(an_offer("900.00"), tariffs, flat_converter, TICKETING)
    assert result.priced is not None
    assert result.priced.total_nuc == Decimal("840.00")
    assert "NUC840.00END" in result.priced.line
