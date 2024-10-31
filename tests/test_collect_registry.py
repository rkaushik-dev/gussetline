from __future__ import annotations

from decimal import Decimal

import pytest

from gussetline.collect.registry import (
    Capability,
    Outcome,
    Registry,
    Supplier,
    classify,
    default_registry,
)
from gussetline.errors import (
    BudgetExhausted,
    ConstructionError,
    MalformedPayload,
    NoFareFound,
    SupplierBlocked,
    SupplierThrottled,
    SupplierUnavailable,
    ValidationError,
)


def test_nothing_wrong_is_ok():
    assert classify(None) is Outcome.OK


def test_each_failure_maps_to_its_own_outcome():
    assert classify(SupplierThrottled("429")) is Outcome.THROTTLED
    assert classify(SupplierBlocked("403")) is Outcome.BLOCKED
    assert classify(MalformedPayload("not json")) is Outcome.MALFORMED
    assert classify(BudgetExhausted("spent")) is Outcome.BUDGET
    assert classify(SupplierUnavailable("timeout")) is Outcome.TRANSIENT


def test_a_malformed_payload_is_not_transient():
    # Retrying gets the same bytes and the same parser.
    assert not classify(MalformedPayload("not json")).retry_later


def test_our_own_refusals_are_neither_transient_nor_blocking():
    assert classify(NoFareFound("nothing published")) is Outcome.REFUSED
    assert classify(ConstructionError("no through fare")) is Outcome.REFUSED
    assert classify(ValidationError("bad market")) is Outcome.REFUSED


def test_something_we_have_never_seen_is_treated_as_transient():
    assert classify(OSError("connection reset")) is Outcome.TRANSIENT
    assert classify(RuntimeError("event loop closed")) is Outcome.TRANSIENT


def test_the_two_outcomes_worth_retrying_are_marked():
    assert Outcome.TRANSIENT.retry_later
    assert Outcome.THROTTLED.retry_later
    assert not Outcome.BLOCKED.retry_later
    assert not Outcome.OK.retry_later


def test_the_two_outcomes_that_stop_a_supplier_are_marked():
    assert Outcome.BLOCKED.stops_the_supplier
    assert Outcome.BUDGET.stops_the_supplier
    assert not Outcome.TRANSIENT.stops_the_supplier


def test_a_supplier_has_a_name():
    with pytest.raises(ValidationError):
        Supplier(name="  ", host="x.example")
    assert Supplier(name=" GDS ", host="x.example").name == "gds"


def test_a_call_does_not_pay_us():
    with pytest.raises(ValidationError):
        Supplier(name="gds", host="x.example", cost_per_call=Decimal("-1"))


def test_a_supplier_says_what_it_can_do():
    supplier = Supplier(name="gds", host="x.example", capabilities=frozenset({Capability.SHOP}))
    assert supplier.can(Capability.SHOP)
    assert not supplier.can(Capability.QUOTE)


def test_a_supplier_with_no_market_list_covers_everything():
    supplier = Supplier(name="gds", host="x.example")
    assert supplier.covers("FRA-JFK")
    assert supplier.covers("anything")


def test_a_supplier_with_a_market_list_covers_only_those():
    supplier = Supplier(name="ndc", host="x.example", markets=frozenset({"FRA-JFK"}))
    assert supplier.covers("fra-jfk")
    assert not supplier.covers("FRA-BKK")


def test_a_free_supplier_is_marked():
    assert Supplier(name="ndc", host="x.example").is_free
    assert not Supplier(name="gds", host="x.example", cost_per_call=Decimal("0.04")).is_free


def test_a_supplier_reads_back_with_what_it_does():
    supplier = Supplier(
        name="gds",
        host="gateway.example",
        capabilities=frozenset({Capability.SHOP, Capability.QUOTE}),
    )
    assert str(supplier) == "gds at gateway.example does quote/shop"
    assert str(Supplier(name="x", host="h")) == "x at h does nothing"


def test_the_registry_finds_a_supplier_by_name():
    registry = Registry()
    registry.add(Supplier(name="gds", host="x.example"))
    assert registry.get("GDS").name == "gds"
    assert registry.names() == ["gds"]
    assert len(registry) == 1


def test_a_supplier_we_do_not_hold_is_refused():
    with pytest.raises(ValidationError):
        Registry().get("nobody")


def test_suppliers_can_be_listed_by_capability():
    registry = default_registry()
    assert sorted(s.name for s in registry.able_to(Capability.SHOP)) == ["gds", "ndc"]
    assert [s.name for s in registry.able_to(Capability.QUOTE)] == ["calendar"]


def test_a_market_both_shop_asks_the_free_one_first():
    registry = default_registry()
    order = [s.name for s in registry.for_market("FRA-JFK", Capability.SHOP)]
    assert order == ["ndc", "gds"]
    cheapest = registry.cheapest_for("FRA-JFK", Capability.SHOP)
    assert cheapest is not None
    assert cheapest.name == "ndc"


def test_a_market_only_one_covers_falls_to_that_one():
    registry = default_registry()
    order = [s.name for s in registry.for_market("FRA-BKK", Capability.SHOP)]
    assert order == ["gds"]


def test_a_capability_nobody_has_answers_empty():
    registry = default_registry()
    assert registry.for_market("FRA-JFK", Capability.REPRICE)[0].name == "gds"
    assert Registry().cheapest_for("FRA-JFK", Capability.SHOP) is None


def test_the_shipped_registry_holds_the_three_suppliers():
    registry = default_registry()
    assert registry.names() == ["calendar", "gds", "ndc"]
    assert registry.get("gds").cost_per_call == Decimal("0.04")
    assert registry.get("ndc").is_free
    assert not registry.get("calendar").can(Capability.SHOP)
