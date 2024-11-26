from __future__ import annotations

from datetime import UTC, date, datetime, timedelta, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from gussetline.money import Money
from gussetline.persistence.base import Base, to_money
from gussetline.persistence.models import (
    AirportRow,
    CarrierRow,
    InterlineRow,
    MileageRow,
    RateRow,
)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def test_every_constraint_is_named_by_the_convention():
    names = {c.name for t in Base.metadata.tables.values() for c in t.constraints if c.name}
    assert "pk_airports" in names
    assert any(name.startswith("uq_rates") for name in names)


def test_an_index_is_named_by_the_convention():
    names = {index.name for table in Base.metadata.tables.values() for index in table.indexes}
    assert "ix_airports_city" in names


def test_an_amount_round_trips_without_losing_a_decimal(session):
    session.add(
        RateRow(
            table_name="roe",
            currency="KWD",
            effective=date(2025, 1, 1),
            units_per_nuc=Decimal("0.30675"),
        )
    )
    session.commit()
    found = session.scalars(select(RateRow)).one()
    assert found.units_per_nuc == Decimal("0.30675")
    assert isinstance(found.units_per_nuc, Decimal)


def test_a_long_amount_survives_the_column(session):
    session.add(
        RateRow(
            table_name="roe",
            currency="KRW",
            effective=date(2025, 1, 1),
            units_per_nuc=Decimal("1421.09876543"),
        )
    )
    session.commit()
    assert session.scalars(select(RateRow)).one().units_per_nuc == Decimal("1421.09876543")


def test_the_timestamps_are_filled_in_on_write(session):
    session.add(carrier())
    session.commit()
    found = session.scalars(select(CarrierRow)).one()
    assert isinstance(found.created_at, datetime)
    assert found.created_at.tzinfo is None


def test_an_aware_timestamp_is_converted_rather_than_refused(session):
    row = airport()
    row.created_at = datetime(2025, 6, 2, 12, 0, tzinfo=timezone(timedelta(hours=2)))
    session.add(row)
    session.commit()
    found = session.scalars(select(AirportRow)).one()
    assert found.created_at == datetime(2025, 6, 2, 10, 0)


def test_updating_a_row_moves_its_updated_at(session):
    session.add(carrier())
    session.commit()
    found = session.scalars(select(CarrierRow)).one()
    first = found.updated_at
    found.name = "Lufthansa German Airlines"
    found.updated_at = datetime.now(UTC).replace(tzinfo=None) + timedelta(seconds=1)
    session.commit()
    assert session.scalars(select(CarrierRow)).one().updated_at > first


def airport(**over) -> AirportRow:
    fields = {
        "code": "FRA",
        "name": "Frankfurt am Main",
        "city": "FRA",
        "country": "DE",
        "latitude": 50.033,
        "longitude": 8.570,
        "utc_offset_minutes": 60,
    }
    fields.update(over)
    return AirportRow(**fields)


def carrier(**over) -> CarrierRow:
    fields = {"code": "LH", "name": "Lufthansa", "country": "DE"}
    fields.update(over)
    return CarrierRow(**fields)


def test_an_airport_stores_and_reads_back(session):
    session.add(airport())
    session.commit()
    found = session.get(AirportRow, "FRA")
    assert found is not None
    assert found.city == "FRA"
    assert found.utc_offset_minutes == 60
    assert found.source == "shipped"
    assert repr(found) == "<Airport FRA in FRA>"


def test_a_carrier_defaults_to_unaligned_and_full_service(session):
    session.add(carrier())
    session.commit()
    found = session.get(CarrierRow, "LH")
    assert found is not None
    assert found.alliance == "none"
    assert found.low_cost is False
    assert repr(found) == "<Carrier LH>"


def test_an_interline_agreement_is_stored_once(session):
    session.add(InterlineRow(left_carrier="EK", right_carrier="LH"))
    session.commit()
    found = session.scalars(select(InterlineRow)).one()
    assert repr(found) == "<Interline EK/LH>"
    assert found.signed_on is None


def test_the_same_pair_twice_is_refused(session):
    session.add(InterlineRow(left_carrier="EK", right_carrier="LH"))
    session.commit()
    session.add(InterlineRow(left_carrier="EK", right_carrier="LH"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_rate_is_keyed_on_table_currency_and_date(session):
    session.add(
        RateRow(
            table_name="roe",
            currency="EUR",
            effective=date(2025, 1, 1),
            units_per_nuc=Decimal("0.951"),
        )
    )
    session.add(
        RateRow(
            table_name="bsr",
            currency="EUR",
            effective=date(2025, 1, 1),
            units_per_nuc=Decimal("0.980"),
        )
    )
    session.commit()
    assert len(session.scalars(select(RateRow)).all()) == 2


def test_the_same_rate_key_twice_is_refused(session):
    for _ in range(2):
        session.add(
            RateRow(
                table_name="roe",
                currency="EUR",
                effective=date(2025, 1, 1),
                units_per_nuc=Decimal("0.951"),
            )
        )
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_mileage_is_keyed_on_the_pair_and_the_indicator(session):
    session.add(MileageRow(origin="FRA", destination="TYO", indicator="TS", miles=5928))
    session.add(MileageRow(origin="FRA", destination="TYO", indicator="PA", miles=7169))
    session.commit()
    rows = session.scalars(select(MileageRow).order_by(MileageRow.miles)).all()
    assert [r.indicator for r in rows] == ["TS", "PA"]
    assert repr(rows[0]) == "<Mileage FRA-TYO 5928>"


def test_a_mileage_with_no_indicator_is_its_own_row(session):
    session.add(MileageRow(origin="FRA", destination="JFK", miles=3851))
    session.commit()
    assert session.scalars(select(MileageRow)).one().indicator == ""


def test_money_is_rebuilt_from_its_two_columns():
    assert to_money(Decimal("420.00"), "EUR") == Money.of("420.00", "EUR")


def test_money_from_a_half_filled_pair_is_nothing():
    assert to_money(None, "EUR") is None
    assert to_money(Decimal("420.00"), None) is None
    assert to_money(Decimal("420.00"), "") is None
