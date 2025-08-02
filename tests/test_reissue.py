from __future__ import annotations

import pytest

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.reissue import (
    Treatment,
    assess,
    credit_for,
    net,
    points_flown,
)
from gussetline.rules.changes import Residual
from gussetline.taxes import AppliedTax


def tax(code="DE", point="FRA", amount="32.50", currency="EUR", imposed=False) -> AppliedTax:
    return AppliedTax(
        code=code,
        amount=Money.of(amount, currency),
        point=point,
        carrier_imposed=imposed,
    )


def test_a_tax_that_did_not_move_is_left_alone():
    report = assess((tax(),), (tax(),))
    assert report.of(Treatment.UNCHANGED)
    assert report.collect == Money.zero("EUR")
    assert report.refund == Money.zero("EUR")


def test_a_tax_that_went_up_is_collected():
    report = assess((tax(amount="32.50"),), (tax(amount="40.00"),))
    assert report.collect == Money.of("7.50", "EUR")
    assert report.of(Treatment.COLLECTED)[0].code == "DE"


def test_a_tax_that_went_up_is_collected_whatever_the_fare_did():
    # No netting against a fare that came down. The government is owed what
    # it is owed and the residual is between the passenger and the carrier.
    report = assess((tax(amount="32.50"),), (tax(amount="40.00"),), residual=Residual.FORFEITED)
    assert report.collect == Money.of("7.50", "EUR")


def test_a_tax_that_came_down_comes_back():
    report = assess((tax(amount="40.00"),), (tax(amount="32.50"),))
    assert report.refund == Money.of("7.50", "EUR")


def test_a_tax_on_a_point_the_new_itinerary_does_not_touch_comes_back_whole():
    # Not because the fare came down. The taxable event did not happen.
    report = assess((tax(code="AT", point="VIE", amount="12.00"),), (tax(),))
    assert report.refund == Money.of("12.00", "EUR")
    assert report.collect == Money.of("32.50", "EUR")


def test_a_tax_on_a_new_point_is_collected_whole():
    report = assess((tax(),), (tax(), tax(code="AT", point="VIE", amount="12.00")))
    assert report.collect == Money.of("12.00", "EUR")


def test_a_tax_at_a_point_already_departed_is_gone():
    # The single most expensive mistake available here. It looks correct on
    # every screen and the carrier finds it at the year end.
    report = assess((tax(),), (), flown_points=points_flown(("FRA",)))
    assert report.refund == Money.zero("EUR")
    assert report.flown == Money.of("32.50", "EUR")
    assert report.of(Treatment.FLOWN)[0].point == "FRA"


def test_a_tax_at_a_point_not_yet_departed_still_comes_back():
    report = assess((tax(code="AT", point="VIE"),), (), flown_points=points_flown(("FRA",)))
    assert report.refund == Money.of("32.50", "EUR")


def test_a_partial_reduction_at_a_flown_point_is_gone_too():
    report = assess(
        (tax(amount="40.00"),), (tax(amount="32.50"),), flown_points=points_flown(("FRA",))
    )
    assert report.refund == Money.zero("EUR")
    assert report.of(Treatment.FLOWN)


def test_an_increase_at_a_flown_point_is_still_collected():
    report = assess(
        (tax(amount="32.50"),), (tax(amount="40.00"),), flown_points=points_flown(("FRA",))
    )
    assert report.collect == Money.of("7.50", "EUR")


def test_a_carrier_surcharge_follows_the_fare_and_not_the_government():
    # YQ is the carrier's own money in a tax shaped box.
    report = assess(
        (tax(code="YQ", amount="180.00", imposed=True),),
        (),
        residual=Residual.FORFEITED,
    )
    assert report.refund == Money.zero("EUR")
    assert report.kept == Money.of("180.00", "EUR")


def test_a_carrier_surcharge_comes_back_when_the_residual_is_refundable():
    report = assess(
        (tax(code="YQ", amount="180.00", imposed=True),),
        (),
        residual=Residual.REFUNDABLE,
    )
    assert report.refund == Money.of("180.00", "EUR")


def test_a_carrier_surcharge_at_a_flown_point_still_follows_the_fare():
    # It is not a departure tax, so departing does not make it owed.
    report = assess(
        (tax(code="YQ", point="FRA", amount="180.00", imposed=True),),
        (),
        flown_points=points_flown(("FRA",)),
        residual=Residual.REFUNDABLE,
    )
    assert report.refund == Money.of("180.00", "EUR")
    assert report.flown == Money.zero("EUR")


def test_a_retained_residual_keeps_the_surcharge_as_well():
    report = assess(
        (tax(code="YR", amount="40.00", imposed=True),),
        (),
        residual=Residual.RETAINED,
    )
    assert report.kept == Money.of("40.00", "EUR")


def test_the_same_code_at_two_points_is_two_lines():
    # Totalling first gives the right answer on the easy cases and quietly
    # wrong ones on every itinerary that changed a country.
    report = assess(
        (tax(code="XT", point="FRA", amount="10.00"), tax(code="XT", point="VIE", amount="12.00")),
        (tax(code="XT", point="FRA", amount="10.00"),),
    )
    assert len(report) == 2
    assert report.refund == Money.of("12.00", "EUR")


def test_the_lines_come_back_in_a_settled_order():
    report = assess(
        (tax(code="XT", point="VIE"), tax(code="DE", point="FRA")),
        (),
    )
    assert [line.code for line in report.lines] == ["DE", "XT"]


def test_a_line_reads_back_in_one_line():
    report = assess((tax(),), (tax(amount="40.00"),))
    assert str(report.lines[0]) == "DE at FRA collected 32.50 EUR to 40.00 EUR"


def test_a_report_reads_back_with_the_two_totals():
    report = assess((tax(),), (tax(amount="40.00"),))
    assert str(report) == "collect 7.50 EUR, refund 0 EUR, 1 lines"


def test_the_net_says_what_the_taxes_cost():
    report = assess((tax(amount="32.50"),), (tax(amount="40.00"),))
    assert net(report) == Money.of("7.50", "EUR")


def test_a_net_that_comes_back_keeps_its_sign():
    # A reissue whose taxes come back further than its fare goes up is a real
    # shape and the desk needs to see the sign.
    report = assess((tax(amount="40.00"),), (tax(amount="32.50"),))
    assert net(report).is_negative


def test_a_refundable_residual_pays_no_voucher():
    report = assess((tax(amount="40.00"),), (tax(amount="32.50"),))
    assert credit_for(report, Residual.REFUNDABLE) == Money.zero("EUR")


def test_a_retained_residual_values_the_refund_as_a_voucher():
    # The same discount the fare side uses, so a recommendation cannot be
    # worth more by moving money between the fare and the taxes.
    report = assess((tax(amount="40.00"),), (tax(amount="30.00"),))
    assert credit_for(report, Residual.RETAINED) == Money.of("8.00", "EUR")


def test_comparing_two_currencies_is_refused():
    with pytest.raises(ValidationError, match="one currency"):
        assess((tax(currency="EUR"),), (tax(currency="GBP"),))


def test_comparing_nothing_at_all_is_refused():
    with pytest.raises(ValidationError, match="needs a tax"):
        assess((), ())


def test_the_points_already_departed_are_read_off_the_flown_coupons():
    assert points_flown(("fra", "", "vie")) == frozenset({"FRA", "VIE"})
    assert points_flown(()) == frozenset()


def test_only_the_two_money_moving_treatments_move_money():
    assert Treatment.COLLECTED.moves_money
    assert Treatment.REFUNDED.moves_money
    assert not Treatment.KEPT.moves_money
    assert not Treatment.FLOWN.moves_money
    assert not Treatment.UNCHANGED.moves_money


def test_a_whole_reissue_adds_up_the_way_the_desk_reads_it():
    was = (
        tax(code="DE", point="FRA", amount="32.50"),
        tax(code="US", point="JFK", amount="18.00"),
        tax(code="YQ", point="FRA", amount="180.00", imposed=True),
    )
    now = (
        tax(code="DE", point="FRA", amount="32.50"),
        tax(code="US", point="BOS", amount="18.00"),
        tax(code="YQ", point="FRA", amount="150.00", imposed=True),
    )
    report = assess(was, now, flown_points=points_flown(("FRA",)), residual=Residual.FORFEITED)
    assert report.collect == Money.of("18.00", "EUR")
    assert report.refund == Money.of("18.00", "EUR")
    assert report.kept == Money.of("30.00", "EUR")
    assert net(report) == Money.zero("EUR")
