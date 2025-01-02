from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from conftest import fare, segment
from gussetline.carriers import carrier
from gussetline.errors import NotFoundError
from gussetline.geography import airport
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.persistence.base import Base
from gussetline.persistence.models import QuoteRow, TicketRow, WatchRow
from gussetline.persistence.repositories import (
    AirportRepository,
    CarrierRepository,
    ObservationRepository,
    QuoteRepository,
    TariffRepository,
    WatchRepository,
)

SEEN = datetime(2025, 6, 12, 9, 0)
LATER = datetime(2025, 6, 14, 9, 0)
TODAY = date(2025, 6, 12)


@pytest.fixture
def session():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as made:
        yield made


def an_offer(amount="840.00", departure="2025-07-04 10:25", **over) -> Offer:
    fields = {
        "source": "gds",
        "itinerary": Itinerary([segment("LH", "400", "FRA", "JFK", departure, "2025-07-04 13:05")]),
        "breakdown": PriceBreakdown(Money.of(amount, "EUR")),
        "fare_bases": ("KLWAP7RT",),
        "observed_at": SEEN,
    }
    fields.update(over)
    return Offer(**fields)


def test_an_airport_saves_once_and_reads_back(session):
    repo = AirportRepository(session)
    repo.save(airport("FRA"))
    repo.save(airport("FRA"))
    session.commit()
    assert repo.count() == 1
    assert repo.get("fra").name == "Frankfurt am Main"


def test_an_airport_we_do_not_hold_is_a_not_found(session):
    with pytest.raises(NotFoundError):
        AirportRepository(session).get("QQQ")


def test_the_airports_of_a_city_come_back_sorted(session):
    repo = AirportRepository(session)
    for code in ("LHR", "LGW", "STN"):
        repo.save(airport(code))
    session.commit()
    assert [a.code for a in repo.in_city("LON")] == ["LGW", "LHR", "STN"]


def test_a_carrier_saves_and_reads_back(session):
    repo = CarrierRepository(session)
    repo.save(carrier("LH"))
    repo.save(carrier("BA"))
    session.commit()
    assert [c.code for c in repo.all()] == ["BA", "LH"]
    assert repo.get("lh").name == "Lufthansa"


def test_a_carrier_we_do_not_hold_is_a_not_found(session):
    with pytest.raises(NotFoundError):
        CarrierRepository(session).get("ZZ")


def test_a_load_takes_its_fares_and_counts_them(session):
    repo = TariffRepository(session)
    load = repo.open_load("LH", "LH.dat", "a" * 32, SEEN, date(2025, 1, 1))
    written = repo.add_fares(load, [fare(amount="420.00"), fare(amount="380.00", basis="QRT")])
    session.commit()
    assert written == 2
    assert repo.count_fares() == 2


def test_a_fare_search_comes_back_cheapest_first(session):
    repo = TariffRepository(session)
    load = repo.open_load("LH", "LH.dat", "a" * 32, SEEN, date(2025, 1, 1))
    repo.add_fares(load, [fare(amount="420.00"), fare(amount="380.00", basis="QRT")])
    session.commit()
    found = repo.search("LH", "FRA", "NYC")
    assert [f.amount.amount for f in found] == [Decimal("380.00"), Decimal("420.00")]


def test_a_fare_search_can_be_narrowed_to_a_date(session):
    repo = TariffRepository(session)
    load = repo.open_load("LH", "LH.dat", "a" * 32, SEEN, date(2025, 1, 1))
    repo.add_fares(
        load,
        [
            fare(amount="420.00", effective=date(2025, 1, 1), discontinue=date(2025, 3, 31)),
            fare(
                amount="440.00",
                basis="QRT",
                effective=date(2025, 4, 1),
                discontinue=date(2025, 12, 31),
            ),
        ],
    )
    session.commit()
    assert len(repo.search("LH", "FRA", "NYC", on=date(2025, 2, 14))) == 1
    assert len(repo.search("LH", "FRA", "NYC", on=date(2025, 5, 14))) == 1
    assert len(repo.search("LH", "FRA", "NYC")) == 2


def test_a_market_with_nothing_filed_comes_back_empty(session):
    assert TariffRepository(session).search("LH", "FRA", "BKK") == []


def test_the_loads_for_a_carrier_come_back_newest_first(session):
    repo = TariffRepository(session)
    repo.open_load("LH", "old.dat", "a" * 32, SEEN, date(2025, 1, 1))
    repo.open_load("LH", "new.dat", "b" * 32, LATER, date(2025, 6, 1))
    session.commit()
    assert [load.filename for load in repo.loads_for("LH")] == ["new.dat", "old.dat"]


def test_a_load_can_be_retired_without_losing_its_fares(session):
    repo = TariffRepository(session)
    load = repo.open_load("LH", "LH.dat", "a" * 32, SEEN, date(2025, 1, 1))
    repo.add_fares(load, [fare()])
    session.commit()
    repo.retire(load)
    session.commit()
    assert repo.loads_for("LH")[0].retired
    assert repo.count_fares() == 1


def test_a_new_offer_is_inserted(session):
    repo = ObservationRepository(session)
    repo.save(an_offer())
    session.commit()
    assert repo.count() == 1


def test_the_same_offer_again_moves_the_stamp_rather_than_adding_a_row(session):
    repo = ObservationRepository(session)
    repo.save(an_offer())
    session.commit()
    repo.save(an_offer(observed_at=LATER))
    session.commit()
    assert repo.count() == 1
    row = session.query(type(repo.save(an_offer()))).first()
    assert row is not None


def test_the_sighting_count_climbs_on_a_repeat(session):
    repo = ObservationRepository(session)
    repo.save(an_offer())
    session.commit()
    row = repo.save(an_offer(observed_at=LATER))
    session.commit()
    assert row.sightings == 2
    assert row.first_seen == SEEN
    assert row.last_seen == LATER


def test_an_earlier_sighting_moves_the_first_seen_back(session):
    repo = ObservationRepository(session)
    repo.save(an_offer(observed_at=LATER))
    session.commit()
    row = repo.save(an_offer(observed_at=SEEN))
    session.commit()
    assert row.first_seen == SEEN
    assert row.last_seen == LATER


def test_a_different_price_is_a_different_row(session):
    repo = ObservationRepository(session)
    repo.save(an_offer("840.00"))
    repo.save(an_offer("790.00"))
    session.commit()
    assert repo.count() == 2


def test_saving_a_batch_reports_new_against_repeats(session):
    repo = ObservationRepository(session)
    new, repeats = repo.save_all([an_offer("840.00"), an_offer("790.00")])
    session.commit()
    assert (new, repeats) == (2, 0)
    new, repeats = repo.save_all([an_offer("840.00"), an_offer("700.00")])
    session.commit()
    assert (new, repeats) == (1, 1)


def test_a_series_comes_back_cheapest_first(session):
    repo = ObservationRepository(session)
    repo.save_all([an_offer("840.00"), an_offer("790.00"), an_offer("900.00")])
    session.commit()
    key = an_offer().series_key()
    found = repo.series("-".join(key))
    assert [o.total.amount for o in found] == [
        Decimal("790.00"),
        Decimal("840.00"),
        Decimal("900.00"),
    ]


def test_the_cheapest_in_a_series_is_the_first_one(session):
    repo = ObservationRepository(session)
    repo.save_all([an_offer("840.00"), an_offer("790.00")])
    session.commit()
    cheapest = repo.cheapest_in("-".join(an_offer().series_key()))
    assert cheapest is not None
    assert cheapest.total == Money.of("790.00", "EUR")


def test_a_series_we_hold_nothing_for_has_no_cheapest(session):
    assert ObservationRepository(session).cheapest_in("nothing") is None


def test_observations_can_be_read_back_by_when_they_were_seen(session):
    repo = ObservationRepository(session)
    repo.save(an_offer("840.00", observed_at=SEEN))
    repo.save(an_offer("790.00", observed_at=LATER))
    session.commit()
    assert len(repo.seen_since(LATER)) == 1
    assert len(repo.seen_since(SEEN)) == 2


def ticket_with_watch(session, departure=date(2025, 7, 4), active=True) -> TicketRow:
    row = TicketRow(
        number=f"220-{departure.isoformat()}",
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
    row.watches.append(
        WatchRow(
            series_key=f"FRA-NYC-{departure.isoformat()}-Y",
            origin="FRA",
            destination="JFK",
            departure=departure,
            threshold_amount=Decimal("50.00"),
            threshold_currency="EUR",
            active=active,
        )
    )
    session.add(row)
    return row


def test_live_watches_come_back_closest_first(session):
    ticket_with_watch(session, date(2025, 8, 4))
    ticket_with_watch(session, date(2025, 7, 4))
    session.commit()
    found = WatchRepository(session).live(TODAY)
    assert [w.departure for w in found] == [date(2025, 7, 4), date(2025, 8, 4)]


def test_a_departure_that_has_gone_is_not_live(session):
    ticket_with_watch(session, date(2025, 6, 1))
    session.commit()
    assert WatchRepository(session).live(TODAY) == []


def test_a_retired_watch_is_not_live(session):
    ticket_with_watch(session, date(2025, 7, 4), active=False)
    session.commit()
    assert WatchRepository(session).live(TODAY) == []


def test_the_watches_of_one_ticket_can_be_found(session):
    ticket_with_watch(session, date(2025, 7, 4))
    session.commit()
    found = WatchRepository(session).for_ticket("220-2025-07-04")
    assert len(found) == 1


def test_flown_watches_are_retired_with_a_reason(session):
    ticket_with_watch(session, date(2025, 6, 1))
    ticket_with_watch(session, date(2025, 7, 4))
    session.commit()
    repo = WatchRepository(session)
    assert repo.retire_flown(TODAY) == 1
    session.commit()
    assert repo.retire_flown(TODAY) == 0
    assert len(repo.live(TODAY)) == 1


def a_quote(amount="420.00", departure=date(2025, 7, 4), sold_out=False) -> QuoteRow:
    return QuoteRow(
        origin="FRA",
        destination="NYC",
        departure=departure,
        observed_on=TODAY,
        amount=Decimal(amount),
        currency="EUR",
        sold_out=sold_out,
    )


def test_a_quote_saves_once_a_day_and_updates_after(session):
    repo = QuoteRepository(session)
    repo.save(a_quote("420.00"))
    session.commit()
    row = repo.save(a_quote("380.00"))
    session.commit()
    assert row.amount == Decimal("380.00")


def test_the_cheapest_quote_in_a_window_is_found(session):
    repo = QuoteRepository(session)
    repo.save(a_quote("420.00", date(2025, 7, 4)))
    repo.save(a_quote("380.00", date(2025, 7, 5)))
    session.commit()
    found = repo.cheapest_between("FRA", "NYC", date(2025, 7, 1), date(2025, 7, 31))
    assert found is not None
    assert found.departure == date(2025, 7, 5)


def test_a_sold_out_day_is_never_the_cheapest(session):
    repo = QuoteRepository(session)
    repo.save(a_quote("420.00", date(2025, 7, 4)))
    repo.save(a_quote("0", date(2025, 7, 5), sold_out=True))
    session.commit()
    found = repo.cheapest_between("FRA", "NYC", date(2025, 7, 1), date(2025, 7, 31))
    assert found is not None
    assert found.departure == date(2025, 7, 4)


def test_a_window_with_nothing_in_it_has_no_cheapest(session):
    assert (
        QuoteRepository(session).cheapest_between("FRA", "NYC", date(2025, 7, 1), date(2025, 7, 31))
        is None
    )
