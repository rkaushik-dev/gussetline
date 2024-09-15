"""Rate limiting, per host and per day, on a clock we control.

Two different budgets and they run out for different reasons.

The token bucket is politeness. It refills at a steady rate and holds a small
burst, which is what a supplier's own limiter is doing on the other side, so
matching the shape means we are throttled by our own code rather than by
theirs. Being throttled by theirs costs a 429, and enough 429s costs the
account.

The daily quota is commercial. Suppliers sell a number of shopping requests a
day and the desk pays for them, so a collector that burns the quota on a
market nobody is watching has spent real money. When it runs out the answer
is not to wait, it is to stop until midnight, which is why the two are
separate objects and not one with a flag.

Time comes from an injected clock everywhere. A limiter that reads the wall
clock cannot be tested without sleeping and cannot be replayed at all.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date

from gussetline.errors import BudgetExhausted, ValidationError


class TokenBucket:
    """Steady refill, small burst, one per host."""

    def __init__(
        self,
        rate_per_second: float,
        burst: int,
        clock: Callable[[], float],
    ) -> None:
        if rate_per_second <= 0:
            raise ValidationError("a refill rate is positive", rate=rate_per_second)
        if burst < 1:
            raise ValidationError("a bucket holds at least one token", burst=burst)
        self.rate = rate_per_second
        self.burst = burst
        self._clock = clock
        self._tokens = float(burst)
        self._last = clock()

    def _refill(self) -> None:
        now = self._clock()
        elapsed = max(0.0, now - self._last)
        self._last = now
        self._tokens = min(float(self.burst), self._tokens + elapsed * self.rate)

    @property
    def tokens(self) -> float:
        self._refill()
        return self._tokens

    def take(self, count: int = 1) -> bool:
        """Take tokens if they are there. False means wait, not never."""
        if count < 1:
            raise ValidationError("taking nothing is not a request", count=count)
        if count > self.burst:
            raise ValidationError(
                "a request larger than the whole bucket can never be served",
                count=count,
                burst=self.burst,
            )
        self._refill()
        if self._tokens < count:
            return False
        self._tokens -= count
        return True

    def wait_for(self, count: int = 1) -> float:
        """How long until the tokens exist. Zero when they already do."""
        self._refill()
        if self._tokens >= count:
            return 0.0
        return (count - self._tokens) / self.rate

    def __str__(self) -> str:
        return f"{self.tokens:.1f} of {self.burst} tokens at {self.rate}/s"


@dataclass
class DailyQuota:
    """How many requests a supplier has sold us today."""

    limit: int
    used: int = 0
    day: date | None = None

    def __post_init__(self) -> None:
        if self.limit < 0:
            raise ValidationError("a quota is not negative", limit=self.limit)

    @property
    def remaining(self) -> int:
        return max(0, self.limit - self.used)

    @property
    def exhausted(self) -> bool:
        return self.remaining == 0

    def roll_to(self, today: date) -> None:
        """Start a new day. Idempotent, so it can be called on every request."""
        if self.day != today:
            self.day = today
            self.used = 0

    def spend(self, today: date, count: int = 1) -> None:
        self.roll_to(today)
        if count > self.remaining:
            raise BudgetExhausted(
                "the day's quota is spent",
                limit=self.limit,
                used=self.used,
                wanted=count,
            )
        self.used += count

    def __str__(self) -> str:
        return f"{self.used} of {self.limit} used"


@dataclass
class HostBudget:
    """Both budgets for one host, and the reason it last refused."""

    host: str
    bucket: TokenBucket
    quota: DailyQuota
    refusals: int = 0
    last_reason: str = ""

    def allow(self, today: date) -> bool:
        """Whether one request may go out right now."""
        self.quota.roll_to(today)
        if self.quota.exhausted:
            self.refusals += 1
            self.last_reason = "quota"
            return False
        if not self.bucket.take():
            self.refusals += 1
            self.last_reason = "rate"
            return False
        self.quota.spend(today)
        return True

    def wait(self) -> float:
        """How long to wait, or zero when waiting will not help."""
        if self.quota.exhausted:
            return 0.0
        return self.bucket.wait_for()

    def __str__(self) -> str:
        return f"{self.host} {self.bucket} {self.quota}"


class Limiter:
    """Budgets for every host the collectors talk to."""

    def __init__(
        self,
        clock: Callable[[], float],
        *,
        default_rate: float = 1.0,
        default_burst: int = 5,
        default_quota: int = 1000,
    ) -> None:
        self._clock = clock
        self.default_rate = default_rate
        self.default_burst = default_burst
        self.default_quota = default_quota
        self._hosts: dict[str, HostBudget] = {}

    def configure(
        self,
        host: str,
        *,
        rate: float | None = None,
        burst: int | None = None,
        quota: int | None = None,
    ) -> HostBudget:
        budget = HostBudget(
            host=host,
            bucket=TokenBucket(
                rate if rate is not None else self.default_rate,
                burst if burst is not None else self.default_burst,
                self._clock,
            ),
            quota=DailyQuota(quota if quota is not None else self.default_quota),
        )
        self._hosts[host] = budget
        return budget

    def budget_for(self, host: str) -> HostBudget:
        if host not in self._hosts:
            return self.configure(host)
        return self._hosts[host]

    def allow(self, host: str, today: date) -> bool:
        return self.budget_for(host).allow(today)

    def refused(self) -> dict[str, int]:
        return {host: b.refusals for host, b in self._hosts.items() if b.refusals}

    def known_hosts(self) -> list[str]:
        return sorted(self._hosts)
