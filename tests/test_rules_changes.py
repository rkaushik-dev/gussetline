from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.rules import Context
from gussetline.rules.changes import (
    RepricingBasis,
    Residual,
    VoluntaryChange,
    priced_from_today,
    reissue_for,
)

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def context(reissue: bool = True) -> Context:
    return Context(
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")]
        ),
        component=out(),
        ticketing=TICKETING,
        reissue=reissue,
    )


def money(amount: str) -> Money:
    return Money.of(amount, "EUR")


def test_the_provision_is_silent_when_nothing_is_being_reissued():
    assert not VoluntaryChange().evaluate(context(reissue=False)).applied


def test_a_fare_that_refuses_changes_fails():
    outcome = VoluntaryChange(permitted=False).evaluate(context())
    assert outcome.failed


def test_a_fare_that_permits_changes_records_how_it_reprices():
    outcome = VoluntaryChange(basis=RepricingBasis.CURRENT, residual=Residual.REFUNDABLE).evaluate(
        context()
    )
    assert not outcome.failed
    assert outcome.details == {"basis": "current", "residual": "refundable"}


def test_a_rule_reads_back_in_one_line():
    assert str(VoluntaryChange(permitted=False)) == "changes not permitted"
    assert str(VoluntaryChange()) == "changes permitted, current pricing, residual forfeited"


def test_a_reissue_happens_in_one_currency():
    with pytest.raises(ValidationError):
        reissue_for(money("500"), Money.of("400", "USD"), money("50"), VoluntaryChange())


def test_a_negative_amount_is_refused():
    with pytest.raises(ValidationError):
        reissue_for(money("500"), money("-400"), money("50"), VoluntaryChange())


def test_a_dearer_new_fare_collects_the_difference_and_the_fee():
    change = reissue_for(money("500"), money("620"), money("50"), VoluntaryChange())
    assert change.difference == money("120")
    assert change.add_collect == money("170")
    assert not change.worth_doing


def test_a_cheaper_new_fare_still_collects_the_fee():
    change = reissue_for(money("500"), money("380"), money("50"), VoluntaryChange())
    assert change.difference == money("-120")
    assert change.add_collect == money("50")


def test_a_forfeited_residual_is_worth_nothing_however_large_the_drop():
    change = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.FORFEITED)
    )
    assert change.refund.is_zero
    assert change.credit.is_zero
    assert not change.worth_doing


def test_a_refundable_residual_comes_back_as_cash_less_the_fee():
    change = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.REFUNDABLE)
    )
    assert change.refund == money("350")
    assert change.benefit == money("350")
    assert change.worth_doing


def test_a_refund_smaller_than_the_fee_leaves_nothing_and_not_a_debt():
    change = reissue_for(
        money("500"), money("480"), money("50"), VoluntaryChange(residual=Residual.REFUNDABLE)
    )
    assert change.refund.is_zero


def test_a_retained_residual_becomes_a_credit_counted_at_a_discount():
    change = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.RETAINED)
    )
    assert change.credit == money("350")
    assert change.refund.is_zero
    assert change.benefit == money("280.00")
    assert change.worth_doing


def test_a_credit_is_worth_less_than_the_same_amount_of_cash():
    refundable = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.REFUNDABLE)
    )
    retained = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.RETAINED)
    )
    assert retained.benefit < refundable.benefit


def test_a_fare_that_refuses_changes_is_worth_nothing_even_on_a_big_drop():
    change = reissue_for(
        money("800"),
        money("300"),
        money("0"),
        VoluntaryChange(permitted=False, residual=Residual.REFUNDABLE),
    )
    assert change.benefit.is_zero
    assert not change.worth_doing
    assert str(change) == "not permitted"


def test_a_reissue_that_costs_money_reports_a_negative_benefit():
    change = reissue_for(money("500"), money("620"), money("50"), VoluntaryChange())
    assert change.benefit == money("-170")
    assert str(change) == "costs 170 EUR"


def test_a_reissue_that_pays_reads_back_as_worth_it():
    change = reissue_for(
        money("800"), money("400"), money("50"), VoluntaryChange(residual=Residual.REFUNDABLE)
    )
    assert str(change) == "worth 350 EUR"


def test_a_drop_exactly_equal_to_the_fee_is_not_worth_doing():
    change = reissue_for(
        money("500"), money("450"), money("50"), VoluntaryChange(residual=Residual.REFUNDABLE)
    )
    assert change.refund.is_zero
    assert not change.worth_doing


def test_a_historical_filing_cannot_reach_todays_fares():
    assert not priced_from_today(VoluntaryChange(basis=RepricingBasis.HISTORICAL))
    assert priced_from_today(VoluntaryChange(basis=RepricingBasis.CURRENT))


def test_a_fare_that_refuses_changes_is_never_priced_from_today():
    assert not priced_from_today(VoluntaryChange(permitted=False))
