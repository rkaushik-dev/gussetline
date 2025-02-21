from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from conftest import segment
from gussetline.api.app import create_app
from gussetline.api.errors import status_for
from gussetline.config import Settings
from gussetline.errors import (
    ConflictError,
    ConstructionError,
    NoFareFound,
    NotFoundError,
    SupplierBlocked,
    ValidationError,
)
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.persistence.models import RecommendationRow, TicketRow, WatchRow
from gussetline.persistence.unitofwork import Database, in_memory

SEEN = datetime(2025, 6, 28, 9, 0)
TODAY = date(2025, 6, 28)


@pytest.fixture
def database() -> Database:
    return in_memory()


@pytest.fixture
def client(database) -> TestClient:
    settings = Settings().with_database("sqlite+pysqlite:///:memory:")
    return TestClient(create_app(settings, database))


def an_offer(amount="840.00") -> Offer:
    return Offer(
        source="gds",
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-08-04 10:25", "2025-08-04 13:05")]
        ),
        breakdown=PriceBreakdown(Money.of(amount, "EUR")),
        fare_bases=("KLWAP7RT",),
        observed_at=SEEN,
    )


def seed_ticket(database, state="open") -> int:
    with database.unit() as work:
        ticket = TicketRow(
            number="220-1234567890",
            record_locator="ABC123",
            passenger_name="MUELLER/ANNA",
            origin="FRA",
            destination="JFK",
            departure=date(2025, 8, 4),
            issued_on=date(2025, 2, 14),
            validating_carrier="LH",
            base_amount=Decimal("840.00"),
            base_currency="EUR",
            total_amount=Decimal("1090.83"),
        )
        watch = WatchRow(
            series_key="FRA-NYC-2025-08-04-Y",
            origin="FRA",
            destination="JFK",
            departure=date(2025, 8, 4),
            threshold_amount=Decimal("50.00"),
            threshold_currency="EUR",
        )
        watch.recommendations.append(
            RecommendationRow(
                raised_on=TODAY,
                state=state,
                paid_amount=Decimal("840.00"),
                new_fare_amount=Decimal("700.00"),
                penalty_amount=Decimal("50.00"),
                benefit_amount=Decimal("90.00"),
                currency="EUR",
                residual="refundable",
            )
        )
        ticket.watches.append(watch)
        work.session.add(ticket)
        work.commit()
        return watch.recommendations[0].id


def test_the_error_table_maps_each_kind_to_its_own_status():
    assert status_for(NotFoundError("x")) == 404
    assert status_for(ConflictError("x")) == 409
    assert status_for(ValidationError("x")) == 422
    assert status_for(NoFareFound("x")) == 422
    assert status_for(ConstructionError("x")) == 422
    assert status_for(SupplierBlocked("x")) == 502


def test_health_is_up_without_touching_storage(client):
    reply = client.get("/health")
    assert reply.status_code == 200
    assert reply.json() == {"status": "up"}


def test_ready_reaches_the_database_and_counts_what_is_in_it(client):
    reply = client.get("/ready")
    assert reply.status_code == 200
    body = reply.json()
    assert body["status"] == "ready"
    assert body["currency"] == "EUR"
    assert body["observations"] == 0


def test_every_response_carries_a_request_id(client):
    reply = client.get("/health")
    assert reply.headers["X-Request-Id"]


def test_a_request_id_that_was_given_is_kept(client):
    reply = client.get("/health", headers={"X-Request-Id": "abc123"})
    assert reply.headers["X-Request-Id"] == "abc123"


def test_two_requests_get_two_different_ids(client):
    first = client.get("/health").headers["X-Request-Id"]
    second = client.get("/health").headers["X-Request-Id"]
    assert first != second


def test_a_not_found_comes_back_in_the_one_error_shape(client):
    reply = client.get("/observations/series/nothing-at-all")
    assert reply.status_code == 404
    body = reply.json()
    assert body["code"] == "record.not_found"
    assert body["details"]["series_key"] == "nothing-at-all"
    assert body["request_id"]


def test_the_error_body_carries_the_domain_code_and_not_an_http_one(client):
    reply = client.get("/watches/ticket/220-nothing")
    assert reply.status_code == 404
    assert reply.json()["code"] == "record.not_found"


def test_a_series_reads_back_cheapest_first(client, database):
    with database.unit() as work:
        work.observations.save_all([an_offer("840.00"), an_offer("790.00")])
        work.commit()
    reply = client.get("/observations/series/FRA-JFK-2025-08-04-Y")
    assert reply.status_code == 200
    body = reply.json()
    assert [o["total"]["amount"] for o in body["offers"]] == ["790.00", "840.00"]
    assert body["cheapest"]["amount"] == "790.00"


def test_an_amount_crosses_the_wire_as_a_string(client, database):
    with database.unit() as work:
        work.observations.save(an_offer("840.00"))
        work.commit()
    body = client.get("/observations/series/FRA-JFK-2025-08-04-Y").json()
    assert body["offers"][0]["base"]["amount"] == "840.00"
    assert isinstance(body["offers"][0]["base"]["amount"], str)


def test_a_market_can_be_addressed_the_way_a_person_says_it(client, database):
    with database.unit() as work:
        work.observations.save(an_offer())
        work.commit()
    reply = client.get(
        "/observations/market",
        params={"origin": "FRA", "destination": "JFK", "departure": "2025-08-04"},
    )
    assert reply.status_code == 200
    assert reply.json()["series_key"] == "FRA-JFK-2025-08-04-Y"


def test_a_market_query_with_a_bad_code_is_refused_before_it_reaches_storage(client):
    reply = client.get(
        "/observations/market",
        params={"origin": "FRANKFURT", "destination": "JFK", "departure": "2025-08-04"},
    )
    assert reply.status_code == 422


def test_the_sources_screen_lists_every_supplier(client):
    reply = client.get("/sources")
    assert reply.status_code == 200
    names = [s["name"] for s in reply.json()]
    assert names == ["calendar", "gds", "ndc"]


def test_one_supplier_can_be_read_on_its_own(client):
    reply = client.get("/sources/gds")
    assert reply.status_code == 200
    assert reply.json()["cost_per_call"] == "0.04"
    assert "shop" in reply.json()["capabilities"]


def test_a_supplier_we_do_not_have_is_a_not_found(client):
    assert client.get("/sources/nobody").status_code == 404


def test_the_process_id_is_reported_because_the_breaker_is_per_process(client):
    assert client.get("/sources/-/process").json()["pid"]


def test_the_live_watches_come_back(client, database):
    seed_ticket(database)
    reply = client.get("/watches", params={"on": "2025-06-28"})
    assert reply.status_code == 200
    body = reply.json()
    assert len(body) == 1
    assert body[0]["ticket_number"] == "220-1234567890"
    assert body[0]["threshold"]["amount"] == "50.00"


def test_the_watches_of_one_ticket_can_be_found(client, database):
    seed_ticket(database)
    reply = client.get("/watches/ticket/220-1234567890")
    assert reply.status_code == 200
    assert len(reply.json()) == 1


def test_a_recommendation_reads_back_with_every_number(client, database):
    identifier = seed_ticket(database)
    body = client.get(f"/watches/recommendations/{identifier}").json()
    assert body["state"] == "open"
    assert body["paid"]["amount"] == "840.00"
    assert body["benefit"]["amount"] == "90.00"


def test_taking_a_recommendation_records_who_did_it(client, database):
    identifier = seed_ticket(database)
    reply = client.post(
        f"/watches/recommendations/{identifier}/take",
        json={"by": "anna", "note": "reissued on LH"},
    )
    assert reply.status_code == 200
    assert reply.json()["state"] == "taken"
    assert reply.json()["decided_by"] == "anna"


def test_killing_a_recommendation_is_recorded_too(client, database):
    identifier = seed_ticket(database)
    reply = client.post(f"/watches/recommendations/{identifier}/kill", json={"by": "anna"})
    assert reply.json()["state"] == "killed"


def test_a_decision_with_no_name_against_it_is_refused(client, database):
    identifier = seed_ticket(database)
    reply = client.post(f"/watches/recommendations/{identifier}/take", json={"by": ""})
    assert reply.status_code == 422


def test_settling_a_settled_recommendation_is_a_conflict(client, database):
    identifier = seed_ticket(database, state="taken")
    reply = client.post(f"/watches/recommendations/{identifier}/kill", json={"by": "bruno"})
    assert reply.status_code == 409
    assert reply.json()["code"] == "record.conflict"


def test_a_recommendation_we_do_not_have_is_a_not_found(client):
    assert client.get("/watches/recommendations/999").status_code == 404


def test_the_openapi_document_is_served(client):
    reply = client.get("/openapi.json")
    assert reply.status_code == 200
    assert reply.json()["info"]["title"] == "gussetline"
