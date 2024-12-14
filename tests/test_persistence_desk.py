from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from gussetline.persistence.base import Base
from gussetline.persistence.models import (
    CouponRow,
    RecommendationRow,
    RunResultRow,
    RunRow,
    TicketRow,
    WatchRow,
)

STARTED = datetime(2025, 6, 8, 3, 0)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def ticket(**over) -> TicketRow:
    fields = {
        "number": "220-1234567890",
        "record_locator": "ABC123",
        "passenger_name": "MUELLER/ANNA",
        "origin": "FRA",
        "destination": "JFK",
        "departure": date(2025, 7, 4),
        "issued_on": date(2025, 2, 14),
        "validating_carrier": "LH",
        "base_amount": Decimal("840.00"),
        "base_currency": "EUR",
        "total_amount": Decimal("1090.83"),
    }
    fields.update(over)
    return TicketRow(**fields)


def coupon(position=0, **over) -> CouponRow:
    fields = {
        "position": position,
        "carrier": "LH",
        "flight_number": "400",
        "origin": "FRA",
        "destination": "JFK",
        "departure": datetime(2025, 7, 4, 10, 25),
        "booking_class": "K",
        "fare_basis": "KLWAP7RT",
    }
    fields.update(over)
    return CouponRow(**fields)


def watch(**over) -> WatchRow:
    fields = {
        "series_key": "FRA-NYC-2025-07-04-Y",
        "origin": "FRA",
        "destination": "JFK",
        "departure": date(2025, 7, 4),
        "threshold_amount": Decimal("50.00"),
        "threshold_currency": "EUR",
    }
    fields.update(over)
    return WatchRow(**fields)


def test_a_ticket_carries_its_coupons_in_order(session):
    row = ticket()
    row.coupons.append(coupon(1, origin="JFK", destination="FRA", flight_number="401"))
    row.coupons.append(coupon(0))
    session.add(row)
    session.commit()
    found = session.scalars(select(TicketRow)).one()
    assert [c.position for c in found.coupons] == [0, 1]
    assert repr(found) == "<Ticket 220-1234567890 FRAJFK>"


def test_a_ticket_number_is_unique(session):
    session.add(ticket())
    session.add(ticket(record_locator="XYZ789"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_two_coupons_at_the_same_position_are_refused(session):
    row = ticket()
    row.coupons.append(coupon(0))
    row.coupons.append(coupon(0, flight_number="401"))
    session.add(row)
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_coupon_starts_open_and_unflown(session):
    row = ticket()
    row.coupons.append(coupon(0))
    session.add(row)
    session.commit()
    found = session.scalars(select(CouponRow)).one()
    assert found.status == "OK"
    assert not found.flown
    assert repr(found) == "<Coupon 0 LH400 OK>"


def test_a_flown_coupon_is_marked(session):
    row = ticket()
    row.coupons.append(coupon(0, flown=True, status="USED"))
    session.add(row)
    session.commit()
    assert session.scalars(select(CouponRow)).one().flown


def test_deleting_a_ticket_takes_its_coupons(session):
    row = ticket()
    row.coupons.append(coupon(0))
    session.add(row)
    session.commit()
    session.delete(row)
    session.commit()
    assert session.scalars(select(CouponRow)).all() == []


def test_a_reissued_ticket_names_the_one_it_came_from(session):
    session.add(ticket())
    session.add(ticket(number="220-9999999999", reissued_from="220-1234567890"))
    session.commit()
    found = session.scalars(select(TicketRow).where(TicketRow.reissued_from != "")).one()
    assert found.reissued_from == "220-1234567890"


def test_a_watch_hangs_off_a_ticket_and_starts_live(session):
    row = ticket()
    row.watches.append(watch())
    session.add(row)
    session.commit()
    found = session.scalars(select(WatchRow)).one()
    assert found.active
    assert found.checks == 0
    assert repr(found) == "<Watch FRA-NYC-2025-07-04-Y live>"


def test_a_retired_watch_says_why(session):
    row = ticket()
    row.watches.append(watch(active=False, retired_reason="departure has gone"))
    session.add(row)
    session.commit()
    found = session.scalars(select(WatchRow)).one()
    assert repr(found) == "<Watch FRA-NYC-2025-07-04-Y retired, departure has gone>"


def test_a_watch_counts_its_checks(session):
    row = ticket()
    row.watches.append(watch())
    session.add(row)
    session.commit()
    found = session.scalars(select(WatchRow)).one()
    found.checks += 1
    found.last_checked = datetime(2025, 6, 9, 3, 0)
    session.commit()
    assert session.scalars(select(WatchRow)).one().checks == 1


def test_one_ticket_can_carry_two_watches(session):
    row = ticket()
    row.watches.append(watch())
    row.watches.append(watch(series_key="FRA-NYC-2025-07-04-C", cabin="C"))
    session.add(row)
    session.commit()
    assert len(session.scalars(select(TicketRow)).one().watches) == 2


def recommendation(**over) -> RecommendationRow:
    fields = {
        "raised_on": date(2025, 6, 10),
        "paid_amount": Decimal("840.00"),
        "new_fare_amount": Decimal("620.00"),
        "penalty_amount": Decimal("50.00"),
        "benefit_amount": Decimal("170.00"),
        "currency": "EUR",
        "residual": "refundable",
    }
    fields.update(over)
    return RecommendationRow(**fields)


def test_a_recommendation_keeps_every_number_it_was_made_on(session):
    row = ticket()
    seen = watch()
    seen.recommendations.append(recommendation())
    row.watches.append(seen)
    session.add(row)
    session.commit()
    found = session.scalars(select(RecommendationRow)).one()
    assert found.paid_amount == Decimal("840.00")
    assert found.benefit_amount == Decimal("170.00")
    assert found.state == "open"
    assert repr(found) == "<Recommendation open worth 170.00 EUR>"


def test_a_recommendation_moves_through_its_states(session):
    row = ticket()
    seen = watch()
    seen.recommendations.append(recommendation())
    row.watches.append(seen)
    session.add(row)
    session.commit()
    found = session.scalars(select(RecommendationRow)).one()
    found.state = "taken"
    found.decided_on = date(2025, 6, 11)
    found.decided_by = "anna"
    session.commit()
    again = session.scalars(select(RecommendationRow)).one()
    assert again.state == "taken"
    assert again.decided_by == "anna"


def test_deleting_a_watch_takes_its_recommendations(session):
    row = ticket()
    seen = watch()
    seen.recommendations.append(recommendation())
    row.watches.append(seen)
    session.add(row)
    session.commit()
    session.delete(seen)
    session.commit()
    assert session.scalars(select(RecommendationRow)).all() == []


def test_a_run_carries_its_results(session):
    run = RunRow(started_at=STARTED, tasks=2, calls=2, offers=5)
    run.results.append(
        RunResultRow(
            supplier="gds",
            market="FRA-NYC",
            departure=date(2025, 7, 4),
            outcome="ok",
            offers=3,
            attempts=1,
        )
    )
    run.results.append(
        RunResultRow(
            supplier="ndc",
            market="FRA-NYC",
            departure=date(2025, 7, 4),
            outcome="budget",
            reason="quota",
        )
    )
    session.add(run)
    session.commit()
    found = session.scalars(select(RunRow)).one()
    assert len(found.results) == 2
    assert repr(found) == "<Run 2025-06-08 03:00 2 tasks>"
    assert repr(found.results[0]) == "<Result gds FRA-NYC ok>"


def test_a_run_that_did_nothing_is_still_a_row(session):
    session.add(RunRow(started_at=STARTED))
    session.commit()
    found = session.scalars(select(RunRow)).one()
    assert found.tasks == 0
    assert found.cost_amount == Decimal(0)


def test_deleting_a_run_takes_its_results(session):
    run = RunRow(started_at=STARTED)
    run.results.append(
        RunResultRow(supplier="gds", market="FRA-NYC", departure=date(2025, 7, 4), outcome="ok")
    )
    session.add(run)
    session.commit()
    session.delete(run)
    session.commit()
    assert session.scalars(select(RunResultRow)).all() == []


def test_the_live_watch_index_is_declared():
    names = {i.name for i in WatchRow.__table__.indexes}
    assert "ix_watches_live" in names
