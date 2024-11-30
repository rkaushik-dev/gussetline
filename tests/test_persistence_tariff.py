from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from gussetline.persistence.base import Base
from gussetline.persistence.models import (
    CombinabilityRow,
    FareRow,
    LoadRow,
    ProvisionRow,
    RoutingRow,
    RuleRow,
    SurchargeRow,
)

LOADED = datetime(2025, 6, 3, 6, 0)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def a_load(**over) -> LoadRow:
    fields = {
        "carrier": "LH",
        "filename": "LH-20250603.dat",
        "digest": "a" * 32,
        "loaded_at": LOADED,
        "effective_from": date(2025, 6, 1),
        "row_count": 2,
    }
    fields.update(over)
    return LoadRow(**fields)


def a_fare(**over) -> FareRow:
    fields = {
        "carrier": "LH",
        "origin": "FRA",
        "destination": "NYC",
        "fare_basis": "KLWAP7RT",
        "amount": Decimal("420.00"),
        "currency": "EUR",
        "owrt": "2",
        "global_indicator": "AT",
        "rule_number": "1100",
        "routing_number": "0000",
        "effective": date(2025, 1, 1),
        "discontinue": date(2025, 12, 31),
        "first_travel": date(2025, 1, 1),
        "last_travel": date(2025, 12, 31),
    }
    fields.update(over)
    return FareRow(**fields)


def test_a_load_carries_its_fares(session):
    load = a_load()
    load.fares.append(a_fare())
    load.fares.append(a_fare(fare_basis="QLWAP14RT", amount=Decimal("380.00")))
    session.add(load)
    session.commit()
    found = session.scalars(select(LoadRow)).one()
    assert len(found.fares) == 2
    assert repr(found) == "<Load LH LH-20250603.dat 2 rows>"


def test_retiring_a_load_takes_its_fares_with_it(session):
    load = a_load()
    load.fares.append(a_fare())
    session.add(load)
    session.commit()
    session.delete(load)
    session.commit()
    assert session.scalars(select(FareRow)).all() == []


def test_two_loads_of_the_same_market_both_survive(session):
    first = a_load(digest="a" * 32)
    first.fares.append(a_fare(amount=Decimal("420.00")))
    second = a_load(digest="b" * 32, loaded_at=datetime(2025, 6, 10, 6, 0))
    second.fares.append(a_fare(amount=Decimal("440.00")))
    session.add_all([first, second])
    session.commit()
    amounts = sorted(f.amount for f in session.scalars(select(FareRow)))
    assert amounts == [Decimal("420.00"), Decimal("440.00")]


def test_a_load_can_be_marked_retired_without_deleting_it(session):
    load = a_load()
    session.add(load)
    session.commit()
    load.retired = True
    session.commit()
    assert session.scalars(select(LoadRow).where(LoadRow.retired)).one() is load


def test_a_fare_reads_back_as_its_market_and_basis(session):
    load = a_load()
    load.fares.append(a_fare())
    session.add(load)
    session.commit()
    assert repr(session.scalars(select(FareRow)).one()) == "<Fare LH FRANYC KLWAP7RT>"


def test_the_market_index_is_declared():
    names = {i.name for i in FareRow.__table__.indexes}
    assert "ix_fares_market" in names
    assert "ix_fares_lookup" in names


def test_a_rule_carries_its_provisions(session):
    rule = RuleRow(carrier="LH", number="1100")
    rule.provisions.append(ProvisionRow(category=6, kind="minimum_stay", number_one=7))
    rule.provisions.append(ProvisionRow(category=16, kind="penalty", amount=Decimal("50.00")))
    session.add(rule)
    session.commit()
    found = session.scalars(select(RuleRow)).one()
    assert len(found.provisions) == 2
    assert repr(found) == "<Rule LH 1100>"
    assert repr(found.provisions[0]) == "<Provision cat 6 minimum_stay>"


def test_deleting_a_rule_takes_its_provisions(session):
    rule = RuleRow(carrier="LH", number="1100")
    rule.provisions.append(ProvisionRow(category=6, kind="minimum_stay", number_one=7))
    session.add(rule)
    session.commit()
    session.delete(rule)
    session.commit()
    assert session.scalars(select(ProvisionRow)).all() == []


def test_the_same_rule_number_twice_on_one_carrier_is_refused(session):
    session.add(RuleRow(carrier="LH", number="1100"))
    session.add(RuleRow(carrier="LH", number="1100"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_same_rule_number_on_another_tariff_is_a_different_rule(session):
    session.add(RuleRow(carrier="LH", number="1100", tariff_number="1"))
    session.add(RuleRow(carrier="LH", number="1100", tariff_number="8"))
    session.commit()
    assert len(session.scalars(select(RuleRow)).all()) == 2


def test_a_provision_amount_keeps_its_decimals(session):
    rule = RuleRow(carrier="LH", number="1100")
    rule.provisions.append(
        ProvisionRow(category=16, kind="penalty", amount=Decimal("12.999"), currency="KWD")
    )
    session.add(rule)
    session.commit()
    assert session.scalars(select(ProvisionRow)).one().amount == Decimal("12.999")


def test_a_surcharge_stores_and_reads_back(session):
    session.add(
        SurchargeRow(
            carrier="LH",
            origin="FRA",
            destination="NYC",
            amount=Decimal("30.00"),
            currency="EUR",
            first_travel=date(2025, 1, 1),
            last_travel=date(2025, 12, 31),
        )
    )
    session.commit()
    found = session.scalars(select(SurchargeRow)).one()
    assert found.scope == "component"
    assert repr(found) == "<Surcharge Q FRANYC>"


def test_combinability_defaults_to_refusing_everything(session):
    session.add(CombinabilityRow(carrier="LH", fare_basis="KLWAP7RT"))
    session.commit()
    found = session.scalars(select(CombinabilityRow)).one()
    assert not found.round_trip
    assert not found.end_on_end
    assert found.same_carrier_only
    assert repr(found) == "<Combinability LH KLWAP7RT>"


def test_the_same_fare_basis_twice_on_one_carrier_is_refused(session):
    session.add(CombinabilityRow(carrier="LH", fare_basis="KLWAP7RT"))
    session.add(CombinabilityRow(carrier="LH", fare_basis="KLWAP7RT"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_routing_hop_stores_its_carriers_as_text(session):
    session.add(
        RoutingRow(carrier="LH", number="0101", origin="FRA", destination="NYC", carriers="LH/UA")
    )
    session.commit()
    found = session.scalars(select(RoutingRow)).one()
    assert found.carriers == "LH/UA"
    assert found.bidirectional
    assert repr(found) == "<Routing LH 0101 FRA-NYC>"


def test_a_routing_map_is_many_rows_under_one_number(session):
    for origin, destination in (("FRA", "LON"), ("LON", "NYC")):
        session.add(RoutingRow(carrier="LH", number="0101", origin=origin, destination=destination))
    session.commit()
    rows = session.scalars(select(RoutingRow).where(RoutingRow.number == "0101")).all()
    assert len(rows) == 2
