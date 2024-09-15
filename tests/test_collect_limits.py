from __future__ import annotations

from datetime import date

import pytest

from gussetline.collect.limits import DailyQuota, HostBudget, Limiter, TokenBucket
from gussetline.errors import BudgetExhausted, ValidationError

TODAY = date(2025, 5, 6)
TOMORROW = date(2025, 5, 7)


class Clock:
    """A clock the test moves by hand."""

    def __init__(self, start: float = 0.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_a_bucket_needs_a_positive_rate_and_a_real_burst():
    clock = Clock()
    with pytest.raises(ValidationError):
        TokenBucket(0, 5, clock)
    with pytest.raises(ValidationError):
        TokenBucket(1.0, 0, clock)


def test_a_bucket_starts_full():
    assert TokenBucket(1.0, 5, Clock()).tokens == 5.0


def test_taking_drains_the_bucket():
    bucket = TokenBucket(1.0, 3, Clock())
    assert bucket.take()
    assert bucket.take()
    assert bucket.take()
    assert not bucket.take()


def test_the_bucket_refills_at_its_rate():
    clock = Clock()
    bucket = TokenBucket(2.0, 4, clock)
    for _ in range(4):
        bucket.take()
    assert not bucket.take()
    clock.advance(0.5)
    assert bucket.take()
    assert not bucket.take()


def test_the_bucket_does_not_fill_past_its_burst():
    clock = Clock()
    bucket = TokenBucket(1.0, 3, clock)
    clock.advance(1000)
    assert bucket.tokens == 3.0


def test_taking_nothing_is_not_a_request():
    with pytest.raises(ValidationError):
        TokenBucket(1.0, 3, Clock()).take(0)


def test_a_request_bigger_than_the_bucket_can_never_be_served():
    with pytest.raises(ValidationError):
        TokenBucket(1.0, 3, Clock()).take(4)


def test_the_wait_is_zero_when_the_tokens_are_there():
    assert TokenBucket(1.0, 3, Clock()).wait_for() == 0.0


def test_the_wait_is_the_time_the_refill_needs():
    clock = Clock()
    bucket = TokenBucket(2.0, 2, clock)
    bucket.take(2)
    assert bucket.wait_for() == pytest.approx(0.5)


def test_a_bucket_reads_back_with_its_rate():
    assert str(TokenBucket(1.0, 5, Clock())) == "5.0 of 5 tokens at 1.0/s"


def test_a_quota_is_not_negative():
    with pytest.raises(ValidationError):
        DailyQuota(limit=-1)


def test_a_quota_counts_down_and_then_refuses():
    quota = DailyQuota(limit=2)
    quota.spend(TODAY)
    quota.spend(TODAY)
    assert quota.exhausted
    with pytest.raises(BudgetExhausted) as caught:
        quota.spend(TODAY)
    assert caught.value.details == {"limit": 2, "used": 2, "wanted": 1}


def test_a_quota_rolls_over_at_midnight():
    quota = DailyQuota(limit=2)
    quota.spend(TODAY, 2)
    assert quota.exhausted
    quota.roll_to(TOMORROW)
    assert quota.remaining == 2


def test_rolling_to_the_same_day_twice_does_not_reset_it():
    quota = DailyQuota(limit=5)
    quota.spend(TODAY, 3)
    quota.roll_to(TODAY)
    quota.roll_to(TODAY)
    assert quota.used == 3


def test_spending_more_than_is_left_is_refused_and_nothing_is_taken():
    quota = DailyQuota(limit=5)
    quota.spend(TODAY, 3)
    with pytest.raises(BudgetExhausted):
        quota.spend(TODAY, 3)
    assert quota.used == 3


def test_a_quota_reads_back_as_used_over_limit():
    quota = DailyQuota(limit=5)
    quota.spend(TODAY, 2)
    assert str(quota) == "2 of 5 used"


def budget(rate=1.0, burst=2, quota=3, clock=None) -> HostBudget:
    clock = clock if clock is not None else Clock()
    return HostBudget(
        host="supplier.example",
        bucket=TokenBucket(rate, burst, clock),
        quota=DailyQuota(limit=quota),
    )


def test_a_host_budget_allows_until_the_rate_runs_out():
    b = budget(burst=2, quota=10)
    assert b.allow(TODAY)
    assert b.allow(TODAY)
    assert not b.allow(TODAY)
    assert b.last_reason == "rate"
    assert b.refusals == 1


def test_a_host_budget_allows_until_the_quota_runs_out():
    b = budget(burst=10, quota=2)
    assert b.allow(TODAY)
    assert b.allow(TODAY)
    assert not b.allow(TODAY)
    assert b.last_reason == "quota"


def test_the_quota_is_checked_before_the_rate_so_a_token_is_not_wasted():
    clock = Clock()
    b = budget(burst=5, quota=0, clock=clock)
    assert not b.allow(TODAY)
    assert b.last_reason == "quota"
    assert b.bucket.tokens == 5.0


def test_waiting_helps_a_rate_refusal_and_not_a_quota_one():
    clock = Clock()
    rated = budget(rate=1.0, burst=1, quota=10, clock=clock)
    rated.allow(TODAY)
    assert rated.wait() > 0

    spent = budget(burst=10, quota=1)
    spent.allow(TODAY)
    assert spent.wait() == 0.0


def test_a_host_budget_reads_back_with_both_budgets():
    b = budget()
    assert b.host in str(b)
    assert "tokens" in str(b)
    assert "used" in str(b)


def test_a_limiter_makes_a_default_budget_for_a_host_it_has_not_seen():
    limiter = Limiter(Clock(), default_burst=1, default_quota=5)
    assert limiter.allow("one.example", TODAY)
    assert not limiter.allow("one.example", TODAY)
    assert limiter.known_hosts() == ["one.example"]


def test_hosts_do_not_share_a_budget():
    limiter = Limiter(Clock(), default_burst=1)
    assert limiter.allow("one.example", TODAY)
    assert limiter.allow("two.example", TODAY)
    assert not limiter.allow("one.example", TODAY)


def test_a_host_can_be_configured_more_tightly_than_the_default():
    limiter = Limiter(Clock(), default_burst=10)
    limiter.configure("slow.example", burst=1, quota=100)
    assert limiter.allow("slow.example", TODAY)
    assert not limiter.allow("slow.example", TODAY)


def test_refusals_are_counted_per_host_and_only_when_there_are_any():
    limiter = Limiter(Clock(), default_burst=1)
    limiter.allow("one.example", TODAY)
    limiter.allow("one.example", TODAY)
    limiter.allow("two.example", TODAY)
    assert limiter.refused() == {"one.example": 1}
