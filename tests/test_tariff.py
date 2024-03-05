from __future__ import annotations

from datetime import date

import pytest

from gussetline.errors import NoFareFound, ValidationError
from gussetline.flights import Cabin
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.tariff import Fare, FareType, OwRt, TariffStore


def fare(**over) -> Fare:
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


def test_a_fare_folds_its_points_to_cities():
    f = fare(origin="fra", destination="ewr")
    assert f.market == ("FRA", "NYC")


def test_a_fare_goes_between_two_places():
    with pytest.raises(ValidationError):
        fare(destination="FRA")


def test_a_published_fare_is_positive():
    with pytest.raises(ValidationError):
        fare(amount=Money.of("0", "EUR"))
    with pytest.raises(ValidationError):
        fare(amount=Money.of("-10", "EUR"))


def test_a_sale_window_that_closes_before_it_opens_is_refused():
    with pytest.raises(ValidationError):
        fare(effective=date(2025, 6, 1), discontinue=date(2025, 5, 1))


def test_a_travel_window_that_closes_before_it_opens_is_refused():
    with pytest.raises(ValidationError):
        fare(first_travel=date(2025, 6, 1), last_travel=date(2025, 5, 1))


def test_the_two_windows_are_read_against_two_different_dates():
    f = fare(
        effective=date(2025, 1, 1),
        discontinue=date(2025, 3, 31),
        first_travel=date(2025, 4, 1),
        last_travel=date(2025, 10, 31),
    )
    assert f.sellable_on(date(2025, 2, 14))
    assert not f.sellable_on(date(2025, 4, 14))
    assert f.flyable_on(date(2025, 6, 14))
    assert not f.flyable_on(date(2025, 2, 14))


def test_the_booking_class_is_the_first_letter_of_the_basis():
    assert fare(fare_basis="KLWAP7RT").booking_class == "K"
    assert fare(fare_basis="ylwap7rt").booking_class == "Y"


def test_the_parsed_basis_is_available_without_reparsing_at_the_call_site():
    assert fare(fare_basis="KLWAP7RT").parsed_basis.advance_days == 7


def test_a_round_trip_half_doubles_and_refuses_to_stand_alone():
    f = fare(owrt=OwRt.ROUND_TRIP_HALF, amount=Money.of("420.00", "EUR"))
    assert f.is_half_round_trip
    assert f.round_trip_amount() == Money.of("840.00", "EUR")
    with pytest.raises(ValidationError):
        f.one_way_amount()


def test_a_one_way_only_fare_stands_alone_and_refuses_to_double():
    f = fare(owrt=OwRt.ONE_WAY_ONLY, amount=Money.of("310.00", "EUR"))
    assert f.one_way_amount() == Money.of("310.00", "EUR")
    with pytest.raises(ValidationError):
        f.round_trip_amount()


def test_a_one_way_that_may_double_does_both():
    f = fare(owrt=OwRt.ONE_WAY_MAY_DOUBLE, amount=Money.of("310.00", "EUR"))
    assert f.one_way_amount() == Money.of("310.00", "EUR")
    assert f.round_trip_amount() == Money.of("620.00", "EUR")


def test_the_key_carries_the_indicator_because_the_fare_does():
    assert fare().key() == ("LH", "FRA", "NYC", "KLWAP7RT", "AT")


@pytest.fixture
def store() -> TariffStore:
    s = TariffStore()
    s.add_many(
        [
            fare(fare_basis="KLWAP7RT", amount=Money.of("420.00", "EUR")),
            fare(fare_basis="QLWAP14RT", amount=Money.of("380.00", "EUR")),
            fare(fare_basis="YRT", amount=Money.of("1980.00", "EUR"), owrt=OwRt.ROUND_TRIP_HALF),
            fare(
                fare_basis="JRT",
                amount=Money.of("2900.00", "EUR"),
                cabin=Cabin.BUSINESS,
            ),
            fare(
                carrier="LH",
                origin="JFK",
                destination="FRA",
                fare_basis="KLWAP7RT",
                amount=Money.of("510.00", "USD"),
            ),
        ]
    )
    return s


def test_the_store_counts_what_went_in(store):
    assert len(store) == 5
    assert ("LH", "FRA", "NYC") in store.markets()


def test_a_search_is_directional(store):
    out = store.search("LH", "FRA", "JFK")
    back = store.search("LH", "JFK", "FRA")
    assert len(out) == 4
    assert len(back) == 1
    assert back[0].amount.currency == "USD"


def test_a_search_comes_back_cheapest_first(store):
    found = store.search("LH", "FRA", "JFK")
    assert [str(f.amount.amount) for f in found] == ["380.00", "420.00", "1980.00", "2900.00"]


def test_a_search_filters_on_cabin(store):
    assert len(store.search("LH", "FRA", "JFK", cabin=Cabin.BUSINESS)) == 1
    assert len(store.search("LH", "FRA", "JFK", cabin=Cabin.ECONOMY)) == 3


def test_a_search_filters_on_booking_class(store):
    assert len(store.search("LH", "FRA", "JFK", booking_class="q")) == 1
    assert store.search("LH", "FRA", "JFK", booking_class="Z") == []


def test_a_search_filters_on_the_indicator_and_a_broader_filing_still_answers():
    s = TariffStore()
    s.add(fare(global_indicator=GlobalIndicator.AT))
    assert len(s.search("LH", "FRA", "JFK", indicator=GlobalIndicator.AT)) == 1
    assert len(s.search("LH", "FRA", "JFK", indicator=GlobalIndicator.SA)) == 1
    assert len(s.search("LH", "FRA", "JFK", indicator=GlobalIndicator.PA)) == 0


def test_a_search_on_a_market_we_hold_nothing_for_is_empty_not_an_error(store):
    assert store.search("LH", "FRA", "BKK") == []


def test_cheapest_raises_when_nothing_survives_the_filters(store):
    with pytest.raises(NoFareFound) as caught:
        store.cheapest("LH", "FRA", "BKK")
    assert caught.value.details["destination"] == "BKK"


def test_cheapest_respects_the_filters(store):
    assert store.cheapest("LH", "FRA", "JFK").fare_basis == "QLWAP14RT"
    assert store.cheapest("LH", "FRA", "JFK", cabin=Cabin.BUSINESS).fare_basis == "JRT"


def test_lookup_by_basis_finds_the_exact_filing(store):
    assert store.by_basis("LH", "FRA", "JFK", "yrt").amount == Money.of("1980.00", "EUR")


def test_lookup_by_basis_raises_when_the_basis_is_not_filed(store):
    with pytest.raises(NoFareFound):
        store.by_basis("LH", "FRA", "JFK", "ZZZ")


def test_private_and_published_fares_sit_in_the_same_store(store):
    store.add(fare(fare_basis="KLWCORP", fare_type=FareType.CORPORATE))
    assert len(store.search("LH", "FRA", "JFK")) == 5
