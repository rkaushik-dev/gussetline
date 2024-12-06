from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from gussetline.persistence.base import Base
from gussetline.persistence.models import ObservationRow, QuoteRow, SectorRow, TaxRow

SEEN = datetime(2025, 6, 5, 9, 0)
LATER = datetime(2025, 6, 7, 9, 0)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def observation(**over) -> ObservationRow:
    fields = {
        "series_key": "FRA-NYC-2025-07-04-Y",
        "fingerprint": "f" * 32,
        "source": "gds",
        "origin": "FRA",
        "destination": "JFK",
        "departure": date(2025, 7, 4),
        "fare_bases": "KLWAP7RT",
        "base_amount": Decimal("420.00"),
        "base_currency": "EUR",
        "tax_amount": Decimal("70.83"),
        "surcharge_amount": Decimal("180.00"),
        "total_amount": Decimal("670.83"),
        "first_seen": SEEN,
        "last_seen": SEEN,
    }
    fields.update(over)
    return ObservationRow(**fields)


def sector(position=0, **over) -> SectorRow:
    fields = {
        "position": position,
        "marketing_carrier": "LH",
        "flight_number": "400",
        "origin": "FRA",
        "destination": "JFK",
        "departure": datetime(2025, 7, 4, 10, 25),
        "arrival": datetime(2025, 7, 4, 13, 5),
        "booking_class": "K",
    }
    fields.update(over)
    return SectorRow(**fields)


def test_an_observation_stores_its_price_parts(session):
    session.add(observation())
    session.commit()
    found = session.scalars(select(ObservationRow)).one()
    assert found.base_amount == Decimal("420.00")
    assert found.surcharge_amount == Decimal("180.00")
    assert found.sightings == 1
    assert repr(found) == "<Observation FRA-NYC-2025-07-04-Y 670.83 EUR>"


def test_the_same_offer_twice_in_one_series_is_refused(session):
    session.add(observation())
    session.add(observation())
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_same_fingerprint_in_another_series_is_a_different_row(session):
    session.add(observation())
    session.add(observation(series_key="FRA-NYC-2025-08-04-Y", departure=date(2025, 8, 4)))
    session.commit()
    assert len(session.scalars(select(ObservationRow)).all()) == 2


def test_a_repeat_sighting_moves_the_last_seen_rather_than_adding_a_row(session):
    session.add(observation())
    session.commit()
    found = session.scalars(select(ObservationRow)).one()
    found.last_seen = LATER
    found.sightings += 1
    session.commit()
    again = session.scalars(select(ObservationRow)).one()
    assert again.first_seen == SEEN
    assert again.last_seen == LATER
    assert again.sightings == 2
    assert session.scalar(select(func.count()).select_from(ObservationRow)) == 1


def test_an_observation_carries_its_sectors_in_order(session):
    row = observation()
    row.sectors.append(sector(1, origin="JFK", destination="FRA", flight_number="401"))
    row.sectors.append(sector(0))
    session.add(row)
    session.commit()
    found = session.scalars(select(ObservationRow)).one()
    assert [s.position for s in found.sectors] == [0, 1]
    assert found.sectors[0].flight_number == "400"


def test_two_sectors_at_the_same_position_are_refused(session):
    row = observation()
    row.sectors.append(sector(0))
    row.sectors.append(sector(0, flight_number="401"))
    session.add(row)
    with pytest.raises(IntegrityError):
        session.commit()


def test_deleting_an_observation_takes_its_sectors_and_taxes(session):
    row = observation()
    row.sectors.append(sector(0))
    row.taxes.append(TaxRow(code="DE", amount=Decimal("70.83"), currency="EUR"))
    session.add(row)
    session.commit()
    session.delete(row)
    session.commit()
    assert session.scalars(select(SectorRow)).all() == []
    assert session.scalars(select(TaxRow)).all() == []


def test_a_tax_remembers_whether_it_was_carrier_money(session):
    row = observation()
    row.taxes.append(TaxRow(code="DE", amount=Decimal("70.83"), currency="EUR"))
    row.taxes.append(
        TaxRow(code="YQ", amount=Decimal("180.00"), currency="EUR", carrier_imposed=True)
    )
    session.add(row)
    session.commit()
    found = session.scalars(select(TaxRow).order_by(TaxRow.code)).all()
    assert [t.carrier_imposed for t in found] == [False, True]
    assert repr(found[0]) == "<Tax DE 70.83>"


def test_a_sector_reads_back_as_a_timetable_line(session):
    row = observation()
    row.sectors.append(sector(0))
    session.add(row)
    session.commit()
    assert repr(session.scalars(select(SectorRow)).one()) == "<Sector LH400 FRAJFK>"


def test_the_series_index_is_declared():
    names = {i.name for i in ObservationRow.__table__.indexes}
    assert "ix_observations_series_seen" in names
    assert "ix_observations_market" in names


def test_a_series_can_be_read_back_cheapest_first(session):
    session.add(observation(fingerprint="a" * 32, total_amount=Decimal("670.83")))
    session.add(observation(fingerprint="b" * 32, total_amount=Decimal("610.00")))
    session.commit()
    rows = session.scalars(
        select(ObservationRow)
        .where(ObservationRow.series_key == "FRA-NYC-2025-07-04-Y")
        .order_by(ObservationRow.total_amount)
    ).all()
    assert [r.total_amount for r in rows] == [Decimal("610.00"), Decimal("670.83")]


def test_a_quoted_total_that_disagrees_is_kept_beside_ours(session):
    session.add(observation(quoted_total=Decimal("700.00")))
    session.commit()
    found = session.scalars(select(ObservationRow)).one()
    assert found.quoted_total == Decimal("700.00")
    assert found.total_amount == Decimal("670.83")


def test_a_quote_stores_and_reads_back(session):
    session.add(
        QuoteRow(
            origin="FRA",
            destination="NYC",
            departure=date(2025, 7, 4),
            observed_on=date(2025, 6, 5),
            amount=Decimal("420.00"),
            currency="EUR",
            carrier="LH",
        )
    )
    session.commit()
    found = session.scalars(select(QuoteRow)).one()
    assert found.carrier == "LH"
    assert not found.sold_out
    assert repr(found) == "<Quote FRANYC 2025-07-04 420.00>"


def test_the_same_quote_twice_on_one_day_is_refused(session):
    for _ in range(2):
        session.add(
            QuoteRow(
                origin="FRA",
                destination="NYC",
                departure=date(2025, 7, 4),
                observed_on=date(2025, 6, 5),
                amount=Decimal("420.00"),
                currency="EUR",
            )
        )
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_same_quote_on_another_day_is_a_new_row(session):
    for day in (date(2025, 6, 5), date(2025, 6, 6)):
        session.add(
            QuoteRow(
                origin="FRA",
                destination="NYC",
                departure=date(2025, 7, 4),
                observed_on=day,
                amount=Decimal("420.00"),
                currency="EUR",
            )
        )
    session.commit()
    assert len(session.scalars(select(QuoteRow)).all()) == 2


def test_a_sold_out_quote_is_stored_with_its_flag(session):
    session.add(
        QuoteRow(
            origin="FRA",
            destination="NYC",
            departure=date(2025, 7, 4),
            observed_on=date(2025, 6, 5),
            amount=Decimal("0"),
            currency="EUR",
            sold_out=True,
        )
    )
    session.commit()
    assert session.scalars(select(QuoteRow)).one().sold_out
