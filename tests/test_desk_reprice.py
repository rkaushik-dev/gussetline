from __future__ import annotations

from datetime import date, datetime

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.desk.reprice import (
    Decision,
    Verdict,
    decide,
    to_retire,
    total_benefit,
    worth_reporting,
)
from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.desk.watch import Observation, Retired, Watch
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.rules.base import Context
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Applies, Penalty

TODAY = date(2025, 6, 22)
DEPARTURE = date(2025, 7, 4)


def coupon(position=0, origin="FRA", destination="JFK", when="2025-07-04 10:25", **over):
    fields = {
        "position": position,
        "carrier": "LH",
        "flight_number": "400",
        "origin": origin,
        "destination": destination,
        "departure": datetime.strptime(when, "%Y-%m-%d %H:%M"),
        "booking_class": "K",
        "fare_basis": "KLWAP7RT",
    }
    fields.update(over)
    return Coupon(**fields)


def ticket(coupons=None) -> Ticket:
    return Ticket(
        number="220-1234567890",
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        coupons=coupons if coupons is not None else (coupon(0),),
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_fare=Money.of("840.00", "EUR"),
        total_paid=Money.of("1090.83", "EUR"),
    )


def watch(seen=("700.00", "700.00"), threshold="50.00", **over) -> Watch:
    fields = {
        "ticket": ticket(),
        "origin": "FRA",
        "destination": "JFK",
        "departure": DEPARTURE,
        "threshold": Money.of(threshold, "EUR"),
        "hysteresis": 2,
    }
    fields.update(over)
    row = Watch(**fields)
    for index, amount in enumerate(seen):
        row.record(Observation(seen_on=date(2025, 6, 18 + index), amount=Money.of(amount, "EUR")))
    return row


def context() -> Context:
    return Context(
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")]
        ),
        component=FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"),)
        ),
        ticketing=date(2025, 2, 14),
        today=TODAY,
        reissue=True,
    )


def change(fee="50.00", **over) -> Penalty:
    fields = {
        "action": Action.CHANGE,
        "before_departure": Money.of(fee, "EUR"),
        "after_departure": Money.of("200.00", "EUR"),
    }
    fields.update(over)
    return Penalty(**fields)


def refundable(**over) -> VoluntaryChange:
    fields = {"residual": Residual.REFUNDABLE}
    fields.update(over)
    return VoluntaryChange(**fields)


def test_a_finished_ticket_is_checked_before_anything_else():
    row = watch(ticket=ticket(coupons=(coupon(0, status=CouponStatus.USED),)))
    made = decide(row, refundable(), [change()], context(), TODAY)
    assert made.verdict is Verdict.NOTHING_OPEN
    assert not made.actionable


def test_a_fare_that_cannot_be_changed_stops_there():
    made = decide(watch(), refundable(permitted=False), [change()], context(), TODAY)
    assert made.verdict is Verdict.NOT_CHANGEABLE
    assert "does not permit" in made.reason


def test_a_historically_priced_fare_stops_there():
    made = decide(
        watch(), refundable(basis=RepricingBasis.HISTORICAL), [change()], context(), TODAY
    )
    assert made.verdict is Verdict.PRICED_HISTORICALLY


def test_a_forfeited_residual_stops_there_however_big_the_drop():
    made = decide(
        watch(seen=("300.00", "300.00")),
        VoluntaryChange(residual=Residual.FORFEITED),
        [change()],
        context(),
        TODAY,
    )
    assert made.verdict is Verdict.RESIDUAL_FORFEITED
    assert made.worth.is_zero


def test_no_held_drop_is_not_worth_it():
    made = decide(watch(seen=("840.00",)), refundable(), [change()], context(), TODAY)
    assert made.verdict is Verdict.NOT_WORTH_IT
    assert "no drop has held" in made.reason


def test_a_drop_that_clears_the_fee_and_the_threshold_is_a_reissue():
    made = decide(watch(), refundable(), [change()], context(), TODAY)
    assert made.verdict is Verdict.REISSUE
    assert made.drop == Money.of("140.00", "EUR")
    assert made.penalty == Money.of("50.00", "EUR")
    assert made.benefit == Money.of("90.00", "EUR")
    assert made.actionable


def test_a_drop_smaller_than_the_fee_is_not_worth_it():
    made = decide(
        watch(seen=("810.00", "810.00"), threshold="10.00"),
        refundable(),
        [change(fee="50.00")],
        context(),
        TODAY,
    )
    assert made.verdict is Verdict.NOT_WORTH_IT
    assert "does not cover" in made.reason
    assert made.worth.is_zero


def test_a_drop_that_exactly_covers_the_fee_is_not_worth_it():
    made = decide(
        watch(seen=("790.00", "790.00"), threshold="0"),
        refundable(),
        [change(fee="50.00")],
        context(),
        TODAY,
    )
    assert made.verdict is Verdict.NOT_WORTH_IT


def test_a_benefit_under_the_desks_threshold_is_not_worth_it():
    made = decide(
        watch(seen=("770.00", "770.00"), threshold="50.00"),
        refundable(),
        [change(fee="50.00")],
        context(),
        TODAY,
    )
    assert made.verdict is Verdict.NOT_WORTH_IT
    assert "under the 50.00 EUR" in made.reason


def test_a_retained_residual_is_worth_less_than_a_refundable_one():
    cash = decide(watch(), refundable(), [change()], context(), TODAY)
    credit = decide(watch(), refundable(residual=Residual.RETAINED), [change()], context(), TODAY)
    assert credit.benefit is not None and cash.benefit is not None
    assert credit.benefit < cash.benefit
    assert credit.benefit == Money.of("72.00", "EUR")
    assert "counted at a discount" in credit.notes[0]


def test_the_fee_after_departure_is_the_one_used_once_travel_starts():
    made = decide(watch(), refundable(), [change()], context(), date(2025, 7, 10))
    # The ticket is finished by then, so the first check fires first.
    assert made.verdict is Verdict.NOTHING_OPEN


def test_a_per_component_fee_multiplies_and_can_kill_the_saving():
    two_coupons = ticket(
        coupons=(
            coupon(0),
            coupon(1, origin="JFK", destination="FRA", when="2025-07-18 18:30"),
        )
    )
    made = decide(
        watch(ticket=two_coupons, seen=("740.00", "740.00")),
        refundable(),
        [change(fee="50.00", applies=Applies.PER_COMPONENT)],
        context(),
        TODAY,
    )
    assert made.penalty == Money.of("100.00", "EUR")
    assert made.verdict is Verdict.NOT_WORTH_IT


def test_a_fare_with_no_change_fee_filed_keeps_the_whole_drop():
    made = decide(watch(), refundable(), [], context(), TODAY)
    assert made.penalty == Money.zero("EUR")
    assert made.benefit == Money.of("140.00", "EUR")


def test_a_fee_in_another_currency_is_refused():
    with pytest.raises(ValidationError):
        decide(
            watch(),
            refundable(),
            [change(before_departure=Money.of("50.00", "USD"))],
            context(),
            TODAY,
        )


def test_a_decision_reads_back_in_one_line():
    made = decide(watch(), refundable(), [change()], context(), TODAY)
    assert str(made) == "FRA-NYC-2025-07-04-Y reissue worth 90.00 EUR"
    refused = decide(watch(), refundable(permitted=False), [change()], context(), TODAY)
    assert str(refused).startswith("FRA-NYC-2025-07-04-Y not_changeable,")


def test_only_the_actionable_decisions_are_reported_dearest_first():
    small = decide(watch(seen=("720.00", "720.00")), refundable(), [change()], context(), TODAY)
    large = decide(watch(seen=("600.00", "600.00")), refundable(), [change()], context(), TODAY)
    nothing = decide(watch(seen=("840.00",)), refundable(), [change()], context(), TODAY)
    reported = worth_reporting([small, nothing, large])
    assert [d.worth for d in reported] == [Money.of("190.00", "EUR"), Money.of("70.00", "EUR")]


def test_the_benefit_of_a_run_only_counts_the_actionable_ones():
    good = decide(watch(), refundable(), [change()], context(), TODAY)
    bad = decide(watch(), refundable(permitted=False), [change()], context(), TODAY)
    assert total_benefit([good, bad], "EUR") == Money.of("90.00", "EUR")
    assert total_benefit([], "EUR") == Money.zero("EUR")


def test_a_fare_that_can_never_become_actionable_retires_its_watch():
    unchangeable = decide(watch(), refundable(permitted=False), [change()], context(), TODAY)
    historical = decide(
        watch(), refundable(basis=RepricingBasis.HISTORICAL), [change()], context(), TODAY
    )
    reasons = [reason for _, reason in to_retire([unchangeable, historical])]
    assert reasons == [Retired.NOT_CHANGEABLE, Retired.HISTORICAL]


def test_a_price_that_is_merely_too_small_does_not_retire_the_watch():
    # Prices move. That one is worth keeping.
    made = decide(watch(seen=("810.00", "810.00")), refundable(), [change()], context(), TODAY)
    assert to_retire([made]) == []


def test_a_forfeited_residual_does_not_retire_the_watch_either():
    # The carrier can refile category 31, and some of them do quarterly.
    made = decide(
        watch(), VoluntaryChange(residual=Residual.FORFEITED), [change()], context(), TODAY
    )
    assert to_retire([made]) == []


def test_a_decision_with_no_benefit_is_worth_nothing_rather_than_none():
    made = Decision(Verdict.NOT_CHANGEABLE, watch())
    assert made.worth == Money.zero("EUR")
