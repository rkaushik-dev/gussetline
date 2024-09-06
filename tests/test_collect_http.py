from __future__ import annotations

import random

import httpx
import pytest

from gussetline.collect.http import (
    BLOCKING_STATUS,
    RETRYABLE_STATUS,
    HttpClient,
    Response,
    RetryPolicy,
)
from gussetline.errors import (
    MalformedPayload,
    SupplierBlocked,
    SupplierThrottled,
    SupplierUnavailable,
)


class Recorder:
    """A transport that answers from a script and counts what it was asked."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        answer = self.answers[min(len(self.calls) - 1, len(self.answers) - 1)]
        if isinstance(answer, Exception):
            raise answer
        return answer


def client(*answers, **over) -> HttpClient:
    recorder = Recorder(*answers)
    fields = {
        "base_url": "https://supplier.example",
        "transport": httpx.MockTransport(recorder),
        "sleep": lambda _: None,
        "clock": iter(range(1000)).__next__,
        "seed": 1,
    }
    fields.update(over)
    made = HttpClient(**fields)
    made.recorder = recorder
    return made


def ok(body=b"{}", status=200, headers=None):
    return httpx.Response(status, content=body, headers=headers or {})


def test_a_policy_tries_at_least_once():
    with pytest.raises(ValueError):
        RetryPolicy(attempts=0)


def test_the_delay_doubles_and_is_capped():
    policy = RetryPolicy(base_delay=1.0, max_delay=4.0, jitter=0.0)
    rng = random.Random(1)
    assert policy.delay_for(1, rng) == 1.0
    assert policy.delay_for(2, rng) == 2.0
    assert policy.delay_for(3, rng) == 4.0
    assert policy.delay_for(9, rng) == 4.0


def test_jitter_moves_the_delay_without_making_it_negative():
    policy = RetryPolicy(base_delay=1.0, jitter=0.5)
    rng = random.Random(7)
    delays = {policy.delay_for(1, rng) for _ in range(20)}
    assert len(delays) > 1
    assert all(0.0 <= d <= 1.5 for d in delays)


def test_a_response_reads_its_body_and_its_headers():
    response = Response(200, b'{"a":1}', {"Content-Type": "application/json"}, url="x")
    assert response.ok
    assert response.text == '{"a":1}'
    assert response.json() == {"a": 1}
    assert response.header("content-type") == "application/json"
    assert response.header("missing", "fallback") == "fallback"


def test_a_body_that_is_not_json_is_a_malformed_payload():
    with pytest.raises(MalformedPayload):
        Response(200, b"not json at all", url="x").json()


def test_retry_after_is_read_when_it_is_a_number():
    assert Response(429, b"", {"Retry-After": "12"}).retry_after_seconds() == 12.0
    assert Response(429, b"", {"Retry-After": "Wed, 21 Oct"}).retry_after_seconds() is None
    assert Response(429, b"").retry_after_seconds() is None


def test_a_response_reads_back_with_its_attempt_count():
    assert str(Response(200, b"", attempts=3, url="/x")) == "200 /x in 3 attempts"


def test_a_good_answer_comes_back_on_the_first_attempt():
    c = client(ok(b'{"fares":[]}'))
    response = c.get("/search")
    assert response.ok
    assert response.attempts == 1
    assert response.json() == {"fares": []}
    assert c.slept == []


def test_a_five_hundred_is_retried_and_then_succeeds():
    c = client(ok(status=503), ok(b"done"))
    response = c.get("/search")
    assert response.attempts == 2
    assert response.body == b"done"
    assert len(c.slept) == 1


def test_a_five_hundred_that_never_clears_gives_up_and_says_so():
    c = client(ok(status=503))
    with pytest.raises(SupplierUnavailable) as caught:
        c.get("/search")
    assert caught.value.details["attempts"] == 3
    assert len(c.recorder.calls) == 3


def test_a_throttle_that_never_clears_is_a_throttle_and_not_an_outage():
    c = client(ok(status=429))
    with pytest.raises(SupplierThrottled):
        c.get("/search")


def test_a_refusal_is_not_retried_at_all():
    c = client(ok(status=403))
    with pytest.raises(SupplierBlocked):
        c.get("/search")
    assert len(c.recorder.calls) == 1


def test_a_four_hundred_is_returned_rather_than_retried():
    c = client(ok(b"bad market", status=400))
    response = c.get("/search")
    assert response.status == 400
    assert not response.ok
    assert len(c.recorder.calls) == 1


def test_a_four_oh_four_is_an_answer_and_not_a_failure():
    c = client(ok(b"", status=404))
    assert c.get("/search").status == 404


def test_the_suppliers_own_retry_after_is_honoured_over_our_backoff():
    c = client(ok(status=429, headers={"Retry-After": "7"}), ok(b"done"))
    c.get("/search")
    assert c.slept == [7.0]


def test_a_retry_after_larger_than_the_cap_is_capped():
    c = client(
        ok(status=429, headers={"Retry-After": "9000"}),
        ok(b"done"),
        policy=RetryPolicy(attempts=2, max_delay=30.0),
    )
    c.get("/search")
    assert c.slept == [30.0]


def test_a_transport_failure_is_retried_and_then_reported():
    c = client(httpx.ConnectError("reset"))
    with pytest.raises(SupplierUnavailable) as caught:
        c.get("/search")
    assert caught.value.details["attempts"] == 3


def test_a_transport_failure_that_clears_returns_the_good_answer():
    c = client(httpx.ConnectError("reset"), ok(b"done"))
    assert c.get("/search").body == b"done"


def test_the_retryable_and_blocking_sets_do_not_overlap():
    assert not RETRYABLE_STATUS & BLOCKING_STATUS


def test_query_parameters_reach_the_supplier():
    c = client(ok())
    c.get("/search", params={"from": "FRA", "to": "JFK"})
    assert "from=FRA" in str(c.recorder.calls[0].url)


def test_a_post_carries_its_body():
    c = client(ok())
    c.post("/price", content=b"<request/>")
    assert c.recorder.calls[0].content == b"<request/>"


def test_default_headers_are_sent_on_every_call():
    c = client(ok(), headers={"User-Agent": "gussetline/1.0"})
    c.get("/search")
    assert c.recorder.calls[0].headers["user-agent"] == "gussetline/1.0"


def test_the_client_closes_cleanly_as_a_context_manager():
    with client(ok()) as c:
        assert c.get("/search").ok
