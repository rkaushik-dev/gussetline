from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from gussetline.money import Money
from gussetline.persistence.book import READINGS, open_book, raised_between, readings_for
from gussetline.persistence.models import (
    CouponRow,
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import in_memory

DEPARTURE = date(2025, 12, 20)
SERIES = "FRA-NYC-2025-12-20-Y"


@pytest.fixture
def database():
    return in_memory()


def a_watch(work, series=SERIES, departure=DEPARTURE, number="220-1234567890") -> WatchRow:
    ticket = TicketRow(
        number=number,
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        origin="FRA",
        destination="JFK",
        departure=departure,
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_amount=Decimal("840.00"),
        base_currency="EUR",
        total_amount=Decimal("1090.83"),
    )
    ticket.coupons.append(
        CouponRow(
            position=0,
            carrier="LH",
            flight_number="400",
            origin="FRA",
            destination="JFK",
            departure=datetime(departure.year, departure.month, departure.day, 10, 25),
            booking_class="K",
            fare_basis="KLWAP7RT",
        )
    )
    watch = WatchRow(
        series_key=series,
        origin="FRA",
        destination="JFK",
        departure=departure,
        threshold_amount=Decimal("50.00"),
        threshold_currency="EUR",
    )
    ticket.watches.append(watch)
    work.session.add(ticket)
    work.session.flush()
    return watch


def a_recommendation(work, watch, raised=date(2025, 11, 20), state="open", benefit="90.00"):
    work.session.add(
        RecommendationRow(
            watch_id=watch.id,
            raised_on=raised,
            state=state,
            paid_amount=Decimal("840.00"),
            new_fare_amount=Decimal("700.00"),
            penalty_amount=Decimal("50.00"),
            benefit_amount=Decimal(benefit),
            currency="EUR",
            residual="refundable",
        )
    )


def a_reading(work, amount="700.00", seen=datetime(2025, 11, 20, 6, 0), sightings=1, series=SERIES):
    work.session.add(
        ObservationRow(
            series_key=series,
            source="gds",
            fingerprint=f"{series}-{amount}".ljust(32, "0")[:32],
            origin="FRA",
            destination="JFK",
            departure=DEPARTURE,
            cabin="Y",
            base_amount=Decimal(amount),
            base_currency="EUR",
            tax_amount=Decimal("0.00"),
            surcharge_amount=Decimal("0.00"),
            total_amount=Decimal(amount),
            first_seen=seen,
            last_seen=seen,
            sightings=sightings,
        )
    )


def test_an_empty_desk_has_an_empty_book(database):
    with database.unit() as work:
        book = open_book(work)
    assert len(book) == 0
    assert book.departures == {}
    assert str(book) == "0 open over 0 series"


def test_an_open_recommendation_is_in_the_book_with_its_departure(database):
    with database.unit() as work:
        a_recommendation(work, a_watch(work))
        work.commit()

    with database.unit() as work:
        book = open_book(work)
    assert len(book) == 1
    assert book.departures == {SERIES: DEPARTURE}
    assert book.recommendations[0].ticket_number == "220-1234567890"


def test_a_settled_recommendation_is_not_in_the_book(database):
    with database.unit() as work:
        a_recommendation(work, a_watch(work), state="taken")
        work.commit()

    with database.unit() as work:
        assert len(open_book(work)) == 0


def test_the_ticket_comes_loaded_so_a_caller_never_queries_per_row(database):
    # A caller that gets a row without it either trips over a detached
    # instance or quietly issues a query per row.
    with database.unit() as work:
        a_recommendation(work, a_watch(work))
        work.commit()

    with database.unit() as work:
        book = open_book(work)
    assert book.recommendations[0].series_key == SERIES


def test_the_readings_are_left_out_unless_they_are_asked_for(database):
    with database.unit() as work:
        a_recommendation(work, a_watch(work))
        a_reading(work)
        work.commit()

    with database.unit() as work:
        assert open_book(work).histories == {}
        assert open_book(work, with_readings=True).histories != {}


def test_a_reading_seen_on_three_mornings_counts_three_times(database):
    # The difference between a drop that has held for three days and one
    # seen this morning.
    with database.unit() as work:
        a_watch(work)
        a_reading(work, sightings=3)
        work.commit()

    with database.unit() as work:
        found = readings_for(work, [SERIES])
    assert len(found[SERIES]) == 3
    assert all(reading.amount == Money.of("700.00", "EUR") for reading in found[SERIES])


def test_a_reading_seen_once_counts_once(database):
    with database.unit() as work:
        a_watch(work)
        a_reading(work, sightings=1)
        work.commit()

    with database.unit() as work:
        assert len(readings_for(work, [SERIES])[SERIES]) == 1


def test_a_sighting_count_of_nothing_still_counts_once(database):
    with database.unit() as work:
        a_watch(work)
        a_reading(work, sightings=0)
        work.commit()

    with database.unit() as work:
        assert len(readings_for(work, [SERIES])[SERIES]) == 1


def test_the_readings_come_back_oldest_first(database):
    with database.unit() as work:
        a_watch(work)
        a_reading(work, amount="840.00", seen=datetime(2025, 11, 18, 6, 0))
        a_reading(work, amount="700.00", seen=datetime(2025, 11, 20, 6, 0))
        work.commit()

    with database.unit() as work:
        found = readings_for(work, [SERIES])
    assert [str(reading.amount) for reading in found[SERIES]] == ["840.00 EUR", "700.00 EUR"]


def test_the_readings_are_capped_however_many_polls_there_were(database):
    with database.unit() as work:
        a_watch(work)
        a_reading(work, sightings=40)
        work.commit()

    with database.unit() as work:
        assert len(readings_for(work, [SERIES])[SERIES]) == READINGS


def test_a_series_with_no_readings_comes_back_empty_rather_than_missing(database):
    with database.unit() as work:
        found = readings_for(work, [SERIES])
    assert found == {SERIES: []}


def test_asking_for_no_series_asks_nothing(database):
    with database.unit() as work:
        assert readings_for(work, []) == {}


def test_the_window_is_keyed_on_the_day_it_was_raised(database):
    with database.unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, raised=date(2025, 10, 1), state="taken")
        a_recommendation(work, watch, raised=date(2025, 11, 20), state="taken")
        work.commit()

    with database.unit() as work:
        found = raised_between(work, date(2025, 11, 1), date(2025, 11, 30))
    assert len(found) == 1
    assert found[0].raised_on == date(2025, 11, 20)


def test_the_window_takes_every_state_and_not_only_the_open_ones(database):
    with database.unit() as work:
        watch = a_watch(work)
        a_recommendation(work, watch, state="open")
        a_recommendation(work, watch, state="taken")
        a_recommendation(work, watch, state="expired")
        work.commit()

    with database.unit() as work:
        assert len(raised_between(work, date(2025, 11, 1), date(2025, 11, 30))) == 3


def test_a_window_with_nothing_in_it_is_empty(database):
    with database.unit() as work:
        assert raised_between(work, date(2020, 1, 1), date(2020, 12, 31)) == []


def test_two_series_are_two_entries_in_the_book(database):
    with database.unit() as work:
        a_recommendation(work, a_watch(work))
        a_recommendation(
            work,
            a_watch(work, series="MUC-CHI-2025-12-20-Y", number="220-9999999999"),
        )
        work.commit()

    with database.unit() as work:
        book = open_book(work)
    assert len(book) == 2
    assert book.series == ["FRA-NYC-2025-12-20-Y", "MUC-CHI-2025-12-20-Y"]
