from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from gussetline.addon import (
    AddOn,
    AddOnStore,
    End,
    cheapest_for,
    construct,
    one_way_only,
    prefer_published,
)
from gussetline.errors import ConstructionError, ValidationError
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.tariff import Fare, FareType, OwRt


def gateway(
    origin="FRA",
    destination="JFK",
    amount="420.00",
    basis="KLWAP7RT",
    carrier="LH",
    currency="EUR",
    fare_type=FareType.PUBLISHED,
    owrt=OwRt.ROUND_TRIP_HALF,
) -> Fare:
    return Fare(
        carrier=carrier,
        origin=origin,
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, currency),
        owrt=owrt,
        global_indicator=GlobalIndicator.AT,
        rule_number="0100",
        routing_number="0011",
        tariff_number="1",
        footnote="12",
        fare_type=fare_type,
        effective=date(2025, 1, 1),
        discontinue=date(2025, 12, 31),
    )


def arbitrary(
    point="HAM",
    gateway_point="FRA",
    amount="40.00",
    end=End.ORIGIN,
    carrier="LH",
    currency="EUR",
) -> AddOn:
    return AddOn(
        carrier=carrier,
        gateway=gateway_point,
        point=point,
        amount=Money.of(amount, currency),
        end=end,
    )


def test_an_arbitrary_reaches_a_point_from_a_gateway():
    made = arbitrary()
    assert made.point == "HAM"
    assert made.gateway == "FRA"
    assert made.market == ("HAM", "FRA")


def test_an_arbitrary_at_the_far_end_reads_the_other_way_round():
    made = arbitrary(point="BOS", gateway_point="JFK", end=End.DESTINATION)
    assert made.market == ("NYC", "BOS")


def test_an_arbitrary_reads_back_in_one_line():
    assert str(arbitrary()) == "LH HAM/FRA 40.00 EUR at the origin"


def test_an_arbitrary_that_goes_nowhere_is_refused():
    with pytest.raises(ValidationError, match="somewhere else"):
        arbitrary(point="FRA", gateway_point="FRA")


def test_a_negative_arbitrary_is_refused():
    with pytest.raises(ValidationError, match="not negative"):
        arbitrary(amount="-10.00")


def test_a_construction_at_the_origin_end_reaches_the_interior_point():
    made = construct(gateway(), origin_addon=arbitrary())
    assert made.fare.origin == "HAM"
    assert made.fare.destination == "NYC"
    assert made.fare.amount == Money.of("460.00", "EUR")


def test_a_construction_at_the_far_end_reaches_the_other_way():
    made = construct(
        gateway(),
        destination_addon=arbitrary(point="BOS", gateway_point="JFK", end=End.DESTINATION),
    )
    assert made.fare.origin == "FRA"
    assert made.fare.destination == "BOS"


def test_a_construction_at_both_ends_adds_both():
    made = construct(
        gateway(),
        origin_addon=arbitrary(amount="40.00"),
        destination_addon=arbitrary(
            point="BOS", gateway_point="JFK", amount="30.00", end=End.DESTINATION
        ),
    )
    assert made.fare.amount == Money.of("490.00", "EUR")
    assert made.arbitrary_total == Decimal("70.00")
    assert made.ends == (End.ORIGIN, End.DESTINATION)


def test_a_construction_with_no_arbitrary_at_all_is_refused():
    with pytest.raises(ConstructionError, match="needs an arbitrary"):
        construct(gateway())


def test_the_rule_and_the_footnote_come_off_the_gateway_fare():
    # An add-on is an amount and carries no conditions. Reading its blank
    # rule number leaves the constructed fare with no conditions at all.
    made = construct(gateway(), origin_addon=arbitrary())
    assert made.fare.rule_number == "0100"
    assert made.fare.footnote == "12"
    assert made.fare.tariff_number == "1"


def test_the_indicator_comes_off_the_gateway_and_is_not_worked_out_again():
    # Nuremberg to New York over the Atlantic is still AT. Re-deriving it
    # from a domestic first sector files it as EH.
    made = construct(gateway(), origin_addon=arbitrary())
    assert made.fare.global_indicator is GlobalIndicator.AT


def test_the_sale_and_travel_windows_come_off_the_gateway_fare():
    made = construct(gateway(), origin_addon=arbitrary())
    assert made.fare.effective == date(2025, 1, 1)
    assert made.fare.discontinue == date(2025, 12, 31)


def test_an_arbitrary_filed_for_the_other_end_is_refused():
    with pytest.raises(ConstructionError, match="the other end"):
        construct(gateway(), origin_addon=arbitrary(end=End.DESTINATION))


def test_an_arbitrary_belonging_to_another_carrier_is_refused():
    with pytest.raises(ConstructionError, match="its own carrier"):
        construct(gateway(), origin_addon=arbitrary(carrier="UA"))


def test_an_arbitrary_that_does_not_meet_the_fare_is_refused():
    with pytest.raises(ConstructionError, match="does not meet"):
        construct(gateway(), origin_addon=arbitrary(gateway_point="MUC"))


def test_an_arbitrary_in_another_currency_is_refused():
    with pytest.raises(ConstructionError, match="own currency"):
        construct(gateway(), origin_addon=arbitrary(currency="GBP"))


def test_a_negotiated_fare_is_not_a_gateway_fare():
    with pytest.raises(ConstructionError, match="not a gateway fare"):
        construct(gateway(fare_type=FareType.NEGOTIATED), origin_addon=arbitrary())


def test_a_construction_reads_back_with_what_it_was_built_from():
    made = construct(gateway(), origin_addon=arbitrary())
    assert "built on" in str(made)
    assert "40.00" in str(made)


def test_a_store_hands_back_the_arbitraries_for_a_point_cheapest_first():
    store = AddOnStore()
    store.add_many([arbitrary(amount="60.00"), arbitrary(amount="40.00")])
    found = store.for_point("LH", "HAM", "FRA", End.ORIGIN)
    assert [a.amount.amount for a in found] == [Decimal("40.00"), Decimal("60.00")]


def test_a_store_keeps_the_two_ends_apart():
    store = AddOnStore()
    store.add(arbitrary(end=End.ORIGIN))
    assert store.for_point("LH", "HAM", "FRA", End.DESTINATION) == []
    assert len(store) == 1


def test_a_store_lists_the_gateways_a_point_can_be_reached_from():
    store = AddOnStore()
    store.add(arbitrary(gateway_point="FRA"))
    store.add(arbitrary(gateway_point="MUC"))
    assert store.gateways("LH", "HAM") == ["FRA", "MUC"]


def test_a_store_folds_airports_to_cities():
    store = AddOnStore()
    store.add(arbitrary(point="BOS", gateway_point="JFK", end=End.DESTINATION))
    assert store.for_point("LH", "BOS", "EWR", End.DESTINATION)


def test_the_cheapest_construction_for_a_market_is_found():
    store = AddOnStore()
    store.add_many([arbitrary(amount="60.00"), arbitrary(amount="40.00")])
    best = cheapest_for("LH", "HAM", "JFK", [gateway()], store)
    assert best is not None
    assert best.fare.amount == Money.of("460.00", "EUR")


def test_the_cheapest_gateway_fare_wins_too():
    store = AddOnStore()
    store.add(arbitrary(amount="40.00"))
    best = cheapest_for(
        "LH", "HAM", "JFK", [gateway(amount="420.00"), gateway(amount="380.00")], store
    )
    assert best is not None
    assert best.fare.amount == Money.of("420.00", "EUR")


def test_a_market_with_no_arbitrary_has_no_construction():
    assert cheapest_for("LH", "HAM", "JFK", [gateway()], AddOnStore()) is None


def test_a_market_with_no_gateway_fare_has_no_construction():
    store = AddOnStore()
    store.add(arbitrary())
    assert cheapest_for("LH", "HAM", "JFK", [], store) is None


def test_one_arbitrary_at_one_end_is_enough():
    store = AddOnStore()
    store.add(arbitrary())
    best = cheapest_for("LH", "HAM", "JFK", [gateway()], store)
    assert best is not None
    assert best.destination_addon is None


def test_a_published_fare_beats_a_construction_on_a_tie():
    # If a carrier has bothered to file the market, that fare is what it
    # sells, whatever an arbitrary adds up to.
    built = construct(gateway(), origin_addon=arbitrary())
    filed = gateway(origin="HAM", amount="460.00", basis="KHAM")
    chosen = prefer_published([filed], built)
    assert chosen is not None
    assert chosen.fare_basis == "KHAM"


def test_a_published_fare_beats_a_construction_a_penny_dearer():
    built = construct(gateway(), origin_addon=arbitrary(amount="40.00"))
    filed = gateway(origin="HAM", amount="459.99", basis="KHAM")
    chosen = prefer_published([filed], built)
    assert chosen is not None
    assert chosen.fare_basis == "KHAM"


def test_a_cheaper_construction_wins_when_it_is_genuinely_cheaper():
    built = construct(gateway(amount="300.00"), origin_addon=arbitrary(amount="40.00"))
    filed = gateway(origin="HAM", amount="460.00", basis="KHAM")
    chosen = prefer_published([filed], built)
    assert chosen is not None
    assert chosen.amount == Money.of("340.00", "EUR")


def test_with_nothing_filed_the_construction_is_what_there_is():
    built = construct(gateway(), origin_addon=arbitrary())
    chosen = prefer_published([], built)
    assert chosen is not None
    assert chosen.origin == "HAM"


def test_with_nothing_at_all_there_is_nothing():
    assert prefer_published([], None) is None


def test_a_one_way_construction_says_it_may_not_be_doubled():
    built = construct(gateway(owrt=OwRt.ONE_WAY_ONLY), origin_addon=arbitrary())
    assert one_way_only(built.fare)
    assert not one_way_only(construct(gateway(), origin_addon=arbitrary()).fare)
