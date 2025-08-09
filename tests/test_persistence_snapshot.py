from __future__ import annotations

from datetime import date, datetime

import pytest

from conftest import segment
from gussetline.combinability import Combinability
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.persistence import snapshot
from gussetline.persistence.unitofwork import in_memory
from gussetline.rules.base import Category, RuleSet
from gussetline.rules.stay import MinimumStay
from gussetline.tariff import Fare, FareType, OwRt

ON = date(2025, 8, 1)


@pytest.fixture
def database():
    return in_memory()


def fare(carrier="LH", origin="FRA", destination="JFK", basis="KLWAP7RT", amount="420.00") -> Fare:
    return Fare(
        carrier=carrier,
        origin=origin,
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, "EUR"),
        owrt=OwRt.ROUND_TRIP_HALF,
        global_indicator=GlobalIndicator.AT,
        rule_number="0100",
        routing_number="0011",
        tariff_number="1",
        fare_type=FareType.PUBLISHED,
        effective=date(2025, 1, 1),
        discontinue=date(2025, 12, 31),
        first_travel=date(2025, 1, 1),
        last_travel=date(2025, 12, 31),
    )


def stock(work, *fares: Fare) -> None:
    row = work.tariff.open_load(
        carrier=fares[0].carrier if fares else "LH",
        filename="test.dat",
        digest="d" * 32,
        loaded_at=datetime(2025, 3, 1, 6, 0),
        effective=date(2025, 3, 1),
    )
    work.tariff.add_fares(row, list(fares))
    for filed in fares:
        work.combinability.save(
            Combinability(carrier=filed.carrier, fare_basis=filed.fare_basis, round_trip=True)
        )
    work.rules.save(
        RuleSet(carrier="LH", number="0100", tariff="1", provisions=(MinimumStay(days=3),))
    )


def an_itinerary() -> Itinerary:
    return Itinerary((segment("LH", "400", "FRA", "JFK", "2025-08-04 10:25", "2025-08-04 13:05"),))


def an_offer(destination="JFK") -> Offer:
    return Offer(
        source="gds",
        itinerary=Itinerary(
            (segment("LH", "400", "FRA", destination, "2025-08-04 10:25", "2025-08-04 13:05"),)
        ),
        breakdown=PriceBreakdown(base=Money.of("620.00", "EUR")),
        fare_bases=("KLWAP7RT",),
        observed_at=datetime(2025, 8, 1, 6, 0),
        validating_carrier="LH",
    )


def test_a_snapshot_of_an_empty_desk_holds_nothing_but_the_mileages(database):
    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], ON)
    assert len(taken.fares) == 0
    assert len(taken.combinability) == 0
    # Not from the database. An empty mileage table is a check that fails on
    # a city pair it has never heard of, which is a different failure.
    assert taken.miles.size() > 0


def test_a_snapshot_finds_a_fare_by_the_city_the_tariff_files_it_under(database):
    # The bug this replaced. One command folded its pairs and the other did
    # not, so a fare found by one was invisible to the other.
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], ON)
    assert len(taken.fares) == 1


def test_a_snapshot_asked_for_an_airport_finds_the_city_fare(database):
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "EWR"], ON)
    assert len(taken.fares) == 1


def test_a_snapshot_carries_the_combinability_and_the_rules(database):
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], ON)
    assert taken.combinability.get("LH", "KLWAP7RT") is not None
    found = taken.rules.find("LH", "0100")
    assert found is not None
    assert found.has(Category.MINIMUM_STAY)


def test_a_snapshot_leaves_out_a_carrier_nobody_asked_for(database):
    with database.unit() as work:
        stock(work, fare(), fare(carrier="UA", basis="KUA"))
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], ON)
    assert taken.combinability.get("UA", "KUA") is None


def test_a_snapshot_leaves_out_a_market_nobody_asked_for(database):
    with database.unit() as work:
        stock(work, fare(), fare(destination="ORD", basis="KORD"))
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], ON)
    assert len(taken.fares) == 1


def test_a_snapshot_applies_the_sale_window_in_the_query(database):
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, ["LH"], ["FRA", "JFK"], date(2026, 6, 1))
    assert len(taken.fares) == 0


def test_no_carriers_at_all_reads_nothing(database):
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.take(work, [], ["FRA", "JFK"], ON)
    assert len(taken.fares) == 0


def test_an_itinerary_works_out_its_own_carriers(database):
    with database.unit() as work:
        stock(work, fare())
        work.commit()

    with database.unit() as work:
        taken = snapshot.for_itinerary(work, an_itinerary(), ON)
    assert len(taken.fares) == 1


def test_naming_a_carrier_narrows_it(database):
    with database.unit() as work:
        stock(work, fare(), fare(carrier="UA", basis="KUA"))
        work.commit()

    with database.unit() as work:
        taken = snapshot.for_itinerary(work, an_itinerary(), ON, carrier="UA")
    assert taken.combinability.get("UA", "KUA") is not None
    assert taken.combinability.get("LH", "KLWAP7RT") is None


def test_a_run_of_offers_reads_one_snapshot_for_all_of_them(database):
    with database.unit() as work:
        stock(work, fare(), fare(destination="ORD", basis="KORD"))
        work.commit()

    with database.unit() as work:
        taken = snapshot.for_offers(work, [an_offer("JFK"), an_offer("ORD")], ON)
    assert len(taken.fares) == 2


def test_a_run_of_no_offers_reads_nothing(database):
    with database.unit() as work:
        taken = snapshot.for_offers(work, [], ON)
    assert len(taken.fares) == 0
