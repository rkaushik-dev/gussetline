from __future__ import annotations

import pytest

from gussetline.baggage import Verdict, compare, net_benefit, smallest, worth_of
from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.offers import Baggage

PIECE = Money.of("60.00", "EUR")
KILO = Money.of("12.00", "EUR")


def test_the_same_allowance_is_the_same():
    change = compare(Baggage(pieces=1), Baggage(pieces=1))
    assert change.verdict is Verdict.SAME
    assert not change.worth_saying


def test_a_larger_allowance_is_better():
    assert compare(Baggage(pieces=1), Baggage(pieces=2)).verdict is Verdict.BETTER


def test_a_smaller_allowance_is_worse_and_says_by_how_much():
    change = compare(Baggage(pieces=2), Baggage(pieces=1))
    assert change.verdict is Verdict.WORSE
    assert change.lost_pieces == 1
    assert change.why() == "1 fewer checked pieces"


def test_a_smaller_weight_allowance_says_the_kilos():
    change = compare(Baggage(kilos=30), Baggage(kilos=23))
    assert change.verdict is Verdict.WORSE
    assert change.lost_kilos == 7
    assert change.why() == "7 kilos less"


def test_weight_against_weight_compares_fine():
    assert compare(Baggage(kilos=23), Baggage(kilos=30)).verdict is Verdict.BETTER
    assert compare(Baggage(kilos=23), Baggage(kilos=23)).verdict is Verdict.SAME


def test_pieces_against_weight_is_not_comparable():
    # Whether thirty kilos beats two pieces depends on what the passenger
    # packed, and inventing a conversion invents an answer.
    change = compare(Baggage(pieces=2), Baggage(kilos=30))
    assert change.verdict is Verdict.NOT_COMPARABLE
    assert "not the same kind" in change.why()
    assert change.worth_saying


def test_a_fare_with_nothing_filed_is_unknown_and_not_nothing():
    # A fare with no allowance filed may include a bag or may not, and
    # quoting a saving against it without saying so is the complaint.
    change = compare(Baggage(pieces=2), Baggage())
    assert change.verdict is Verdict.UNKNOWN
    assert change.worth_saying
    assert "does not state an allowance" in change.why()


def test_neither_side_stated_is_unknown_too():
    assert compare(Baggage(), Baggage()).verdict is Verdict.UNKNOWN


def test_a_change_reads_back_in_one_line():
    assert str(compare(Baggage(pieces=2), Baggage(pieces=1))) == (
        "2PC to 1PC: 1 fewer checked pieces"
    )


def test_only_the_bad_verdicts_are_worth_saying():
    assert not Verdict.SAME.costs_the_passenger
    assert not Verdict.BETTER.costs_the_passenger
    assert Verdict.WORSE.costs_the_passenger
    assert Verdict.NOT_COMPARABLE.costs_the_passenger
    assert Verdict.UNKNOWN.costs_the_passenger


def test_a_journey_carries_the_least_of_its_sectors():
    # The bag is checked through, so the passenger meets the strictest
    # sector's allowance at the first desk.
    assert smallest([Baggage(pieces=2), Baggage(pieces=1)]) == Baggage(pieces=1)


def test_a_journey_of_one_sector_carries_that_sector():
    assert smallest([Baggage(kilos=23)]) == Baggage(kilos=23)


def test_a_sector_with_nothing_filed_does_not_drag_the_journey_down():
    assert smallest([Baggage(pieces=2), Baggage()]) == Baggage(pieces=2)


def test_a_journey_with_nothing_filed_anywhere_states_nothing():
    assert not smallest([Baggage(), Baggage()]).is_stated
    assert not smallest([]).is_stated


def test_two_kinds_across_a_journey_state_nothing_rather_than_guessing():
    # The honest answer to what a two piece and a thirty kilo journey carries
    # is that somebody has to look.
    assert not smallest([Baggage(pieces=2), Baggage(kilos=30)]).is_stated


def test_a_lost_piece_costs_the_rate_per_piece():
    change = compare(Baggage(pieces=2), Baggage(pieces=1))
    assert worth_of(change, PIECE) == Money.of("60.00", "EUR")


def test_two_lost_pieces_cost_twice():
    change = compare(Baggage(pieces=3), Baggage(pieces=1))
    assert worth_of(change, PIECE) == Money.of("120.00", "EUR")


def test_lost_kilos_cost_the_rate_per_kilo():
    change = compare(Baggage(kilos=30), Baggage(kilos=23))
    assert worth_of(change, PIECE, KILO) == Money.of("84.00", "EUR")


def test_lost_kilos_with_no_rate_are_refused_rather_than_priced_at_nothing():
    change = compare(Baggage(kilos=30), Baggage(kilos=23))
    with pytest.raises(ValidationError, match="rate per kilo"):
        worth_of(change, PIECE)


def test_a_better_allowance_is_not_a_saving_to_be_added():
    # The passenger was not going to be paid for the bag they did not check.
    change = compare(Baggage(pieces=1), Baggage(pieces=2))
    assert worth_of(change, PIECE) == Money.zero("EUR")


def test_the_same_allowance_costs_nothing():
    assert worth_of(compare(Baggage(pieces=1), Baggage(pieces=1)), PIECE) == Money.zero("EUR")


def test_an_unknown_allowance_costs_nothing_but_is_still_worth_saying():
    change = compare(Baggage(pieces=2), Baggage())
    assert worth_of(change, PIECE) == Money.zero("EUR")
    assert change.worth_saying


def test_the_benefit_is_the_saving_with_the_lost_bag_taken_off():
    # A hundred euro drop onto a fare with one fewer bag is a saving of
    # about forty, and quoting the hundred is how it becomes a complaint.
    change = compare(Baggage(pieces=2), Baggage(pieces=1))
    assert net_benefit(Money.of("100.00", "EUR"), change, PIECE) == Money.of("40.00", "EUR")


def test_a_saving_that_does_not_cover_the_lost_bag_goes_negative():
    change = compare(Baggage(pieces=3), Baggage(pieces=1))
    assert net_benefit(Money.of("100.00", "EUR"), change, PIECE).is_negative


def test_a_saving_with_no_allowance_lost_is_the_whole_saving():
    change = compare(Baggage(pieces=1), Baggage(pieces=1))
    assert net_benefit(Money.of("100.00", "EUR"), change, PIECE) == Money.of("100.00", "EUR")


def test_a_benefit_worked_out_across_currencies_is_refused():
    change = compare(Baggage(pieces=2), Baggage(pieces=1))
    with pytest.raises(ValidationError, match="one currency"):
        net_benefit(Money.of("100.00", "GBP"), change, PIECE)
