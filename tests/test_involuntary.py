from __future__ import annotations

from datetime import date

import pytest

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.involuntary import (
    Cause,
    Reaccommodation,
    assess,
    is_within_reaccommodation,
    residual_for,
    still_applies,
    waives,
)
from gussetline.money import Money
from gussetline.reissue import assess as assess_taxes
from gussetline.rules.changes import Residual
from gussetline.taxes import AppliedTax

PAID = Money.of("840.00", "EUR")


def tax(code="DE", point="FRA", amount="32.50", imposed=False) -> AppliedTax:
    return AppliedTax(
        code=code, amount=Money.of(amount, "EUR"), point=point, carrier_imposed=imposed
    )


def test_the_fare_is_the_one_the_passenger_bought():
    # The most common way a desk overcharges somebody already let down.
    made = assess(PAID, Cause.CANCELLED)
    assert made.new_fare == PAID


def test_there_is_no_change_fee():
    assert assess(PAID, Cause.CANCELLED).penalty == Money.zero("EUR")


def test_nothing_is_collected_when_the_taxes_did_not_move():
    made = assess(PAID, Cause.CANCELLED)
    assert made.add_collect == Money.zero("EUR")
    assert made.refund == Money.zero("EUR")
    assert made.net == Money.zero("EUR")


def test_a_tax_that_went_up_is_still_collected():
    taxes = assess_taxes((tax(amount="32.50"),), (tax(amount="40.00"),))
    made = assess(PAID, Cause.CANCELLED, taxes=taxes)
    assert made.add_collect == Money.of("7.50", "EUR")


def test_a_tax_that_came_back_comes_back():
    taxes = assess_taxes((tax(amount="40.00"),), (tax(amount="32.50"),))
    made = assess(PAID, Cause.CANCELLED, taxes=taxes)
    assert made.refund == Money.of("7.50", "EUR")
    assert made.net.is_negative


def test_a_cancelled_flight_says_why_it_is_not_repriced():
    made = assess(PAID, Cause.CANCELLED)
    assert "the carrier did not fly it" in made.notes[0]
    assert "nobody changed their mind" in made.notes[1]


def test_a_downgrade_owes_the_difference_between_the_two_cabins():
    made = assess(
        PAID,
        Cause.DOWNGRADED,
        was=Cabin.BUSINESS,
        now=Cabin.ECONOMY,
        cabin_difference=Money.of("1200.00", "EUR"),
    )
    assert made.downgrade_refund == Money.of("1200.00", "EUR")
    assert made.refund == Money.of("1200.00", "EUR")
    assert "downgraded from C to Y" in made.notes[-1]


def test_denied_boarding_owes_a_downgrade_too():
    made = assess(
        PAID,
        Cause.DENIED_BOARDING,
        was=Cabin.BUSINESS,
        now=Cabin.ECONOMY,
        cabin_difference=Money.of("900.00", "EUR"),
    )
    assert made.downgrade_refund == Money.of("900.00", "EUR")


def test_a_schedule_change_that_happens_to_seat_them_lower_owes_nothing():
    made = assess(PAID, Cause.SCHEDULE_CHANGE, was=Cabin.BUSINESS, now=Cabin.ECONOMY)
    assert made.downgrade_refund == Money.zero("EUR")
    assert "without a downgrade owed" in made.notes[-1]


def test_a_downgrade_with_no_difference_priced_is_refused_rather_than_guessed():
    with pytest.raises(ValidationError, match="difference between the two cabins"):
        assess(PAID, Cause.DOWNGRADED, was=Cabin.BUSINESS, now=Cabin.ECONOMY)


def test_being_moved_up_a_cabin_owes_nothing_either_way():
    made = assess(PAID, Cause.CANCELLED, was=Cabin.ECONOMY, now=Cabin.BUSINESS)
    assert made.downgrade_refund == Money.zero("EUR")
    assert made.net == Money.zero("EUR")


def test_a_negative_refund_is_refused():
    from gussetline.involuntary import Involuntary

    with pytest.raises(ValidationError, match="not negative"):
        Involuntary(paid=PAID, cause=Cause.CANCELLED, downgrade_refund=Money.of("-1.00", "EUR"))


def test_an_involuntary_reissue_reads_back_in_one_line():
    made = assess(PAID, Cause.CANCELLED)
    assert str(made) == "cancelled: collect 0 EUR, refund 0 EUR"


def test_every_cause_is_the_carriers_doing():
    # Which is the whole reason this file exists rather than a flag on the
    # voluntary path.
    assert all(cause.is_the_carriers_doing for cause in Cause)


def test_only_two_causes_owe_a_downgrade():
    owed = {cause for cause in Cause if cause.owes_a_downgrade_refund}
    assert owed == {Cause.DOWNGRADED, Cause.DENIED_BOARDING}


def test_the_conditions_the_passenger_kept_are_waived():
    # Promises about how they would buy and travel, which they kept.
    given = waives(Cause.CANCELLED)
    assert "advance purchase" in given
    assert "minimum stay" in given
    assert "penalties" in given


def test_the_carriers_own_limits_still_apply():
    left = still_applies(Cause.CANCELLED)
    assert "flight application" in left
    assert "advance purchase" not in left


def test_the_two_lists_do_not_overlap():
    assert not set(waives(Cause.CANCELLED)) & set(still_applies(Cause.CANCELLED))


def test_a_residual_on_an_involuntary_reissue_always_comes_back():
    # A forfeited residual is a term accepted for a cheaper fare, and none of
    # that applies when the carrier is the one who did not fly.
    assert all(residual_for(cause) is Residual.REFUNDABLE for cause in Cause)


def test_a_reaccommodation_permits_the_carriers_own_flights():
    offer = Reaccommodation(cause=Cause.CANCELLED)
    assert offer.permits("LH", "LH")
    assert not offer.permits("UA", "LH")


def test_an_interline_reaccommodation_permits_an_agreed_carrier():
    offer = Reaccommodation(
        cause=Cause.CANCELLED, same_carrier_only=False, interline_agreed=("UA", "AC")
    )
    assert offer.permits("UA", "LH")
    assert not offer.permits("BA", "LH")


def test_a_cancelled_seat_does_not_become_a_better_one_by_default():
    offer = Reaccommodation(cause=Cause.CANCELLED)
    assert offer.permits_cabin(Cabin.ECONOMY, Cabin.ECONOMY)
    assert offer.permits_cabin(Cabin.ECONOMY, Cabin.BUSINESS)
    assert not offer.permits_cabin(Cabin.BUSINESS, Cabin.ECONOMY)


def test_a_carrier_that_will_seat_them_anywhere_says_so():
    offer = Reaccommodation(cause=Cause.CANCELLED, same_cabin_or_better=False)
    assert offer.permits_cabin(Cabin.BUSINESS, Cabin.ECONOMY)


def test_a_reaccommodation_reads_back_in_one_line():
    assert str(Reaccommodation(cause=Cause.CANCELLED)) == "cancelled, same carrier"


def test_a_nearby_flight_is_a_reaccommodation():
    assert is_within_reaccommodation(Cause.CANCELLED, date(2025, 8, 4), date(2025, 8, 6))


def test_the_day_before_is_a_reaccommodation_too():
    assert is_within_reaccommodation(Cause.CANCELLED, date(2025, 8, 4), date(2025, 8, 3))


def test_two_months_later_is_a_new_journey_and_not_a_reaccommodation():
    # Pricing it as involuntary hands them a fare they did not buy.
    assert not is_within_reaccommodation(Cause.CANCELLED, date(2025, 8, 4), date(2025, 10, 4))


def test_the_window_can_be_named():
    assert is_within_reaccommodation(Cause.CANCELLED, date(2025, 8, 4), date(2025, 10, 4), days=90)


def test_a_negative_window_is_refused():
    with pytest.raises(ValidationError, match="not negative"):
        is_within_reaccommodation(Cause.CANCELLED, date(2025, 8, 4), date(2025, 8, 6), days=-1)


def test_a_whole_involuntary_reissue_adds_up():
    taxes = assess_taxes(
        (tax(code="DE", amount="32.50"), tax(code="US", point="JFK", amount="18.00")),
        (tax(code="DE", amount="40.00"),),
        residual=residual_for(Cause.CANCELLED),
    )
    made = assess(
        PAID,
        Cause.DOWNGRADED,
        taxes=taxes,
        was=Cabin.BUSINESS,
        now=Cabin.ECONOMY,
        cabin_difference=Money.of("1200.00", "EUR"),
    )
    assert made.new_fare == PAID
    assert made.penalty == Money.zero("EUR")
    assert made.add_collect == Money.of("7.50", "EUR")
    assert made.refund == Money.of("1218.00", "EUR")
    assert made.net == Money.of("-1210.50", "EUR")
