from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from gussetline.api.app import create_app
from gussetline.api.security import (
    ApiKey,
    Forbidden,
    KeyRing,
    Role,
    Unauthorised,
    fingerprint,
)
from gussetline.config import Settings
from gussetline.persistence.models import RecommendationRow, TicketRow, WatchRow
from gussetline.persistence.unitofwork import in_memory

TODAY = date(2025, 6, 29)


@pytest.fixture
def ring() -> KeyRing:
    made = KeyRing()
    made.issue("screens", "reader-key", Role.READER)
    made.issue("anna", "agent-key", Role.AGENT)
    made.issue("ops", "operator-key", Role.OPERATOR)
    return made


@pytest.fixture
def database():
    made = in_memory()
    with made.unit() as work:
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
                paid_amount=Decimal("840.00"),
                new_fare_amount=Decimal("700.00"),
                penalty_amount=Decimal("50.00"),
                benefit_amount=Decimal("90.00"),
                currency="EUR",
            )
        )
        ticket.watches.append(watch)
        work.session.add(ticket)
        work.commit()
    return made


@pytest.fixture
def client(database, ring) -> TestClient:
    settings = Settings().with_database("sqlite+pysqlite:///:memory:")
    return TestClient(create_app(settings, database, ring))


def as_key(client: TestClient, key: str) -> dict[str, str]:
    return {"X-Api-Key": key}


def test_a_key_is_stored_as_a_hash_and_never_as_itself():
    key = ApiKey(name="x", digest=fingerprint("secret"))
    assert key.digest != "secret"
    assert len(key.digest) == 64
    assert key.matches("secret")
    assert not key.matches("Secret")


def test_a_key_is_compared_after_the_same_normalising():
    key = ApiKey(name="x", digest=fingerprint("secret"))
    assert key.matches("  secret  ")


def test_the_roles_are_ordered_and_the_order_is_meaningful():
    assert Role.OPERATOR.covers(Role.READER)
    assert Role.OPERATOR.covers(Role.AGENT)
    assert Role.AGENT.covers(Role.READER)
    assert not Role.READER.covers(Role.AGENT)
    assert not Role.AGENT.covers(Role.OPERATOR)


def test_every_role_covers_itself():
    for role in Role:
        assert role.covers(role)


def test_a_key_reads_back_with_its_role():
    assert str(ApiKey(name="anna", digest="x", role=Role.AGENT)) == "anna as agent"


def test_the_ring_finds_a_key_it_holds(ring):
    found = ring.find("agent-key")
    assert found is not None
    assert found.name == "anna"
    assert len(ring) == 3


def test_the_ring_does_not_find_one_it_does_not(ring):
    assert ring.find("nonsense") is None


def test_no_key_at_all_is_unauthorised(client):
    reply = client.get("/observations/series/anything")
    assert reply.status_code == 401
    assert reply.json()["code"] == "auth.unauthorised"


def test_a_key_we_do_not_hold_is_unauthorised(client):
    reply = client.get("/observations/series/anything", headers=as_key(client, "nonsense"))
    assert reply.status_code == 401


def test_a_reader_can_read_observations(client):
    key = "FRA-LON-2099-01-01-Y"
    reply = client.get(f"/observations/series/{key}", headers=as_key(client, "reader-key"))
    # Not found rather than forbidden, which means the key got through.
    assert reply.status_code == 404


def test_a_reader_can_read_watches(client):
    reply = client.get("/watches", headers=as_key(client, "reader-key"))
    assert reply.status_code == 200


def test_a_reader_cannot_settle_a_recommendation(client):
    reply = client.post(
        "/watches/recommendations/1/take",
        json={"by": "anna"},
        headers=as_key(client, "reader-key"),
    )
    assert reply.status_code == 403
    body = reply.json()
    assert body["code"] == "auth.forbidden"
    assert body["details"] == {"key": "screens", "has": "reader", "needs": "agent"}


def test_an_agent_can_settle_a_recommendation(client):
    reply = client.post(
        "/watches/recommendations/1/take",
        json={"by": "anna"},
        headers=as_key(client, "agent-key"),
    )
    assert reply.status_code == 200
    assert reply.json()["state"] == "taken"


def test_an_agent_cannot_see_the_supplier_screen(client):
    reply = client.get("/sources", headers=as_key(client, "agent-key"))
    assert reply.status_code == 403
    assert reply.json()["details"]["needs"] == "operator"


def test_an_operator_can_see_everything(client):
    assert client.get("/sources", headers=as_key(client, "operator-key")).status_code == 200
    assert client.get("/watches", headers=as_key(client, "operator-key")).status_code == 200
    assert (
        client.post(
            "/watches/recommendations/1/kill",
            json={"by": "ops"},
            headers=as_key(client, "operator-key"),
        ).status_code
        == 200
    )


def test_health_needs_no_key_at_all(client):
    assert client.get("/health").status_code == 200
    assert client.get("/ready").status_code == 200


def test_an_application_with_no_ring_lets_everything_through(database):
    settings = Settings().with_database("sqlite+pysqlite:///:memory:")
    open_client = TestClient(create_app(settings, database))
    assert open_client.get("/sources").status_code == 200
    assert open_client.get("/watches").status_code == 200


def test_a_disabled_ring_also_lets_everything_through(database, ring):
    ring.enabled = False
    settings = Settings().with_database("sqlite+pysqlite:///:memory:")
    open_client = TestClient(create_app(settings, database, ring))
    assert open_client.get("/sources").status_code == 200


def test_the_refusals_carry_the_key_name_so_a_log_says_who(client):
    reply = client.get("/sources", headers=as_key(client, "reader-key"))
    assert reply.json()["details"]["key"] == "screens"


def test_the_auth_errors_are_our_own_kind():
    assert Unauthorised("x").code == "auth.unauthorised"
    assert Forbidden("x").code == "auth.forbidden"
