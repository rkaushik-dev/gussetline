from __future__ import annotations

from datetime import date

import pytest

from conftest import fare, segment
from gussetline.components import FareComponent
from gussetline.discounts import Passenger, PassengerType
from gussetline.errors import NoRuleFound, ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import (
    Answer,
    Category,
    Context,
    Provision,
    RuleSet,
    RuleStore,
    evaluate,
    fails,
    passes,
    permitted,
    refusals,
    silent,
)
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def round_trip():
    return Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
        ]
    )


def context(**over):
    fields = {
        "itinerary": round_trip(),
        "component": out(),
        "ticketing": TICKETING,
    }
    fields.update(over)
    return Context(**fields)


class Always(Provision):
    category = Category.ADVANCE_RESERVATION

    def evaluate(self, ctx):
        return passes(self.category, "nothing to check")


class Never(Provision):
    category = Category.MINIMUM_STAY

    def evaluate(self, ctx):
        return fails(self.category, "three nights and it wants seven", needed=7, had=3)


class Quiet(Provision):
    category = Category.BLACKOUTS

    def evaluate(self, ctx):
        return silent(self.category)


def test_the_category_numbers_are_the_tariffs_own():
    assert Category.ADVANCE_RESERVATION.value == 5
    assert Category.MINIMUM_STAY.value == 6
    assert Category.PENALTIES.value == 16
    assert Category.VOLUNTARY_CHANGES.value == 31
    assert Category.MINIMUM_STAY.label == "cat 6"


def test_the_three_answers_are_distinguishable():
    assert passes(Category.MINIMUM_STAY, "x").answer is Answer.PASSES
    assert fails(Category.MINIMUM_STAY, "x").failed
    assert not silent(Category.MINIMUM_STAY).applied
    assert not silent(Category.MINIMUM_STAY).failed


def test_a_provision_that_does_not_apply_is_not_a_failure():
    outcome = silent(Category.BLACKOUTS)
    assert not outcome.failed
    assert not outcome.applied
    assert permitted([outcome])


def test_an_outcome_reads_back_as_its_category_and_verdict():
    assert str(fails(Category.MINIMUM_STAY, "wants seven")) == "cat 6 fails, wants seven"


def test_the_context_reads_the_departure_off_the_component():
    assert context().departure == date(2025, 3, 4)
    assert context().journey_departure == date(2025, 3, 4)


def test_the_return_departure_is_the_first_sector_home():
    assert context().return_departure == date(2025, 3, 18)


def test_a_one_way_has_no_return_departure():
    one_way = Itinerary(
        [segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")]
    )
    assert context(itinerary=one_way).return_departure is None


def test_the_return_departure_of_a_two_sector_leg_home_is_the_first_of_them():
    trip = Itinerary(
        [
            segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
            segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
            segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35"),
        ]
    )
    assert context(itinerary=trip).return_departure == date(2025, 3, 18)


def test_the_context_defaults_to_an_adult():
    assert context().passenger.kind is PassengerType.ADULT
    assert context(passenger=Passenger(PassengerType.CHILD)).passenger.kind is PassengerType.CHILD


def test_a_rule_has_a_number():
    with pytest.raises(ValidationError):
        RuleSet("LH", "  ")


def test_a_rule_lists_the_categories_it_carries():
    rule = RuleSet("LH", "1100", (Always(), Never(), Quiet()))
    assert rule.categories() == (
        Category.ADVANCE_RESERVATION,
        Category.MINIMUM_STAY,
        Category.BLACKOUTS,
    )
    assert rule.has(Category.MINIMUM_STAY)
    assert not rule.has(Category.PENALTIES)
    assert len(rule) == 3


def test_provisions_can_be_read_back_by_category():
    rule = RuleSet("LH", "1100", (Always(), Always(), Never()))
    assert len(rule.for_category(Category.ADVANCE_RESERVATION)) == 2


def test_a_rule_reads_back_with_its_categories():
    assert str(RuleSet("LH", "1100", (Always(), Never()))) == "LH rule 1100 [5,6]"
    assert str(RuleSet("LH", "1100")) == "LH rule 1100 [empty]"


def test_the_store_finds_a_rule_by_carrier_number_and_tariff():
    store = RuleStore()
    store.add(RuleSet("LH", "1100"))
    assert store.get("lh", "1100").number == "1100"
    assert len(store) == 1


def test_a_rule_that_is_not_filed_raises_and_names_what_was_asked_for():
    with pytest.raises(NoRuleFound) as caught:
        RuleStore().get("LH", "9999")
    assert caught.value.details["number"] == "9999"


def test_find_answers_none_instead_of_raising():
    assert RuleStore().find("LH", "9999") is None


def test_the_tariff_number_is_part_of_the_key():
    store = RuleStore()
    store.add(RuleSet("LH", "1100", tariff="1"))
    store.add(RuleSet("LH", "1100", tariff="8"))
    assert len(store) == 2
    assert store.find("LH", "1100", "3") is None


def test_every_provision_runs_and_nothing_short_circuits():
    outcomes = evaluate([Never(), Always(), Never()], context())
    assert len(outcomes) == 3
    assert len(refusals(outcomes)) == 2
    assert not permitted(outcomes)


def test_a_rule_with_nothing_filed_permits_everything():
    assert permitted(evaluate([], context()))
    assert permitted(evaluate([Quiet()], context()))


def test_failures_and_passes_are_both_recorded():
    trace = Trace()
    evaluate([Always(), Never()], context(), trace)
    steps = trace.for_stage(Stage.RULE)
    assert len(steps) == 2
    assert trace.stage_failed(Stage.RULE)


def test_a_provision_that_does_not_apply_is_not_recorded():
    trace = Trace()
    evaluate([Quiet()], context(), trace)
    assert trace.for_stage(Stage.RULE) == ()


def test_the_details_ride_along_to_the_trace():
    trace = Trace()
    evaluate([Never()], context(), trace)
    assert trace.for_stage(Stage.RULE)[0].details == {"needed": 7, "had": 3}


def test_a_fare_names_the_rule_it_points_at():
    assert fare().rule_number == "1100"
