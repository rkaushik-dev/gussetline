from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from conftest import fare, segment
from gussetline.carriers import Alliance, carrier
from gussetline.flights import Cabin
from gussetline.geography import airport
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Baggage, Offer, PriceBreakdown
from gussetline.persistence.base import Base
from gussetline.persistence.mappers import (
    airport_to_row,
    carrier_to_row,
    fare_to_row,
    offer_to_row,
    row_to_airport,
    row_to_carrier,
    row_to_fare,
    row_to_offer,
    row_to_segment,
    segment_to_row,
    series_key_of,
    tax_to_row,
)
from gussetline.persistence.models import LoadRow, ObservationRow
from gussetline.taxes import AppliedTax

SEEN = datetime(2025, 6, 12, 9, 0)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def an_offer(**over) -> Offer:
    fields = {
        "source": "gds",
        "itinerary": Itinerary(
            [
                segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-07-18 18:30", "2025-07-19 08:05"),
            ]
        ),
        "breakdown": PriceBreakdown(
            Money.of("840.00", "EUR"),
            (
                AppliedTax("DE", Money.of("70.83", "EUR"), "FRA"),
                AppliedTax("YQ", Money.of("180.00", "EUR"), "FRA", carrier_imposed=True),
            ),
            Money.of("1090.83", "EUR"),
        ),
        "fare_bases": ("KLWAP7RT", "KLWAP7RT"),
        "observed_at": SEEN,
        "seats": 4,
        "baggage": Baggage(pieces=1),
        "refundable": False,
        "validating_carrier": "LH",
    }
    fields.update(over)
    return Offer(**fields)


def test_an_airport_round_trips():
    assert row_to_airport(airport_to_row(airport("FRA"))) == airport("FRA")


def test_a_carrier_round_trips():
    assert row_to_carrier(carrier_to_row(carrier("LH"))) == carrier("LH")


def test_an_unaligned_carrier_keeps_its_alliance():
    row = carrier_to_row(carrier("EK"))
    assert row.alliance == "none"
    assert row_to_carrier(row).alliance is Alliance.NONE


def test_a_low_cost_carrier_keeps_its_flag():
    assert row_to_carrier(carrier_to_row(carrier("FR"))).low_cost


def test_a_fare_round_trips(session):
    original = fare(amount="420.00")
    load = LoadRow(
        carrier="LH",
        filename="x.dat",
        digest="a" * 32,
        loaded_at=SEEN,
        effective_from=date(2025, 1, 1),
    )
    load.fares.append(fare_to_row(original, load_id=0))
    session.add(load)
    session.commit()
    from gussetline.persistence.models import FareRow

    back = row_to_fare(session.scalars(select(FareRow)).one())
    assert back == original


def test_a_fare_amount_survives_the_round_trip_exactly(session):
    original = fare(amount="12.999", currency="KWD")
    load = LoadRow(
        carrier="LH",
        filename="x.dat",
        digest="a" * 32,
        loaded_at=SEEN,
        effective_from=date(2025, 1, 1),
    )
    load.fares.append(fare_to_row(original, load_id=0))
    session.add(load)
    session.commit()
    from gussetline.persistence.models import FareRow

    assert row_to_fare(session.scalars(select(FareRow)).one()).amount.amount == Decimal("12.999")


def test_a_segment_round_trips():
    original = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")
    assert row_to_segment(segment_to_row(original, 0)) == original


def test_a_codeshare_keeps_both_carriers():
    original = segment(
        "LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05", operating_carrier="UA"
    )
    row = segment_to_row(original, 0)
    assert row.operating_carrier == "UA"
    assert row_to_segment(row).is_codeshare


def test_a_segment_with_no_operating_carrier_stays_that_way():
    original = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")
    row = segment_to_row(original, 0)
    assert row.operating_carrier == ""
    assert row_to_segment(row).operating_carrier is None


def test_a_tax_keeps_whether_it_was_carrier_money():
    government = tax_to_row(AppliedTax("DE", Money.of("70.83", "EUR"), "FRA"))
    carrier_money = tax_to_row(
        AppliedTax("YQ", Money.of("180.00", "EUR"), "FRA", carrier_imposed=True)
    )
    assert not government.carrier_imposed
    assert carrier_money.carrier_imposed


def test_an_offer_round_trips_through_the_database(session):
    session.add(offer_to_row(an_offer()))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.source == "gds"
    assert back.breakdown.base == Money.of("840.00", "EUR")
    assert back.fare_bases == ("KLWAP7RT", "KLWAP7RT")
    assert back.seats == 4
    assert back.baggage.pieces == 1
    assert back.refundable is False
    assert back.validating_carrier == "LH"


def test_the_itinerary_comes_back_in_order(session):
    session.add(offer_to_row(an_offer()))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert [s.designator for s in back.itinerary.segments] == ["LH400", "LH401"]
    assert back.itinerary.origin == "FRA"
    assert back.itinerary.destination == "FRA"


def test_the_taxes_come_back_split_the_way_they_went_in(session):
    session.add(offer_to_row(an_offer()))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.breakdown.tax_total == Money.of("70.83", "EUR")
    assert back.breakdown.surcharge_total == Money.of("180.00", "EUR")


def test_a_quoted_total_that_disagrees_survives_storage(session):
    disagreeing = an_offer(
        breakdown=PriceBreakdown(
            Money.of("840.00", "EUR"),
            (AppliedTax("DE", Money.of("70.83", "EUR"), "FRA"),),
            Money.of("999.00", "EUR"),
        )
    )
    session.add(offer_to_row(disagreeing))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.breakdown.quoted_total == Money.of("999.00", "EUR")
    assert not back.breakdown.adds_up


def test_an_offer_with_no_quoted_total_comes_back_without_one(session):
    plain = an_offer(breakdown=PriceBreakdown(Money.of("840.00", "EUR")))
    session.add(offer_to_row(plain))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.breakdown.quoted_total is None
    assert back.breakdown.adds_up


def test_the_fingerprint_is_written_and_survives(session):
    original = an_offer()
    session.add(offer_to_row(original))
    session.commit()
    row = session.scalars(select(ObservationRow)).one()
    assert row.fingerprint == original.fingerprint()
    assert row_to_offer(row).fingerprint() == original.fingerprint()


def test_the_series_key_is_built_the_one_way(session):
    session.add(offer_to_row(an_offer()))
    session.commit()
    row = session.scalars(select(ObservationRow)).one()
    assert row.series_key == series_key_of("FRA", "FRA", date(2025, 7, 4), "Y")


def test_a_business_cabin_offer_keeps_its_cabin(session):
    business = an_offer(cabin=Cabin.BUSINESS)
    session.add(offer_to_row(business))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.cabin is Cabin.BUSINESS


def test_an_offer_with_nothing_optional_still_round_trips(session):
    bare = Offer(
        source="ndc",
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")]
        ),
        breakdown=PriceBreakdown(Money.of("420.00", "EUR")),
        fare_bases=("KLWAP7RT",),
        observed_at=SEEN,
    )
    session.add(offer_to_row(bare))
    session.commit()
    back = row_to_offer(session.scalars(select(ObservationRow)).one())
    assert back.seats is None
    assert not back.baggage.is_stated
    assert back.refundable is None
    assert back.validating_carrier is None
