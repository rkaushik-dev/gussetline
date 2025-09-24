from __future__ import annotations

from datetime import date

import pytest

from gussetline.errors import ValidationError
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.negotiated import (
    Account,
    Negotiated,
    NegotiatedStore,
    best_for,
    saving_against,
    visible_to,
    with_inherited_rule,
)
from gussetline.tariff import Fare, FareType, OwRt

ON = date(2025, 10, 20)


def fare(
    amount="420.00",
    basis="KLWAP7RT",
    fare_type=FareType.PUBLISHED,
    rule="0100",
    routing="0011",
    origin="FRA",
    destination="JFK",
    tariff="1",
    footnote="12",
) -> Fare:
    return Fare(
        carrier="LH",
        origin=origin,
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, "EUR"),
        owrt=OwRt.ROUND_TRIP_HALF,
        global_indicator=GlobalIndicator.AT,
        rule_number=rule,
        routing_number=routing,
        tariff_number=tariff,
        footnote=footnote,
        fare_type=fare_type,
        effective=date(2025, 1, 1),
        discontinue=date(2025, 12, 31),
    )


def private(amount="320.00", basis="KCORP", account="ACME", inherits="", rule="") -> Negotiated:
    return Negotiated(
        account=Account(code=account, name="Acme Industries"),
        fare=fare(
            amount=amount,
            basis=basis,
            fare_type=FareType.NEGOTIATED,
            rule=rule,
            routing="",
            footnote="",
        ),
        inherits_from=inherits,
    )


def test_an_account_is_a_code_and_a_name():
    made = Account(code="acme", name="Acme Industries")
    assert made.code == "ACME"
    assert str(made) == "ACME Acme Industries"


def test_an_account_with_no_code_is_refused():
    with pytest.raises(ValidationError, match="has a code"):
        Account(code="  ")


def test_an_account_code_nobody_could_type_is_refused():
    with pytest.raises(ValidationError, match="not an account code"):
        Account(code="A" * 40)


def test_a_public_fare_cannot_be_filed_as_a_negotiated_one():
    with pytest.raises(ValidationError, match="filed as one"):
        Negotiated(account=Account(code="ACME"), fare=fare())


def test_a_private_fare_reads_back_with_the_account_it_belongs_to():
    assert "for ACME" in str(private())


def test_a_caller_with_the_code_sees_the_fare():
    store = NegotiatedStore()
    store.add(private())
    assert store.for_account(["ACME"], "LH", "FRA", "JFK") != []


def test_a_caller_with_no_code_sees_nothing_at_all():
    # Not the cheapest published one and not a fallback. A caller without a
    # code has no business seeing another customer's terms.
    store = NegotiatedStore()
    store.add(private())
    assert store.for_account([], "LH", "FRA", "JFK") == []
    assert store.for_account([""], "LH", "FRA", "JFK") == []


def test_a_caller_with_the_wrong_code_sees_nothing_either():
    store = NegotiatedStore()
    store.add(private(account="ACME"))
    assert store.for_account(["OTHERCO"], "LH", "FRA", "JFK") == []


def test_a_caller_may_present_more_than_one_code():
    store = NegotiatedStore()
    store.add_many([private(account="ACME"), private(account="BETA", basis="KBETA")])
    assert len(store.for_account(["ACME", "BETA"], "LH", "FRA", "JFK")) == 2


def test_the_same_code_twice_is_not_two_fares():
    store = NegotiatedStore()
    store.add(private())
    assert len(store.for_account(["ACME", "acme"], "LH", "FRA", "JFK")) == 1


def test_the_fares_come_back_cheapest_first():
    store = NegotiatedStore()
    store.add_many([private(amount="360.00", basis="KB"), private(amount="320.00", basis="KA")])
    found = store.for_account(["ACME"], "LH", "FRA", "JFK")
    assert [f.fare.fare_basis for f in found] == ["KA", "KB"]


def test_the_market_is_folded_the_way_the_tariff_files_it():
    store = NegotiatedStore()
    store.add(private())
    assert store.for_account(["ACME"], "LH", "FRA", "EWR") != []


def test_a_fare_outside_its_sale_window_is_not_shown():
    store = NegotiatedStore()
    store.add(private())
    assert store.for_account(["ACME"], "LH", "FRA", "JFK", on=date(2026, 6, 1)) == []
    assert store.for_account(["ACME"], "LH", "FRA", "JFK", on=ON) != []


def test_the_store_lists_the_accounts_it_holds_fares_for():
    store = NegotiatedStore()
    store.add_many([private(account="ACME"), private(account="BETA", basis="KBETA")])
    assert store.accounts() == ["ACME", "BETA"]
    assert len(store) == 2


def test_whether_a_fare_is_visible_can_be_asked_directly():
    filed = private(account="ACME")
    assert visible_to(filed, ["acme"])
    assert not visible_to(filed, ["beta"])
    assert not visible_to(filed, [])


def test_a_private_fare_takes_the_rule_of_the_public_fare_it_names():
    # The carrier does not refile twenty categories to change a number.
    filed = private(inherits="KLWAP7RT")
    built = with_inherited_rule(filed, [fare()])
    assert built.rule_number == "0100"
    assert built.tariff_number == "1"
    assert built.footnote == "12"


def test_the_inherited_fare_keeps_its_own_amount_and_basis():
    built = with_inherited_rule(private(amount="320.00", inherits="KLWAP7RT"), [fare()])
    assert built.amount == Money.of("320.00", "EUR")
    assert built.fare_basis == "KCORP"
    assert built.fare_type is FareType.NEGOTIATED


def test_the_inherited_routing_is_used_when_the_private_fare_files_none():
    built = with_inherited_rule(private(inherits="KLWAP7RT"), [fare(routing="0011")])
    assert built.routing_number == "0011"


def test_a_private_fare_with_a_rule_of_its_own_keeps_it():
    filed = private(rule="0900")
    assert with_inherited_rule(filed, [fare()]).rule_number == "0900"


def test_a_private_fare_with_no_rule_and_nothing_to_inherit_is_refused():
    # A fully flexible ticket at a discount fare price is the most expensive
    # way to be wrong here.
    with pytest.raises(ValidationError, match="rule of its own"):
        with_inherited_rule(private(), [fare()])


def test_inheriting_from_a_fare_nobody_loaded_is_refused_by_name():
    with pytest.raises(ValidationError, match="not loaded"):
        with_inherited_rule(private(inherits="KGONE"), [fare()])


def test_a_negotiated_fare_beats_a_published_one_at_the_same_price():
    # The opposite of the add-on rule, for the opposite reason.
    chosen = best_for([fare(amount="320.00")], [private(amount="320.00")])
    assert chosen is not None
    assert chosen.fare_basis == "KCORP"


def test_a_cheaper_published_fare_still_wins():
    chosen = best_for([fare(amount="300.00", basis="KPUB")], [private(amount="320.00")])
    assert chosen is not None
    assert chosen.fare_basis == "KPUB"


def test_with_no_private_fare_the_published_one_is_what_there_is():
    chosen = best_for([fare()], [])
    assert chosen is not None
    assert chosen.fare_basis == "KLWAP7RT"


def test_with_no_published_fare_the_private_one_is_what_there_is():
    chosen = best_for([], [private()])
    assert chosen is not None
    assert chosen.fare_basis == "KCORP"


def test_with_nothing_at_all_there_is_nothing():
    assert best_for([], []) is None


def test_the_cheapest_of_several_private_fares_is_the_one_chosen():
    chosen = best_for([], [private(amount="360.00", basis="KB"), private(amount="320.00")])
    assert chosen is not None
    assert chosen.amount == Money.of("320.00", "EUR")


def test_the_saving_is_what_the_private_fare_is_worth_over_the_public_one():
    assert saving_against([fare(amount="420.00")], private(amount="320.00")) == Money.of(
        "100.00", "EUR"
    )


def test_a_private_fare_dearer_than_the_public_one_saves_nothing():
    assert saving_against([fare(amount="300.00")], private(amount="320.00")) == Money.zero("EUR")


def test_with_nothing_published_there_is_nothing_to_save_against():
    assert saving_against([], private()) == Money.zero("EUR")
