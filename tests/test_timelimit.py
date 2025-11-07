from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.errors import ValidationError
from gussetline.timelimit import (
    Clock,
    falling_by,
    limit_for,
    soonest,
    survived,
    urgent,
)

BOOKED = date(2025, 11, 10)
DEPARTURE = date(2026, 3, 10)


def test_a_hold_sets_the_deadline_when_it_is_the_earliest():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert limit.on == date(2025, 11, 17)
    assert limit.clock is Clock.HOLD


def test_the_advance_purchase_backstop_wins_when_it_falls_first():
    # A booking four months out with a seven day hold and a twenty one day
    # backstop is ticketed within a week, not three months later.
    limit = limit_for(date(2026, 2, 1), DEPARTURE, hold_days=30, ticket_by_days_before=21)
    assert limit.on == date(2026, 2, 17)
    assert limit.clock is Clock.ADVANCE_PURCHASE


def test_the_hold_wins_when_it_is_the_shorter_of_the_two():
    limit = limit_for(date(2026, 2, 1), DEPARTURE, hold_days=3, ticket_by_days_before=21)
    assert limit.on == date(2026, 2, 4)
    assert limit.clock is Clock.HOLD


def test_the_departure_catches_a_booking_nobody_filed_a_rule_for():
    # The clock nobody files because nobody imagines needing it.
    limit = limit_for(date(2026, 3, 9), date(2026, 3, 10))
    assert limit.on == date(2026, 3, 10)
    assert limit.clock is Clock.DEPARTURE


def test_a_hold_longer_than_the_journey_still_ends_at_the_departure():
    limit = limit_for(BOOKED, date(2025, 11, 12), hold_days=30)
    assert limit.on == date(2025, 11, 12)
    assert limit.clock is Clock.DEPARTURE


def test_a_backstop_already_behind_us_does_not_fall_before_the_booking():
    limit = limit_for(date(2026, 3, 1), DEPARTURE, ticket_by_days_before=21)
    assert limit.on == date(2026, 3, 1)


def test_instant_purchase_beats_everything():
    # A booking that was never held has no deadline to miss.
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7, instant=True)
    assert limit.on == BOOKED
    assert limit.clock is Clock.IMMEDIATE


def test_a_tie_goes_to_the_rule_that_says_so():
    # An agent asking why a booking is due today wants the rule, not the
    # calendar fact that the flight is also today.
    limit = limit_for(date(2026, 3, 3), DEPARTURE, hold_days=7)
    assert limit.on == date(2026, 3, 10)
    assert limit.clock is Clock.HOLD


def test_a_deadline_falls_late_enough_to_leave_a_working_day():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert limit.at.hour == 23


def test_the_hour_can_be_named_by_a_carrier_that_says_so():
    assert limit_for(BOOKED, DEPARTURE, hold_days=7, hour=12).at.hour == 12


def test_an_hour_nobody_has_is_refused():
    with pytest.raises(ValidationError, match="hour of the day"):
        limit_for(BOOKED, DEPARTURE, hold_days=7, hour=25)


def test_a_booking_made_after_the_flight_is_refused():
    with pytest.raises(ValidationError, match="before it flies"):
        limit_for(date(2026, 4, 1), DEPARTURE)


def test_a_negative_hold_is_refused():
    with pytest.raises(ValidationError, match="hold is not negative"):
        limit_for(BOOKED, DEPARTURE, hold_days=-1)


def test_a_negative_backstop_is_refused():
    with pytest.raises(ValidationError, match="backstop is not negative"):
        limit_for(BOOKED, DEPARTURE, ticket_by_days_before=-1)


def test_a_hold_of_nothing_means_today():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=0)
    assert limit.on == BOOKED


def test_a_limit_reads_back_with_the_reason_and_not_only_the_date():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert str(limit) == "2025-11-17 23:00 by the hold on the booking"


def test_a_limit_counts_the_days_left():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert limit.days_left(BOOKED) == 7
    assert limit.days_left(date(2025, 11, 17)) == 0
    assert limit.days_left(date(2025, 11, 20)) == -3


def test_a_limit_knows_when_it_has_gone():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert not limit.expired_on(date(2025, 11, 17))
    assert limit.expired_on(date(2025, 11, 18))


def test_a_limit_knows_when_it_is_today():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert limit.is_today(date(2025, 11, 17))
    assert not limit.is_today(date(2025, 11, 16))


def test_the_departure_clock_is_the_only_one_nobody_filed():
    assert not Clock.DEPARTURE.is_filed
    assert Clock.HOLD.is_filed
    assert Clock.ADVANCE_PURCHASE.is_filed
    assert Clock.IMMEDIATE.is_filed


def test_something_due_tomorrow_is_urgent():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert urgent(limit, date(2025, 11, 16))
    assert not urgent(limit, date(2025, 11, 15))


def test_the_urgency_window_can_be_widened():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert urgent(limit, date(2025, 11, 14), within=3)


def test_a_booking_with_three_coupons_is_worked_to_the_earliest():
    # Reporting them all is how an agent pays for the wrong one.
    limits = [
        limit_for(BOOKED, DEPARTURE, hold_days=30),
        limit_for(BOOKED, DEPARTURE, hold_days=7),
        limit_for(BOOKED, DEPARTURE, hold_days=14),
    ]
    first = soonest(limits)
    assert first is not None
    assert first.on == date(2025, 11, 17)


def test_nothing_at_all_has_no_soonest():
    assert soonest([]) is None


def test_the_ones_falling_soon_come_back_soonest_first():
    limits = [
        limit_for(BOOKED, DEPARTURE, hold_days=14),
        limit_for(BOOKED, DEPARTURE, hold_days=1),
        limit_for(BOOKED, DEPARTURE, hold_days=2),
    ]
    due = falling_by(limits, date(2025, 11, 10), within=2)
    assert [limit.on for limit in due] == [date(2025, 11, 11), date(2025, 11, 12)]


def test_nothing_falling_soon_is_an_empty_list():
    limits = [limit_for(BOOKED, DEPARTURE, hold_days=30)]
    assert falling_by(limits, BOOKED) == []


def test_a_booking_ticketed_in_time_survived():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert survived(limit, datetime(2025, 11, 17, 10, 0))


def test_a_booking_ticketed_a_minute_late_did_not():
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert not survived(limit, datetime(2025, 11, 17, 23, 1))


def test_a_booking_with_no_ticket_at_all_did_not_survive():
    # Whatever the desk's own records still say, the airline has cancelled it.
    limit = limit_for(BOOKED, DEPARTURE, hold_days=7)
    assert not survived(limit, None)
