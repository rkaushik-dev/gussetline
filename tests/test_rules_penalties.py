from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.rules import Context
from gussetline.rules.penalties import (
    Action,
    Applies,
    Penalty,
    highest_of,
    is_refundable,
    residual_value,
    total_penalty,
)

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def context(today: date | None = None) -> Context:
    return Context(
        itinerary=Itinerary(
            [
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
            ]
        ),
        component=out(),
        ticketing=TICKETING,
        today=today,
    )


def change(**over) -> Penalty:
    fields = {
        "action": Action.CHANGE,
        "before_departure": Money.of("50.00", "EUR"),
        "after_departure": Money.of("200.00", "EUR"),
    }
    fields.update(over)
    return Penalty(**fields)


def test_a_negative_penalty_is_refused():
    with pytest.raises(ValidationError):
        change(before_departure=Money.of("-1", "EUR"))


def test_a_filing_with_nothing_in_it_does_not_apply():
    assert not Penalty().evaluate(context()).applied


def test_travel_has_not_started_before_the_first_departure():
    assert not change().travel_started(context(date(2025, 3, 1)))
    assert not change().travel_started(context(date(2025, 3, 4)))
    assert change().travel_started(context(date(2025, 3, 5)))


def test_the_same_ticket_costs_two_different_amounts_on_two_days():
    rule = change()
    assert rule.amount_on(context(date(2025, 3, 1))) == Money.of("50.00", "EUR")
    assert rule.amount_on(context(date(2025, 3, 10))) == Money.of("200.00", "EUR")


def test_without_an_after_departure_amount_the_before_one_stands():
    rule = change(after_departure=None)
    assert rule.amount_on(context(date(2025, 3, 10))) == Money.of("50.00", "EUR")


def test_the_date_falls_back_to_the_ticketing_date():
    assert change().amount_on(context()) == Money.of("50.00", "EUR")


def test_a_per_ticket_fee_is_charged_once_however_many_components():
    rule = change(applies=Applies.PER_TICKET)
    assert rule.charge(context(), components=3) == Money.of("50.00", "EUR")


def test_a_per_component_fee_multiplies():
    rule = change(applies=Applies.PER_COMPONENT)
    assert rule.charge(context(), components=3) == Money.of("150.00", "EUR")


def test_an_action_that_is_not_permitted_costs_nothing_and_that_is_not_free():
    rule = change(permitted=False)
    assert rule.charge(context()) is None
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["action"] == "change"


def test_a_zero_fee_is_a_price_and_not_a_refusal():
    rule = change(before_departure=Money.zero("EUR"))
    assert rule.charge(context()) == Money.zero("EUR")
    assert not rule.evaluate(context()).failed


def test_a_permitted_change_is_recorded_with_its_amount():
    outcome = change().evaluate(context())
    assert not outcome.failed
    assert outcome.details == {"action": "change", "amount": "50.00 EUR"}


def test_a_penalty_reads_back_in_one_line():
    assert str(change()) == "change 50.00 EUR before, 200.00 EUR after, per_ticket"
    assert str(change(permitted=False)) == "change not permitted"


def test_the_highest_of_several_amounts_wins():
    assert highest_of(
        [Money.of("50.00", "EUR"), Money.of("120.00", "EUR"), Money.of("80.00", "EUR")]
    ) == Money.of("120.00", "EUR")


def test_the_highest_of_nothing_is_nothing():
    assert highest_of([]) is None


def test_mixed_currencies_refuse_to_compare():
    with pytest.raises(ValidationError):
        highest_of([Money.of("50.00", "EUR"), Money.of("50.00", "USD")])


def test_two_filings_add_together():
    total = total_penalty(
        [change(), change(action=Action.NO_SHOW, before_departure=Money.of("75.00", "EUR"))],
        context(),
    )
    assert total == Money.of("125.00", "EUR")


def test_a_highest_of_filing_replaces_the_multiplication_for_itself():
    rule = change(applies=Applies.HIGHEST_OF)
    assert total_penalty([rule], context(), components=3) == Money.of("150.00", "EUR")


def test_a_highest_of_filing_and_a_per_ticket_one_both_count():
    total = total_penalty(
        [
            change(applies=Applies.HIGHEST_OF),
            change(action=Action.NO_SHOW, before_departure=Money.of("75.00", "EUR")),
        ],
        context(),
        components=2,
    )
    assert total == Money.of("175.00", "EUR")


def test_nothing_chargeable_totals_to_nothing():
    assert total_penalty([], context()) is None
    assert total_penalty([change(permitted=False)], context()) is None


def test_penalties_in_two_currencies_refuse_to_add():
    with pytest.raises(ValidationError):
        total_penalty(
            [change(), change(action=Action.CANCEL, before_departure=Money.of("50.00", "USD"))],
            context(),
        )


def test_a_fare_with_no_cancel_filing_is_refundable():
    assert is_refundable([])
    assert is_refundable([change()])


def test_a_cancel_filing_that_refuses_makes_it_non_refundable():
    assert not is_refundable([Penalty(action=Action.CANCEL, permitted=False)])


def test_a_cancel_filing_that_permits_keeps_it_refundable():
    assert is_refundable(
        [Penalty(action=Action.CANCEL, before_departure=Money.of("120.00", "EUR"))]
    )


def test_the_residual_is_what_is_left_after_the_fee():
    assert residual_value(Money.of("500.00", "EUR"), Money.of("120.00", "EUR")) == Money.of(
        "380.00", "EUR"
    )


def test_a_fee_larger_than_the_fare_leaves_nothing_and_not_a_debt():
    assert residual_value(Money.of("100.00", "EUR"), Money.of("120.00", "EUR")) == Money.zero("EUR")


def test_no_fee_at_all_leaves_nothing_because_nothing_may_be_refunded():
    assert residual_value(Money.of("500.00", "EUR"), None) == Money.zero("EUR")
