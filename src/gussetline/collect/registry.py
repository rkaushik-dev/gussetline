"""The supplier register, and what each one is actually good for.

Three suppliers and none of them does the same job. The pricing gateway shops
and costs money per call. The offer feed shops and is free but covers a third
of the markets. The calendar quotes and cannot be audited at all.

A collector that treats them as interchangeable spends gateway calls on
markets the feed already covered and asks the calendar for things it cannot
answer. So the register holds what each one can do, and the scheduler reads
that rather than hard coding the names.

The other thing here is the failure classification. Every exception the
collectors can raise maps onto one of four outcomes, and the outcome decides
what happens next: retry later, stop for the day, stop until somebody looks,
or record and carry on. Doing that at the call site means three copies that
drift, which is how a blocked supplier ended up being retried for two days.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Any

from gussetline.errors import (
    BudgetExhausted,
    GussetlineError,
    MalformedPayload,
    SupplierBlocked,
    SupplierError,
    SupplierThrottled,
    SupplierUnavailable,
    ValidationError,
)


class Capability(str, Enum):
    """What a supplier can be asked for."""

    SHOP = "shop"
    QUOTE = "quote"
    REPRICE = "reprice"
    TAX_BREAKDOWN = "tax_breakdown"
    SEAT_COUNTS = "seat_counts"


class Outcome(str, Enum):
    """What a call did, in the terms the run report is written in."""

    OK = "ok"
    TRANSIENT = "transient"
    THROTTLED = "throttled"
    BLOCKED = "blocked"
    MALFORMED = "malformed"
    BUDGET = "budget"
    REFUSED = "refused"

    @property
    def retry_later(self) -> bool:
        return self in (Outcome.TRANSIENT, Outcome.THROTTLED)

    @property
    def stops_the_supplier(self) -> bool:
        return self in (Outcome.BLOCKED, Outcome.BUDGET)


def classify(exc: BaseException | None) -> Outcome:
    """Turn any failure into the one word the run report needs.

    A malformed payload is deliberately not transient. Trying it again gets
    the same bytes and the same parser, and a collector that retries a parse
    failure burns its whole budget on one broken market.
    """
    if exc is None:
        return Outcome.OK
    if isinstance(exc, SupplierThrottled):
        return Outcome.THROTTLED
    if isinstance(exc, SupplierBlocked):
        return Outcome.BLOCKED
    if isinstance(exc, MalformedPayload):
        return Outcome.MALFORMED
    if isinstance(exc, BudgetExhausted):
        return Outcome.BUDGET
    if isinstance(exc, SupplierUnavailable | SupplierError):
        return Outcome.TRANSIENT
    if isinstance(exc, ValidationError):
        return Outcome.REFUSED
    if isinstance(exc, GussetlineError):
        return Outcome.REFUSED
    return Outcome.TRANSIENT


@dataclass(frozen=True, slots=True)
class Supplier:
    """One source, what it can do, and what it costs to ask."""

    name: str
    host: str
    path: str = "/"
    capabilities: frozenset[Capability] = field(default_factory=frozenset)
    cost_per_call: Decimal = Decimal(0)
    markets: frozenset[str] = field(default_factory=frozenset)
    parser: Callable[[bytes, datetime], Any] | None = None
    user_agent: str = "gussetline/1.0"

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValidationError("a supplier has a name")
        object.__setattr__(self, "name", self.name.strip().lower())
        if self.cost_per_call < 0:
            raise ValidationError("a call does not pay us", cost=str(self.cost_per_call))

    def can(self, capability: Capability) -> bool:
        return capability in self.capabilities

    def covers(self, market: str) -> bool:
        """Whether this supplier answers for a market at all.

        An empty market set means everything, because two of the three
        suppliers do not publish a list and refusing them would leave one.
        """
        if not self.markets:
            return True
        return market.upper() in self.markets

    @property
    def is_free(self) -> bool:
        return self.cost_per_call == 0

    def __str__(self) -> str:
        able = "/".join(sorted(c.value for c in self.capabilities)) or "nothing"
        return f"{self.name} at {self.host} does {able}"


class Registry:
    """Every supplier the desk has, and the questions it can answer."""

    def __init__(self) -> None:
        self._suppliers: dict[str, Supplier] = {}

    def add(self, supplier: Supplier) -> Supplier:
        self._suppliers[supplier.name] = supplier
        return supplier

    def get(self, name: str) -> Supplier:
        key = name.strip().lower()
        if key not in self._suppliers:
            raise ValidationError("no such supplier", name=key)
        return self._suppliers[key]

    def names(self) -> list[str]:
        return sorted(self._suppliers)

    def able_to(self, capability: Capability) -> list[Supplier]:
        return [s for s in self._suppliers.values() if s.can(capability)]

    def for_market(self, market: str, capability: Capability) -> list[Supplier]:
        """Who can answer for this market, free ones first.

        Free first because a market both cover should be asked of the feed
        and not of the gateway, and the only reason it ever went the other
        way round was that the gateway happened to be added first.
        """
        able = [s for s in self.able_to(capability) if s.covers(market)]
        return sorted(able, key=lambda s: (s.cost_per_call, s.name))

    def cheapest_for(self, market: str, capability: Capability) -> Supplier | None:
        found = self.for_market(market, capability)
        return found[0] if found else None

    def __len__(self) -> int:
        return len(self._suppliers)


def default_registry() -> Registry:
    """The three suppliers the desk actually has."""
    registry = Registry()
    registry.add(
        Supplier(
            name="gds",
            host="gateway.example",
            path="/pricing",
            capabilities=frozenset(
                {
                    Capability.SHOP,
                    Capability.REPRICE,
                    Capability.TAX_BREAKDOWN,
                    Capability.SEAT_COUNTS,
                }
            ),
            cost_per_call=Decimal("0.04"),
        )
    )
    registry.add(
        Supplier(
            name="ndc",
            host="offers.example",
            path="/v2/offers",
            capabilities=frozenset(
                {Capability.SHOP, Capability.TAX_BREAKDOWN, Capability.SEAT_COUNTS}
            ),
            markets=frozenset({"FRA-JFK", "FRA-ORD", "MUC-JFK", "FRA-LHR", "FRA-SIN"}),
        )
    )
    registry.add(
        Supplier(
            name="calendar",
            host="fares.example",
            path="/lowfare",
            capabilities=frozenset({Capability.QUOTE}),
        )
    )
    return registry
