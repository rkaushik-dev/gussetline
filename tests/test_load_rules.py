from __future__ import annotations

from datetime import date
from decimal import Decimal

from gussetline.collect.fixedwidth import write_record
from gussetline.load.rules import (
    ADVANCE,
    CHANGES,
    MAXIMUM,
    MINIMUM,
    PENALTY,
    RULE_HEADER,
    SALES,
    apply,
    categories_of,
    load_into,
    missing_categories,
    read,
)
from gussetline.money import Money
from gussetline.rules.base import Category, RuleStore
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Applies, Penalty
from gussetline.rules.purchase import AdvancePurchase
from gussetline.rules.sales import FormOfPayment, SalesRestriction
from gussetline.rules.stay import MaximumStay, MinimumStay


def head(carrier="LH", number="0100", tariff="001", action="A") -> str:
    return write_record(
        RULE_HEADER,
        {"carrier": carrier, "number": number, "tariff": tariff, "action": action},
    )


def advance(reservation=7, after=None, before=None) -> str:
    return write_record(
        ADVANCE,
        {
            "reservation_days": reservation,
            "ticketing_after": after,
            "ticketing_before": before,
        },
    )


def minimum(days=None, weekday=None) -> str:
    return write_record(MINIMUM, {"days": days, "weekday": weekday})


def maximum(days=None, months=6, from_turnaround=None) -> str:
    return write_record(
        MAXIMUM, {"days": days, "months": months, "from_turnaround": from_turnaround}
    )


def sales(countries=None, excluded=None, payment=None, first=None, last=None) -> str:
    return write_record(
        SALES,
        {
            "countries": countries,
            "excluded": excluded,
            "payment": payment,
            "first_sale": first,
            "last_sale": last,
        },
    )


def penalty(
    action="C",
    permitted="Y",
    currency="EUR",
    before="50.00",
    after="100.00",
    applies=None,
    no_show=None,
) -> str:
    return write_record(
        PENALTY,
        {
            "action": action,
            "permitted": permitted,
            "currency": currency,
            "before": Decimal(before) if before is not None else None,
            "after": Decimal(after) if after is not None else None,
            "applies": applies,
            "no_show_forfeits": no_show,
        },
    )


def changes(permitted="Y", basis="C", residual="R", keep=None, same_carrier=None, once=None) -> str:
    return write_record(
        CHANGES,
        {
            "permitted": permitted,
            "basis": basis,
            "residual": residual,
            "keep_breaks": keep,
            "same_carrier": same_carrier,
            "once_only": once,
        },
    )


def file_of(*lines: str) -> str:
    return "\n".join(lines)


def test_an_empty_file_reads_as_no_rules():
    load = read("")
    assert len(load) == 0
    assert load.clean


def test_a_header_on_its_own_is_a_rule_with_no_conditions():
    # Not a mistake. An unrestricted fare points at exactly this, and skipping
    # it leaves the previous version of the rule in place with its conditions.
    load = read(head())
    assert len(load) == 1
    assert load.rules[0].provisions == ()
    assert load.rules[0].carrier == "LH"
    assert load.rules[0].number == "0100"


def test_the_records_after_a_header_belong_to_that_header():
    load = read(file_of(head(), advance(), minimum(days=3)))
    rule = load.rules[0]
    assert len(rule) == 2
    assert rule.has(Category.ADVANCE_RESERVATION)
    assert rule.has(Category.MINIMUM_STAY)


def test_a_second_header_closes_the_first_rule():
    load = read(file_of(head(number="0100"), advance(), head(number="0200"), minimum(days=3)))
    assert len(load) == 2
    first = load.get("LH", "0100")
    second = load.get("LH", "0200")
    assert first is not None and second is not None
    assert first.categories() == (Category.ADVANCE_RESERVATION,)
    assert second.categories() == (Category.MINIMUM_STAY,)


def test_a_category_record_before_any_header_is_an_orphan():
    load = read(file_of(advance(), head(), minimum(days=3)))
    assert load.orphans == 1
    assert len(load.problems) == 1
    assert "no rule above it" in load.problems[0].reason
    assert len(load.rules) == 1
    assert load.rules[0].categories() == (Category.MINIMUM_STAY,)


def test_a_truncated_file_shows_up_as_a_run_of_orphans():
    load = read(file_of(advance(), minimum(days=3), maximum()))
    assert load.orphans == 3
    assert len(load.rules) == 0


def test_advance_purchase_reads_its_three_numbers():
    load = read(file_of(head(), advance(reservation=7, after=3, before=21)))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, AdvancePurchase)
    assert provision.reservation_days == 7
    assert provision.ticketing_days_after_reservation == 3
    assert provision.ticketing_days_before_departure == 21


def test_a_blank_number_is_unrestricted_and_never_a_zero():
    # A zero would say the fare has to be bought on the day of departure.
    load = read(file_of(head(), advance(reservation=None)))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, AdvancePurchase)
    assert provision.reservation_days is None


def test_a_minimum_stay_can_be_days_or_a_weekday():
    load = read(file_of(head(), minimum(days=3), minimum(weekday="SUN")))
    days, weekday = load.rules[0].provisions
    assert isinstance(days, MinimumStay) and days.days == 3
    assert isinstance(weekday, MinimumStay) and weekday.weekday == "SUN"


def test_a_maximum_stay_reads_its_months_and_its_flag():
    load = read(file_of(head(), maximum(months=6, from_turnaround="Y")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, MaximumStay)
    assert provision.months == 6
    assert provision.from_turnaround


def test_a_flag_that_is_not_a_yes_is_a_no():
    load = read(file_of(head(), maximum(from_turnaround=None)))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, MaximumStay)
    assert not provision.from_turnaround


def test_a_sales_restriction_splits_its_country_list():
    load = read(file_of(head(), sales(countries="DE/AT/CH")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, SalesRestriction)
    assert provision.countries == ("DE", "AT", "CH")


def test_a_sales_restriction_reads_its_window_and_its_payment():
    load = read(file_of(head(), sales(payment="C", first=date(2025, 1, 1), last=date(2025, 6, 30))))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, SalesRestriction)
    assert provision.payment is FormOfPayment.CARD
    assert provision.first_sale == date(2025, 1, 1)
    assert provision.last_sale == date(2025, 6, 30)


def test_a_blank_payment_code_means_the_tariff_default():
    load = read(file_of(head(), sales(countries="DE")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, SalesRestriction)
    assert provision.payment is FormOfPayment.ANY


def test_a_penalty_reads_both_amounts_in_the_filed_currency():
    load = read(file_of(head(), penalty(before="50.00", after="100.00", currency="EUR")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, Penalty)
    assert provision.action is Action.CHANGE
    assert provision.before_departure == Money.of("50.00", "EUR")
    assert provision.after_departure == Money.of("100.00", "EUR")
    assert provision.permitted


def test_a_penalty_that_forbids_the_action_says_so():
    load = read(file_of(head(), penalty(permitted="N")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, Penalty)
    assert not provision.permitted


def test_a_penalty_scope_code_reads_into_the_scope():
    load = read(file_of(head(), penalty(applies="H")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, Penalty)
    assert provision.applies is Applies.HIGHEST_OF


def test_a_code_nobody_files_is_one_bad_record():
    load = read(file_of(head(), penalty(applies="Z"), minimum(days=3)))
    assert len(load.problems) == 1
    assert "no such code" in load.problems[0].reason
    assert load.rules[0].categories() == (Category.MINIMUM_STAY,)


def test_voluntary_changes_read_all_six_of_their_fields():
    load = read(
        file_of(head(), changes(basis="H", residual="C", keep="Y", same_carrier="Y", once="Y"))
    )
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, VoluntaryChange)
    assert provision.permitted
    assert provision.basis is RepricingBasis.HISTORICAL
    assert provision.residual is Residual.RETAINED
    assert provision.keep_fare_breaks
    assert provision.same_carrier_only
    assert provision.once_only


def test_changes_that_are_not_permitted_read_as_not_permitted():
    load = read(file_of(head(), changes(permitted="N")))
    provision = load.rules[0].provisions[0]
    assert isinstance(provision, VoluntaryChange)
    assert not provision.permitted


def test_a_rule_lands_in_the_store_under_its_own_key():
    store = RuleStore()
    load_into(store, file_of(head(), advance()))
    rule = store.get("LH", "0100", "001")
    assert rule.has(Category.ADVANCE_RESERVATION)
    assert len(store) == 1


def test_a_refiled_rule_replaces_the_whole_of_the_old_one():
    # There is no record that removes a category, so the second filing has to
    # take the first one's conditions with it.
    store = RuleStore()
    load_into(store, file_of(head(), advance(), minimum(days=3)))
    _, applied = load_into(store, file_of(head(), advance()))
    assert applied.replaced == 1
    rule = store.get("LH", "0100", "001")
    assert rule.categories() == (Category.ADVANCE_RESERVATION,)


def test_a_rule_refiled_with_nothing_in_it_clears_its_conditions():
    store = RuleStore()
    load_into(store, file_of(head(), advance(), minimum(days=3)))
    _, applied = load_into(store, head())
    assert applied.empty_rules == 1
    assert len(store.get("LH", "0100", "001")) == 0


def test_a_deleted_rule_leaves_the_store():
    store = RuleStore()
    load_into(store, file_of(head(), advance()))
    _, applied = load_into(store, head(action="D"))
    assert applied.deleted == 1
    assert store.find("LH", "0100", "001") is None


def test_a_delete_carries_no_conditions_with_it():
    load = read(file_of(head(action="D"), advance()))
    assert load.rules == []
    assert load.deleted == [("LH", "0100", "001")]


def test_a_delete_for_a_rule_we_never_held_is_counted_and_ignored():
    store = RuleStore()
    _, applied = load_into(store, head(action="D"))
    assert applied.unknown_deletes == 1
    assert applied.deleted == 0


def test_two_carriers_are_two_rules_even_at_the_same_number():
    store = RuleStore()
    load_into(store, file_of(head(carrier="LH"), advance(), head(carrier="UA"), minimum(days=3)))
    assert len(store) == 2
    assert store.numbers("LH") == ["0100"]


def test_reading_never_touches_the_store():
    store = RuleStore()
    load = read(file_of(head(), advance()))
    assert len(store) == 0
    assert apply(store, load).loaded == 1


def test_the_categories_in_a_file_are_counted_for_the_load_report():
    load = read(file_of(head(), advance(), minimum(days=3), head(number="0200"), advance()))
    assert categories_of(load) == {5: 2, 6: 1}


def test_the_categories_a_rule_does_not_file_can_be_listed():
    load = read(file_of(head(), advance()))
    wanted = (Category.ADVANCE_RESERVATION, Category.MINIMUM_STAY, Category.PENALTIES)
    assert missing_categories(load.rules[0], wanted) == (
        Category.MINIMUM_STAY,
        Category.PENALTIES,
    )


def test_a_load_reads_back_in_one_line():
    load = read(file_of(head(), advance(), head(number="0200", action="D")))
    assert str(load) == "1 rules, 1 deleted, 0 problems"


def test_what_was_applied_reads_back_in_one_line():
    store = RuleStore()
    _, applied = load_into(store, file_of(head(), advance()))
    assert str(applied) == (
        "1 rules loaded, 0 of them replacing one, 0 deleted, 0 with no conditions"
    )
