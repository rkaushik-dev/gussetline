from __future__ import annotations

import pytest

from gussetline.errors import (
    BudgetExhausted,
    CombinabilityError,
    ConfigError,
    ConstructionError,
    GussetlineError,
    MalformedPayload,
    NoFareFound,
    NoRuleFound,
    SupplierBlocked,
    SupplierError,
    SupplierThrottled,
    SupplierUnavailable,
    TariffError,
    is_retryable,
)


def test_message_alone_renders_plainly():
    err = GussetlineError("nothing to price")
    assert str(err) == "nothing to price"


def test_details_render_sorted_and_repr_quoted():
    err = GussetlineError("no fare", carrier="LH", origin="FRA")
    assert str(err) == "no fare (carrier='LH' origin='FRA')"


def test_as_dict_carries_the_code():
    err = NoFareFound("nothing published", market="FRA-JFK")
    assert err.as_dict() == {
        "code": "tariff.no_fare",
        "message": "nothing published",
        "details": {"market": "FRA-JFK"},
    }


def test_as_dict_details_are_a_copy():
    err = NoFareFound("nothing published", market="FRA-JFK")
    err.as_dict()["details"]["market"] = "LHR-JFK"
    assert err.details == {"market": "FRA-JFK"}


@pytest.mark.parametrize(
    ("cls", "code"),
    [
        (ConfigError, "config.invalid"),
        (TariffError, "tariff.error"),
        (NoFareFound, "tariff.no_fare"),
        (NoRuleFound, "tariff.no_rule"),
        (ConstructionError, "pricing.construction"),
        (CombinabilityError, "pricing.combinability"),
        (SupplierError, "supplier.error"),
        (SupplierUnavailable, "supplier.unavailable"),
        (SupplierThrottled, "supplier.throttled"),
        (SupplierBlocked, "supplier.blocked"),
        (MalformedPayload, "supplier.malformed"),
        (BudgetExhausted, "collector.budget_exhausted"),
    ],
)
def test_codes_are_stable(cls, code):
    assert cls("x").code == code


def test_subclasses_group_the_way_handlers_expect():
    assert issubclass(NoFareFound, TariffError)
    assert issubclass(CombinabilityError, ConstructionError)
    assert issubclass(SupplierThrottled, SupplierError)
    assert issubclass(SupplierError, GussetlineError)


def test_a_timeout_is_retryable_and_a_refusal_is_not():
    assert is_retryable(SupplierUnavailable("read timeout"))
    assert is_retryable(SupplierThrottled("429"))
    assert is_retryable(BudgetExhausted("day quota spent"))
    assert not is_retryable(SupplierBlocked("403 forever"))
    assert not is_retryable(MalformedPayload("not a record"))


def test_a_bad_market_is_never_retryable():
    assert not is_retryable(NoFareFound("nothing published"))
    assert not is_retryable(ConstructionError("no through fare"))


def test_unknown_exceptions_default_to_retryable_except_programmer_errors():
    assert is_retryable(OSError("connection reset"))
    assert is_retryable(RuntimeError("event loop closed"))
    assert not is_retryable(TypeError("str + int"))
    assert not is_retryable(KeyError("carrier"))
