from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.distance import MileageTable
from gussetline.errors import NoFareFound
from gussetline.exchange import Converter, RateTable
from gussetline.flights import Cabin
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.routing import RoutingMap, RoutingStore
from gussetline.selection import apply_selection, select
from gussetline.tariff import Fare, OwRt, TariffStore
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


@pytest.fixture
def converter():
    table = RateTable("roe")
    table.add("EUR", date(2025, 1, 1), "1.000")
    return Converter(table)


@pytest.fixture
def miles():
    table = MileageTable()
    table.add("FRA", "NYC", 4000)
    table.add("FRA", "LON", 500)
    table.add("LON", "NYC", 4400)
    table.add("FRA", "DXB", 3000)
    table.add("DXB", "NYC", 6800)
    return table


def a_fare(basis="KLWAP7RT", amount="420.00", **over) -> Fare:
    base = {
        "carrier": "LH",
        "origin": "FRA",
        "destination": "JFK",
        "fare_basis": basis,
        "amount": Money.of(amount, "EUR"),
        "owrt": OwRt.ROUND_TRIP_HALF,
        "global_indicator": GlobalIndicator.AT,
        "rule_number": "1100",
        "routing_number": "0000",
    }
    base.update(over)
    return Fare(**base)


def direct(booking_class="K"):
    return FareComponent(
        (
            segment(
                "LH",
                "400",
                "FRA",
                "JFK",
                "2025-03-04 10:25",
                "2025-03-04 13:05",
                booking_class=booking_class,
            ),
        )
    )


def via_london(booking_class="K"):
    return FareComponent(
        (
            segment(
                "LH",
                "992",
                "FRA",
                "LHR",
                "2025-03-04 07:00",
                "2025-03-04 07:40",
                booking_class=booking_class,
            ),
            segment(
                "LH",
                "400",
                "LHR",
                "JFK",
                "2025-03-04 11:05",
                "2025-03-04 14:00",
                booking_class=booking_class,
            ),
        )
    )


def via_dubai():
    return FareComponent(
        (
            segment("LH", "630", "FRA", "DXB", "2025-03-04 10:00", "2025-03-04 18:30"),
            segment("EK", "201", "DXB", "JFK", "2025-03-06 08:00", "2025-03-06 14:20"),
        )
    )


def test_a_component_with_no_flown_sector_cannot_be_priced(converter, miles):
    from conftest import surface

    with pytest.raises(NoFareFound):
        select(
            FareComponent((surface("JFK", "BOS"),)),
            TariffStore(),
            RoutingStore(),
            miles,
            converter,
            TICKETING,
        )


def test_nothing_published_is_a_refusal_that_names_the_market(converter, miles):
    with pytest.raises(NoFareFound) as caught:
        select(direct(), TariffStore(), RoutingStore(), miles, converter, TICKETING)
    assert caught.value.details["destination"] == "NYC"


def test_the_cheapest_surviving_fare_wins(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("QLWAP14RT", "380.00"))
    trace = Trace()
    chosen = select(direct("Q"), store, RoutingStore(), miles, converter, TICKETING, trace=trace)
    assert chosen.chosen.fare.fare_basis == "QLWAP14RT"
    assert len(trace.for_stage(Stage.SELECTION)) == 1


def test_a_fare_whose_class_is_not_booked_is_refused(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("QLWAP14RT", "380.00"))
    chosen = select(direct("K"), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.chosen.fare.fare_basis == "KLWAP7RT"
    assert chosen.reason_for("QLWAP14RT") == "no sector booked in Q"


def test_a_fare_on_the_wrong_indicator_is_refused(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00", global_indicator=GlobalIndicator.PA))
    with pytest.raises(NoFareFound):
        select(direct(), store, RoutingStore(), miles, converter, TICKETING)


def test_a_broader_indicator_still_prices_the_component(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00", global_indicator=GlobalIndicator.AT))
    chosen = select(direct(), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.chosen.fare.fare_basis == "KLWAP7RT"


def test_a_routing_that_refuses_the_path_drops_the_fare(converter, miles):
    routings = RoutingStore()
    routed = RoutingMap("0101")
    routed.add("FRA", "NYC", ["LH"])
    routings.add("LH", routed)

    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00", routing_number="0101"))
    store.add(a_fare("KHXRT", "980.00", routing_number="0000"))

    chosen = select(via_london(), store, routings, miles, converter, TICKETING)
    assert chosen.chosen.fare.fare_basis == "KHXRT"
    assert chosen.reason_for("KLWAP7RT") == "routing 0101 refuses the path"


def test_a_fare_over_the_mileage_ladder_is_refused(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    with pytest.raises(NoFareFound) as caught:
        select(via_dubai(), store, RoutingStore(), miles, converter, TICKETING)
    assert caught.value.details["refused"] == 1


def test_every_refusal_is_recorded_when_nothing_survives(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00", global_indicator=GlobalIndicator.PA))
    trace = Trace()
    with pytest.raises(NoFareFound):
        select(direct(), store, RoutingStore(), miles, converter, TICKETING, trace=trace)
    assert trace.stage_failed(Stage.SELECTION)
    assert "filed PA, journey is AT" in trace.reasons()[0]


def test_a_fare_we_did_not_refuse_has_no_reason(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    chosen = select(direct(), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.reason_for("KLWAP7RT") is None


def test_the_total_carries_the_mileage_band_and_the_base_does_not(converter, miles):
    # The routing through London puts the component into the five percent
    # band. The number that leaves here has to be the surcharged one, because
    # it is compared against what the supplier quoted.
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("KHXRT", "410.00"))
    chosen = select(via_london(), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.chosen.band == "5M"
    assert chosen.chosen.base_nuc == Decimal("410.00")
    assert chosen.chosen.surcharge == Decimal("20.50")
    assert chosen.chosen.total_nuc == Decimal("430.50")


def test_the_band_is_the_same_for_every_candidate_on_one_component(converter, miles):
    # The surcharge is a percentage of the fare, so it scales the survivors
    # together and cannot put a dearer fare in front of a cheaper one.
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("KHXRT", "410.00"))
    chosen = select(via_london(), store, RoutingStore(), miles, converter, TICKETING)
    assert {c.band for c in chosen.considered} == {"5M"}
    assert chosen.chosen.base_nuc < Decimal("420.00")


def test_the_runner_up_is_the_next_cheapest(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("QLWAP14RT", "380.00"))
    chosen = select(direct("Q"), store, RoutingStore(), miles, converter, TICKETING)
    # Only the Q class fare is bookable, so there is no runner up.
    assert chosen.runner_up is None


def test_with_two_survivors_there_is_a_runner_up(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    store.add(a_fare("KHWAP3RT", "460.00"))
    chosen = select(direct("K"), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.chosen.fare.fare_basis == "KLWAP7RT"
    assert chosen.runner_up is not None
    assert chosen.runner_up.fare.fare_basis == "KHWAP3RT"


def test_a_candidate_reads_back_with_its_band(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    chosen = select(direct(), store, RoutingStore(), miles, converter, TICKETING)
    assert str(chosen.chosen) == "KLWAP7RT 420.00 NUC M"


def test_applying_the_selection_prices_the_component(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    chosen = select(direct(), store, RoutingStore(), miles, converter, TICKETING)
    priced = apply_selection(direct(), chosen)
    assert priced.nuc == Decimal("420.00")
    assert priced.total_nuc == Decimal("420.00")
    assert priced.notes == ()


def test_applying_a_surcharged_selection_carries_the_band(converter, miles):
    store = TariffStore()
    store.add(a_fare("KLWAP7RT", "420.00"))
    chosen = select(via_london(), store, RoutingStore(), miles, converter, TICKETING)
    priced = apply_selection(via_london(), chosen)
    assert priced.notes == ("5M",)
    assert priced.total_nuc == Decimal("441.00")


def test_a_business_fare_is_not_dropped_for_being_dearer(converter, miles):
    store = TariffStore()
    store.add(a_fare("JRT", "2900.00", cabin=Cabin.BUSINESS))
    chosen = select(direct("J"), store, RoutingStore(), miles, converter, TICKETING)
    assert chosen.chosen.fare.cabin is Cabin.BUSINESS
