from __future__ import annotations

import pytest

from gussetline.collect.breaker import Breaker, BreakerBoard, State
from gussetline.errors import SupplierUnavailable, ValidationError


class Clock:
    def __init__(self, start: float = 0.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def breaker(threshold=3, cooldown=60.0, clock=None) -> Breaker:
    return Breaker(name="gds", threshold=threshold, cooldown=cooldown, clock=clock or Clock())


def test_a_breaker_needs_a_real_threshold_and_cooldown():
    with pytest.raises(ValidationError):
        Breaker(name="x", threshold=0)
    with pytest.raises(ValidationError):
        Breaker(name="x", cooldown=-1)


def test_a_fresh_breaker_is_closed_and_lets_everything_through():
    b = breaker()
    assert b.state is State.CLOSED
    assert b.allow()
    assert b.allow()


def test_failures_under_the_threshold_do_not_trip_it():
    b = breaker(threshold=3)
    b.failed()
    b.failed()
    assert b.state is State.CLOSED
    assert b.allow()


def test_a_run_of_failures_trips_it():
    b = breaker(threshold=3)
    for _ in range(3):
        b.failed()
    assert b.is_open
    assert not b.allow()
    assert b.trips == 1


def test_a_success_clears_the_run():
    b = breaker(threshold=3)
    b.failed()
    b.failed()
    b.succeeded()
    b.failed()
    assert b.state is State.CLOSED


def test_an_open_breaker_stays_shut_until_the_cooldown_passes():
    clock = Clock()
    b = breaker(threshold=1, cooldown=60.0, clock=clock)
    b.failed()
    assert not b.allow()
    clock.advance(59)
    assert not b.allow()
    clock.advance(1)
    assert b.allow()


def test_the_cooldown_lets_exactly_one_probe_through():
    clock = Clock()
    b = breaker(threshold=1, cooldown=10.0, clock=clock)
    b.failed()
    clock.advance(10)
    assert b.allow()
    assert b.state is State.HALF_OPEN
    assert not b.allow()
    assert b.probes == 1


def test_a_probe_that_works_closes_the_breaker():
    clock = Clock()
    b = breaker(threshold=1, cooldown=10.0, clock=clock)
    b.failed()
    clock.advance(10)
    b.allow()
    b.succeeded()
    assert b.state is State.CLOSED
    assert b.allow()
    assert b.allow()


def test_a_probe_that_fails_reopens_for_another_cooldown():
    clock = Clock()
    b = breaker(threshold=1, cooldown=10.0, clock=clock)
    b.failed()
    clock.advance(10)
    b.allow()
    b.failed()
    assert b.is_open
    assert b.trips == 2
    assert not b.allow()
    clock.advance(10)
    assert b.allow()


def test_a_failing_probe_does_not_need_the_threshold_again():
    clock = Clock()
    b = breaker(threshold=5, cooldown=10.0, clock=clock)
    for _ in range(5):
        b.failed()
    clock.advance(10)
    b.allow()
    b.failed()
    assert b.is_open


def test_a_zero_cooldown_reopens_immediately_for_a_probe():
    b = breaker(threshold=1, cooldown=0.0)
    b.failed()
    assert b.allow()


def test_the_guard_raises_rather_than_answering():
    b = breaker(threshold=1)
    b.failed()
    with pytest.raises(SupplierUnavailable) as caught:
        b.guard()
    assert caught.value.details["supplier"] == "gds"


def test_the_guard_is_quiet_while_the_breaker_is_closed():
    breaker().guard()


def test_a_breaker_reads_back_with_its_state():
    b = breaker(threshold=3)
    b.failed()
    assert str(b) == "gds closed after 1 failures"


def test_a_board_makes_a_breaker_per_supplier():
    board = BreakerBoard(Clock(), threshold=1)
    assert board.allow("gds")
    board.failed("gds")
    assert not board.allow("gds")
    assert board.allow("ndc")
    assert len(board) == 2


def test_a_board_lists_the_suppliers_that_are_out():
    board = BreakerBoard(Clock(), threshold=1)
    board.failed("gds")
    board.failed("calendar")
    board.succeeded("ndc")
    assert board.open_suppliers() == ["calendar", "gds"]


def test_a_board_summarises_every_breaker_it_holds():
    board = BreakerBoard(Clock(), threshold=1)
    board.failed("gds")
    board.succeeded("ndc")
    assert board.summary() == {"gds": "open", "ndc": "closed"}
