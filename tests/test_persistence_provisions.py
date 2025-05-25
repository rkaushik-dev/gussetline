from __future__ import annotations

from datetime import date

import pytest

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.persistence.provisions import (
    CODECS,
    kind_of,
    pack,
    provision_to_row,
    row_to_provision,
    storable,
    unpack,
)
from gussetline.rules.base import Category, Provision
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.dates import Blackout
from gussetline.rules.penalties import Action, Applies, Penalty
from gussetline.rules.purchase import AdvancePurchase, InstantPurchase
from gussetline.rules.sales import FormOfPayment, SalesRestriction
from gussetline.rules.stay import MaximumStay, MinimumStay

# Every provision the codecs claim to handle, with the awkward corners filled
# in rather than left at their defaults. The round trip test walks this, so a
# field added to a provision and forgotten in one direction fails here.
EVERY_KIND: list[Provision] = [
    AdvancePurchase(
        reservation_days=7,
        ticketing_days_after_reservation=3,
        ticketing_days_before_departure=21,
    ),
    AdvancePurchase(),
    AdvancePurchase(reservation_days=0),
    InstantPurchase(hours=24),
    InstantPurchase(),
    MinimumStay(days=3),
    MinimumStay(weekday="SUN"),
    MinimumStay(),
    MaximumStay(days=30),
    MaximumStay(months=6, from_turnaround=True),
    MaximumStay(),
    SalesRestriction(countries=("DE", "AT")),
    SalesRestriction(excluded_countries=("US",), payment=FormOfPayment.CARD),
    SalesRestriction(first_sale=date(2025, 1, 1), last_sale=date(2025, 6, 30)),
    SalesRestriction(agencies=("IATA12345678", "IATA87654321")),
    SalesRestriction(),
    Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR")),
    Penalty(
        action=Action.CANCEL,
        before_departure=Money.of("50.00", "EUR"),
        after_departure=Money.of("100.00", "EUR"),
        applies=Applies.PER_COMPONENT,
        no_show_forfeits=True,
    ),
    Penalty(action=Action.NO_SHOW, permitted=False),
    Penalty(),
    VoluntaryChange(),
    VoluntaryChange(
        permitted=False,
        basis=RepricingBasis.HISTORICAL,
        residual=Residual.RETAINED,
        keep_fare_breaks=True,
        same_carrier_only=True,
        once_only=True,
    ),
    VoluntaryChange(same_carrier_only=False, once_only=False),
]


@pytest.mark.parametrize("provision", EVERY_KIND, ids=lambda p: type(p).__name__)
def test_every_provision_survives_the_round_trip(provision):
    # The one test that catches a field filled on the way in and forgotten on
    # the way out, which otherwise reads empty for a year.
    assert row_to_provision(provision_to_row(provision)) == provision


@pytest.mark.parametrize("provision", EVERY_KIND, ids=lambda p: type(p).__name__)
def test_every_provision_keeps_its_category_on_the_row(provision):
    row = provision_to_row(provision)
    assert row.category == int(provision.category)
    assert row.kind in CODECS
    assert row.detail is not None


def test_every_codec_names_a_provision_class_that_exists():
    for name, (kind, _, _) in CODECS.items():
        assert issubclass(kind, Provision)
        assert name.islower()


def test_a_provision_names_the_kind_it_stores_as():
    assert kind_of(MinimumStay(days=3)) == "minimum_stay"
    assert kind_of(VoluntaryChange()) == "voluntary_change"


def test_a_provision_with_no_codec_is_refused_by_class_name():
    with pytest.raises(ValidationError, match="Blackout"):
        kind_of(Blackout())


def test_whether_a_provision_can_be_stored_can_be_asked_without_raising():
    assert storable(MinimumStay(days=3))
    assert not storable(Blackout())


def test_a_row_with_a_kind_we_do_not_know_is_refused():
    row = provision_to_row(MinimumStay(days=3))
    row.kind = "nonsense"
    with pytest.raises(ValidationError, match="no codec"):
        row_to_provision(row)


def test_the_rule_id_is_set_when_it_is_given():
    assert provision_to_row(MinimumStay(days=3), rule_id=7).rule_id == 7


def test_a_minimum_stay_puts_its_days_in_a_column_a_report_can_group_by():
    row = provision_to_row(MinimumStay(days=3))
    assert row.number_one == 3
    assert row.detail == ""


def test_a_sales_restriction_puts_its_countries_in_columns_and_the_rest_in_the_spill():
    # Somebody does want to ask which fares may be sold in Austria, so that
    # is a column. Nobody groups by the form of payment.
    row = provision_to_row(SalesRestriction(countries=("DE", "AT"), payment=FormOfPayment.CARD))
    assert row.text_one == "DE/AT"
    assert "payment=card" in row.detail


def test_a_penalty_puts_the_fee_before_departure_in_the_amount_column():
    row = provision_to_row(Penalty(before_departure=Money.of("50.00", "EUR")))
    assert row.amount == Money.of("50.00", "EUR").amount
    assert row.currency == "EUR"


def test_a_penalty_with_only_an_after_amount_still_keeps_its_currency():
    row = provision_to_row(Penalty(after_departure=Money.of("100.00", "GBP")))
    assert row.currency == "GBP"
    back = row_to_provision(row)
    assert isinstance(back, Penalty)
    assert back.after_departure == Money.of("100.00", "GBP")


def test_the_spill_writes_the_pairs_in_a_settled_order():
    assert pack({"b": 2, "a": 1}) == "a=1;b=2"


def test_the_spill_leaves_out_what_is_not_there():
    assert pack({"a": None, "b": "", "c": (), "d": 1}) == "d=1"


def test_the_spill_writes_a_date_the_way_it_reads_one():
    assert pack({"first": date(2025, 1, 1)}) == "first=2025-01-01"


def test_the_spill_refuses_a_value_carrying_its_own_separator():
    with pytest.raises(ValidationError, match="separator"):
        pack({"a": "one;two"})
    with pytest.raises(ValidationError, match="separator"):
        pack({"a": "one=two"})


def test_the_spill_reads_back_what_it_wrote():
    assert unpack(pack({"a": 1, "b": "two"})) == {"a": "1", "b": "two"}


def test_an_empty_spill_reads_as_nothing():
    assert unpack("") == {}


def test_a_spill_with_rubbish_in_it_yields_what_it_can():
    assert unpack("a=1;nonsense;b=2") == {"a": "1", "b": "2"}


def test_a_value_may_carry_an_equals_when_it_comes_back():
    # Writing one is refused, but a row written by an older version might.
    assert unpack("a=one=two") == {"a": "one=two"}


def test_a_zero_is_kept_and_not_mistaken_for_nothing():
    # A category 5 filed at zero days says the ticket is bought on the day of
    # departure. Dropping it would say there is no advance purchase at all.
    row = provision_to_row(AdvancePurchase(ticketing_days_before_departure=0))
    back = row_to_provision(row)
    assert isinstance(back, AdvancePurchase)
    assert back.ticketing_days_before_departure == 0


def test_the_categories_stored_cover_the_ones_the_reprice_needs():
    stored = {int(provision.category) for provision in EVERY_KIND}
    assert Category.ADVANCE_RESERVATION in stored
    assert Category.MINIMUM_STAY in stored
    assert Category.MAXIMUM_STAY in stored
    assert Category.PENALTIES in stored
    assert Category.VOLUNTARY_CHANGES in stored
