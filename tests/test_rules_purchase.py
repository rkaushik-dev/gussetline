from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Category, Context, evaluate, permitted
from gussetline.rules.purchase import AdvancePurchase, InstantPurchase
from gussetline.trace import Stage, Trace

DEPARTURE = date(2025, 3, 4)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def context(ticketing: date) -> Context:
    return Context(
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")]
        ),
        component=out(),
        ticketing=ticketing,
    )


def test_a_filing_with_nothing_in_it_does_not_apply():
    outcome = AdvancePurchase().evaluate(context(date(2025, 3, 3)))
    assert not outcome.applied
    assert outcome.category is Category.ADVANCE_RESERVATION


def test_a_negative_period_is_refused():
    with pytest.raises(ValidationError):
        AdvancePurchase(reservation_days=-1)
    with pytest.raises(ValidationError):
        AdvancePurchase(ticketing_days_before_departure=-1)
    with pytest.raises(ValidationError):
        InstantPurchase(hours=-1)


def test_the_latest_reservation_counts_back_from_departure():
    assert AdvancePurchase(reservation_days=7).latest_reservation(DEPARTURE) == date(2025, 2, 25)
    assert AdvancePurchase().latest_reservation(DEPARTURE) is None


def test_a_booking_inside_the_window_passes():
    rule = AdvancePurchase(reservation_days=7)
    assert rule.evaluate(context(date(2025, 2, 20))).answer.value == "passes"


def test_a_booking_on_the_deadline_passes():
    rule = AdvancePurchase(reservation_days=7)
    assert not rule.evaluate(context(date(2025, 2, 25))).failed


def test_a_booking_after_the_deadline_fails_and_says_by_how_much():
    rule = AdvancePurchase(reservation_days=7)
    outcome = rule.evaluate(context(date(2025, 3, 1)))
    assert outcome.failed
    assert outcome.details == {"needed": 7, "had": 3}


def test_the_ticketing_deadline_takes_the_earlier_of_the_two():
    rule = AdvancePurchase(ticketing_days_after_reservation=3, ticketing_days_before_departure=7)
    # Booked six months out, so the three day hold is the binding one.
    assert rule.ticketing_deadline(DEPARTURE, date(2024, 9, 1)) == date(2024, 9, 4)
    # Booked eight days out, so the seven days before departure binds instead.
    assert rule.ticketing_deadline(DEPARTURE, date(2025, 2, 24)) == date(2025, 2, 25)


def test_one_deadline_filed_alone_is_the_deadline():
    after = AdvancePurchase(ticketing_days_after_reservation=3)
    assert after.ticketing_deadline(DEPARTURE, date(2025, 1, 1)) == date(2025, 1, 4)
    before = AdvancePurchase(ticketing_days_before_departure=7)
    assert before.ticketing_deadline(DEPARTURE, date(2025, 1, 1)) == date(2025, 2, 25)


def test_a_hold_deadline_with_no_booking_date_is_ignored():
    rule = AdvancePurchase(ticketing_days_after_reservation=3)
    assert rule.ticketing_deadline(DEPARTURE, None) is None


def test_no_ticketing_deadline_filed_gives_none():
    assert AdvancePurchase(reservation_days=7).ticketing_deadline(DEPARTURE, DEPARTURE) is None


def test_a_late_ticket_fails_on_the_binding_deadline():
    rule = AdvancePurchase(
        ticketing_days_after_reservation=3,
        ticketing_days_before_departure=7,
        booked_on=date(2025, 2, 24),
    )
    outcome = rule.evaluate(context(date(2025, 2, 27)))
    assert outcome.failed
    assert outcome.details["deadline"] == "2025-02-25"


def test_the_same_ticket_passes_when_it_was_booked_early_enough():
    rule = AdvancePurchase(
        ticketing_days_after_reservation=3,
        ticketing_days_before_departure=7,
        booked_on=date(2025, 2, 20),
    )
    assert not rule.evaluate(context(date(2025, 2, 22))).failed


def test_the_booking_date_falls_back_to_the_ticketing_date():
    rule = AdvancePurchase(reservation_days=7)
    assert rule.evaluate(context(date(2025, 3, 1))).failed
    held = AdvancePurchase(reservation_days=7, booked_on=date(2025, 2, 1))
    assert not held.evaluate(context(date(2025, 3, 1))).failed


def test_a_passing_outcome_carries_the_deadline_it_used():
    rule = AdvancePurchase(ticketing_days_before_departure=7)
    outcome = rule.evaluate(context(date(2025, 2, 20)))
    assert outcome.details["deadline"] == "2025-02-25"


def test_a_passing_outcome_with_no_deadline_says_none():
    outcome = AdvancePurchase(reservation_days=7).evaluate(context(date(2025, 2, 20)))
    assert outcome.details["deadline"] == "none"


def test_instant_purchase_with_no_hours_does_not_apply():
    assert not InstantPurchase().evaluate(context(date(2025, 3, 1))).applied


def test_instant_purchase_passes_when_the_ticket_is_immediate():
    rule = InstantPurchase(hours=24, booked_at=date(2025, 3, 1))
    assert not rule.evaluate(context(date(2025, 3, 1))).failed


def test_instant_purchase_fails_when_the_ticket_is_days_later():
    rule = InstantPurchase(hours=24, booked_at=date(2025, 3, 1))
    outcome = rule.evaluate(context(date(2025, 3, 3)))
    assert outcome.failed
    assert outcome.details["hours"] == 24


def test_the_provisions_run_together_and_both_answers_are_kept():
    trace = Trace()
    outcomes = evaluate(
        [AdvancePurchase(reservation_days=7), InstantPurchase(hours=24)],
        context(date(2025, 3, 1)),
        trace,
    )
    assert len(outcomes) == 2
    assert not permitted(outcomes)
    assert trace.stage_failed(Stage.RULE)
