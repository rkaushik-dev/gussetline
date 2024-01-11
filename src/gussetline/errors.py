"""The error taxonomy.

Everything the desk sees eventually turns into one of these. Each carries a
short stable code because the codes end up in the audit trail, in the http
error body and in the reliability report, and renaming one of those means
rewriting history that has already been signed off.

The split that matters is between an error that says the input was wrong and
an error that says we could not answer. A supplier that times out is not a bad
market and must not be reported as one, or the coverage report quietly starts
blaming the analyst for the network.
"""

from __future__ import annotations

from typing import Any


class GussetlineError(Exception):
    """Base for everything raised inside the package."""

    code = "gussetline.error"
    retryable = False

    def __init__(self, message: str, **details: Any) -> None:
        super().__init__(message)
        self.message = message
        self.details: dict[str, Any] = dict(details)

    def __str__(self) -> str:
        if not self.details:
            return self.message
        rendered = " ".join(f"{k}={v!r}" for k, v in sorted(self.details.items()))
        return f"{self.message} ({rendered})"

    def as_dict(self) -> dict[str, Any]:
        """The shape the http layer and the audit trail both write."""
        return {"code": self.code, "message": self.message, "details": dict(self.details)}


class ConfigError(GussetlineError):
    """A setting is missing, unparseable or contradicts another setting."""

    code = "config.invalid"


class ValidationError(GussetlineError):
    """Input did not describe a thing the desk can work with."""

    code = "input.invalid"


class NotFoundError(GussetlineError):
    """A record the caller named does not exist."""

    code = "record.not_found"


class ConflictError(GussetlineError):
    """The write would contradict a record that is already there."""

    code = "record.conflict"


class TariffError(GussetlineError):
    """The tariff cannot answer the question asked of it."""

    code = "tariff.error"


class NoFareFound(TariffError):
    """No published fare covers the market, carrier and dates given."""

    code = "tariff.no_fare"


class NoRuleFound(TariffError):
    """A fare names a rule the rule store does not hold."""

    code = "tariff.no_rule"


class MileageUnavailable(TariffError):
    """No maximum permitted mileage is published for the pair."""

    code = "tariff.no_mileage"


class ConstructionError(GussetlineError):
    """The fare could not be built out of the components offered."""

    code = "pricing.construction"


class CombinabilityError(ConstructionError):
    """Two components that must join are not allowed to."""

    code = "pricing.combinability"


class SupplierError(GussetlineError):
    """The far side did not give us an answer we can use."""

    code = "supplier.error"


class SupplierUnavailable(SupplierError):
    """Transport level failure. Nothing was learned about the market."""

    code = "supplier.unavailable"
    retryable = True


class SupplierThrottled(SupplierError):
    """The supplier said slow down, and it is entitled to."""

    code = "supplier.throttled"
    retryable = True


class SupplierBlocked(SupplierError):
    """The supplier refused us rather than the request. Stop asking."""

    code = "supplier.blocked"


class MalformedPayload(SupplierError):
    """A response arrived and did not parse as the format it claimed."""

    code = "supplier.malformed"


class BudgetExhausted(GussetlineError):
    """The politeness or quota budget for this window is spent."""

    code = "collector.budget_exhausted"
    retryable = True


def is_retryable(exc: BaseException) -> bool:
    """True when trying the same call again could plausibly succeed.

    Anything that is not one of ours is treated as retryable, because an
    unrecognised failure from httpx or the standard library is far more often
    a socket than a logic error, and a collector that gives up on the first
    unknown exception stops collecting the moment a supplier changes a header.
    """
    if isinstance(exc, GussetlineError):
        return exc.retryable
    return not isinstance(exc, TypeError | ValueError | AttributeError | KeyError)
