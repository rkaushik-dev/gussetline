from __future__ import annotations

from decimal import Decimal

import pytest

from conftest import segment, surface
from gussetline.components import Charge, FareComponent, PricingUnit, split_at
from gussetline.errors import ConstructionError, ValidationError
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import JourneyType
from gussetline.money import Money
from gussetline.tariff import Fare, OwRt


def out_sector():
    return segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")


def home_sector():
    return segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")


def feeder():
    return segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40")


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


def test_a_component_needs_a_sector():
    with pytest.raises(ValidationError):
        FareComponent(())


def test_a_component_has_to_join_up():
    with pytest.raises(ValidationError):
        FareComponent((out_sector(), out_sector()))


def test_board_and_off_points_are_cities():
    c = FareComponent((out_sector(),))
    assert c.board_point == "FRA"
    assert c.off_point == "NYC"


def test_via_points_are_the_interior_only():
    c = FareComponent(
        (
            feeder(),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        )
    )
    assert c.via_points == ("LHR",)
    assert c.origin == "FRA"
    assert c.destination == "JFK"


def test_the_indicator_comes_off_the_component_and_not_the_journey():
    c = FareComponent((out_sector(),))
    assert c.indicator is GlobalIndicator.AT


def test_the_governing_carrier_is_the_first_international_sector():
    c = FareComponent(
        (
            segment("LH", "10", "FRA", "MUC", "2025-03-04 06:00", "2025-03-04 07:00"),
            segment("LX", "400", "MUC", "JFK", "2025-03-04 09:00", "2025-03-04 12:30"),
        )
    )
    assert c.carriers == ("LH", "LX")
    assert c.governing_carrier == "LX"


def test_a_wholly_domestic_component_takes_its_first_carrier():
    c = FareComponent(
        (
            segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
            segment("AA", "200", "ORD", "LAX", "2025-03-04 11:00", "2025-03-04 13:30"),
        )
    )
    assert c.governing_carrier == "UA"


def test_a_component_of_only_surface_has_no_governing_carrier():
    c = FareComponent((surface("JFK", "BOS"),))
    with pytest.raises(ConstructionError):
        c.governing_carrier


def test_a_component_knows_when_it_carries_surface():
    assert not FareComponent((out_sector(),)).has_surface
    assert FareComponent((out_sector(), surface("JFK", "BOS"))).has_surface


def test_an_unpriced_component_refuses_to_total():
    with pytest.raises(ConstructionError):
        FareComponent((out_sector(),)).total_nuc


def test_pricing_a_component_returns_a_new_one():
    plain = FareComponent((out_sector(),))
    priced = plain.priced_with(a_fare(), Decimal("441.64"))
    assert plain.nuc is None
    assert priced.nuc == Decimal("441.64")
    assert priced.total_nuc == Decimal("441.64")
    assert priced.fare is not None


def test_a_surcharge_adds_to_the_total_and_leaves_a_note():
    priced = FareComponent((out_sector(),)).priced_with(a_fare(), Decimal("441.64"))
    surcharged = priced.with_surcharge(Decimal("22.08"), "5M")
    assert surcharged.total_nuc == Decimal("463.72")
    assert surcharged.notes == ("5M",)
    assert priced.total_nuc == Decimal("441.64")


def test_surcharges_accumulate_and_keep_both_notes():
    c = (
        FareComponent((out_sector(),))
        .priced_with(a_fare(), Decimal("400.00"))
        .with_surcharge(Decimal("20.00"), "5M")
        .with_surcharge(Decimal("10.00"), "Q")
    )
    assert c.total_nuc == Decimal("430.00")
    assert c.notes == ("5M", "Q")


def test_string_form_says_unpriced_until_it_is_not():
    assert str(FareComponent((out_sector(),))) == "FRANYC unpriced"
    priced = FareComponent((out_sector(),)).priced_with(a_fare(), Decimal("441.64"))
    assert str(priced) == "FRANYC KLWAP7RT"


def priced(elements, amount="441.64"):
    return FareComponent(tuple(elements)).priced_with(a_fare(), Decimal(amount))


def test_a_round_trip_unit_is_exactly_two_components():
    unit = PricingUnit((priced([out_sector()]), priced([home_sector()])), JourneyType.ROUND_TRIP)
    assert len(unit) == 2
    assert unit.turnaround == "JFK"
    assert unit.total_nuc == Decimal("883.28")


def test_a_round_trip_unit_refuses_a_third_component():
    with pytest.raises(ValidationError):
        PricingUnit(
            (priced([out_sector()]), priced([home_sector()]), priced([out_sector()])),
            JourneyType.ROUND_TRIP,
        )


def test_a_one_way_unit_is_exactly_one_component_and_does_not_turn_round():
    unit = PricingUnit((priced([out_sector()]),), JourneyType.ONE_WAY)
    assert unit.origin == "FRA"
    assert unit.destination == "JFK"
    with pytest.raises(ConstructionError):
        unit.turnaround


def test_a_one_way_unit_refuses_a_second_component():
    with pytest.raises(ValidationError):
        PricingUnit((priced([out_sector()]), priced([home_sector()])), JourneyType.ONE_WAY)


def test_a_circle_trip_unit_needs_three():
    with pytest.raises(ValidationError):
        PricingUnit((priced([out_sector()]), priced([home_sector()])), JourneyType.CIRCLE_TRIP)


def test_an_empty_unit_is_refused():
    with pytest.raises(ValidationError):
        PricingUnit((), JourneyType.ONE_WAY)


def test_replacing_a_component_leaves_the_original_alone():
    unit = PricingUnit((priced([out_sector()]), priced([home_sector()])), JourneyType.ROUND_TRIP)
    dearer = unit.components[0].with_surcharge(Decimal("50.00"), "HIP")
    updated = unit.replace(0, dearer)
    assert updated.total_nuc == Decimal("933.28")
    assert unit.total_nuc == Decimal("883.28")


def test_is_priced_reads_every_component():
    half = PricingUnit(
        (priced([out_sector()]), FareComponent((home_sector(),))), JourneyType.ROUND_TRIP
    )
    assert not half.is_priced


def test_split_cuts_at_the_break_points():
    elements = [
        feeder(),
        segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
        segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35"),
    ]
    parts = split_at(elements, [1])
    assert len(parts) == 2
    assert parts[0].origin == "FRA" and parts[0].destination == "JFK"
    assert parts[1].origin == "JFK" and parts[1].destination == "FRA"


def test_split_with_no_break_gives_one_component():
    parts = split_at([out_sector()], [])
    assert len(parts) == 1


def test_break_points_have_to_climb():
    elements = [
        feeder(),
        segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
    ]
    with pytest.raises(ValidationError):
        split_at(elements, [1, 0])


def test_a_break_past_the_end_is_refused():
    with pytest.raises(ValidationError):
        split_at([out_sector(), home_sector()], [1])


def test_splitting_nothing_is_refused():
    with pytest.raises(ValidationError):
        split_at([], [])


def test_a_charge_reads_back_as_its_reason_and_amount():
    assert str(Charge("5M", Decimal("22.08"))) == "5M 22.08"


def test_charges_are_kept_apart_and_the_total_still_adds_up():
    c = (
        FareComponent((out_sector(),))
        .priced_with(a_fare(), Decimal("400.00"))
        .with_surcharge(Decimal("20.00"), "5M")
        .with_surcharge(Decimal("30.00"), "Q")
    )
    assert c.charges == (Charge("5M", Decimal("20.00")), Charge("Q", Decimal("30.00")))
    assert c.surcharges == Decimal("50.00")
    assert c.total_nuc == Decimal("450.00")


def test_one_reason_can_be_read_back_on_its_own():
    c = (
        FareComponent((out_sector(),))
        .priced_with(a_fare(), Decimal("400.00"))
        .with_surcharge(Decimal("20.00"), "5M")
        .with_surcharge(Decimal("30.00"), "Q")
    )
    assert c.charge_for("Q") == Decimal("30.00")
    assert c.charge_for("5M") == Decimal("20.00")
    assert c.charge_for("HIP") == Decimal(0)


def test_the_same_reason_twice_adds_together():
    c = (
        FareComponent((out_sector(),))
        .priced_with(a_fare(), Decimal("400.00"))
        .with_surcharge(Decimal("20.00"), "Q")
        .with_surcharge(Decimal("12.00"), "Q")
    )
    assert c.charge_for("Q") == Decimal("32.00")
    assert c.notes == ("Q", "Q")


def test_pricing_a_component_keeps_charges_already_on_it():
    plain = FareComponent((out_sector(),)).with_surcharge(Decimal("20.00"), "5M")
    priced = plain.priced_with(a_fare(), Decimal("400.00"))
    assert priced.charge_for("5M") == Decimal("20.00")
    assert priced.total_nuc == Decimal("420.00")


def test_a_component_with_no_charges_has_none_of_either():
    plain = FareComponent((out_sector(),))
    assert plain.charges == ()
    assert plain.notes == ()
    assert plain.surcharges == Decimal(0)
