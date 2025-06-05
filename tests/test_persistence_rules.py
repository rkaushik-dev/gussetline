from __future__ import annotations

import pytest

from gussetline.money import Money
from gussetline.persistence.unitofwork import in_memory
from gussetline.rules.base import Category, RuleSet
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.dates import Blackout
from gussetline.rules.penalties import Action, Penalty
from gussetline.rules.purchase import AdvancePurchase
from gussetline.rules.stay import MaximumStay, MinimumStay


@pytest.fixture
def database():
    return in_memory()


def rule(carrier="LH", number="0100", tariff="1", *provisions) -> RuleSet:
    return RuleSet(carrier=carrier, number=number, tariff=tariff, provisions=tuple(provisions))


def full(carrier="LH", number="0100") -> RuleSet:
    return rule(
        carrier,
        number,
        "1",
        AdvancePurchase(reservation_days=7, ticketing_days_before_departure=21),
        MinimumStay(weekday="SUN"),
        MaximumStay(months=6),
        Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR")),
        VoluntaryChange(basis=RepricingBasis.CURRENT, residual=Residual.REFUNDABLE),
    )


def test_a_rule_with_nothing_in_it_still_saves(database):
    with database.unit() as work:
        work.rules.save(rule())
        work.commit()

    with database.unit() as work:
        back = work.rules.get("LH", "0100")
    assert back is not None
    assert back.provisions == ()


def test_a_rule_comes_back_with_every_provision_it_went_in_with(database):
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        back = work.rules.get("LH", "0100")
    assert back == full()


def test_the_categories_come_back_in_the_order_they_were_filed(database):
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        back = work.rules.get("LH", "0100")
    assert back is not None
    assert back.categories() == (
        Category.ADVANCE_RESERVATION,
        Category.MINIMUM_STAY,
        Category.MAXIMUM_STAY,
        Category.PENALTIES,
        Category.VOLUNTARY_CHANGES,
    )


def test_a_refiled_rule_takes_the_old_provisions_with_it(database):
    # The carrier sends the categories it now has. Merging would leave a
    # minimum stay nobody filed, which is the hardest wrong answer to find.
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        work.rules.save(rule("LH", "0100", "1", AdvancePurchase(reservation_days=14)))
        work.commit()

    with database.unit() as work:
        back = work.rules.get("LH", "0100")
        assert work.rules.count() == 1
    assert back is not None
    assert back.categories() == (Category.ADVANCE_RESERVATION,)


def test_a_rule_that_is_not_there_is_nothing(database):
    with database.unit() as work:
        assert work.rules.get("LH", "9999") is None


def test_two_tariffs_at_one_number_are_two_rules(database):
    with database.unit() as work:
        work.rules.save(rule("LH", "0100", "1", MinimumStay(days=3)))
        work.rules.save(rule("LH", "0100", "2", MinimumStay(days=7)))
        work.commit()

    with database.unit() as work:
        one = work.rules.get("LH", "0100", "1")
        two = work.rules.get("LH", "0100", "2")
        assert work.rules.count() == 2
    assert one is not None and two is not None
    assert one.provisions[0] != two.provisions[0]


def test_a_provision_with_no_codec_is_counted_rather_than_swallowed(database):
    # A rule stored without its category 11 permits travel on a day the
    # carrier blacked out, so the operator has to be told.
    with database.unit() as work:
        _, dropped = work.rules.save(rule("LH", "0100", "1", MinimumStay(days=3), Blackout()))
        work.commit()
    assert dropped == 1

    with database.unit() as work:
        back = work.rules.get("LH", "0100")
    assert back is not None
    assert len(back) == 1


def test_a_whole_file_saves_and_says_what_it_dropped(database):
    with database.unit() as work:
        written, dropped = work.rules.save_all(
            [full("LH", "0100"), rule("LH", "0200", "1", Blackout())]
        )
        work.commit()
    assert written == 2
    assert dropped == 1


def test_a_deleted_rule_leaves_the_database(database):
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        assert work.rules.delete("LH", "0100")
        work.commit()

    with database.unit() as work:
        assert work.rules.count() == 0
        assert work.rules.get("LH", "0100") is None


def test_deleting_a_rule_takes_its_provisions_with_it(database):
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        work.rules.delete("LH", "0100")
        work.commit()

    with database.unit() as work:
        assert work.rules.categories_held() == {}


def test_deleting_something_that_is_not_there_says_so(database):
    with database.unit() as work:
        assert not work.rules.delete("LH", "9999")


def test_the_store_for_a_carrier_holds_every_rule_it_filed(database):
    with database.unit() as work:
        work.rules.save_all([full("LH", "0100"), full("LH", "0200"), full("UA", "0100")])
        work.commit()

    with database.unit() as work:
        store = work.rules.store_for(["LH"])
    assert len(store) == 2
    assert store.numbers("LH") == ["0100", "0200"]
    assert store.find("UA", "0100") is None


def test_the_store_carries_the_provisions_and_not_only_the_rules(database):
    with database.unit() as work:
        work.rules.save(full())
        work.commit()

    with database.unit() as work:
        store = work.rules.store_for(["LH"])
    found = store.get("LH", "0100")
    assert len(found) == 5
    assert found.has(Category.PENALTIES)


def test_the_store_for_no_carriers_is_empty_without_a_query(database):
    with database.unit() as work:
        assert len(work.rules.store_for([])) == 0


def test_the_categories_held_are_counted_for_a_load_report(database):
    with database.unit() as work:
        work.rules.save_all([full("LH", "0100"), rule("LH", "0200", "1", MinimumStay(days=3))])
        work.commit()

    with database.unit() as work:
        held = work.rules.categories_held()
    assert held[int(Category.MINIMUM_STAY)] == 2
    assert held[int(Category.PENALTIES)] == 1


def test_a_rule_that_is_never_committed_is_not_there(database):
    with database.unit() as work:
        work.rules.save(full())

    with database.unit() as work:
        assert work.rules.count() == 0
