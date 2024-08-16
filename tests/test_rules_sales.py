from __future__ import annotations

from datetime import date

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.discounts import Passenger, PassengerType
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.rules import Context
from gussetline.rules.sales import Eligibility, FormOfPayment, Sale, SalesRestriction

TICKETING = date(2025, 2, 14)


def out():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def context(**over) -> Context:
    fields = {
        "itinerary": Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")]
        ),
        "component": out(),
        "ticketing": TICKETING,
    }
    fields.update(over)
    return Context(**fields)


def test_a_country_of_sale_has_to_be_one_we_know():
    with pytest.raises(ValidationError):
        Sale("QQ")
    assert Sale(" de ").country == "DE"


def test_an_eligibility_filing_with_nothing_in_it_does_not_apply():
    assert not Eligibility().evaluate(context()).applied


def test_an_unknown_residency_country_is_refused():
    with pytest.raises(ValidationError):
        Eligibility(residency="QQ")


def test_a_passenger_of_the_named_type_qualifies():
    rule = Eligibility(passenger_types=(PassengerType.STUDENT,))
    student = context(passenger=Passenger(PassengerType.STUDENT))
    assert not rule.evaluate(student).failed


def test_a_passenger_of_another_type_does_not():
    rule = Eligibility(passenger_types=(PassengerType.STUDENT,))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details == {"wanted": ["STU"], "had": "ADT"}


def test_an_age_condition_with_no_date_of_birth_fails_rather_than_assuming():
    rule = Eligibility(min_age=60)
    outcome = rule.evaluate(context(passenger=Passenger(PassengerType.SENIOR)))
    assert outcome.failed
    assert "no date of birth" in outcome.message


def test_a_passenger_inside_the_age_range_qualifies():
    rule = Eligibility(min_age=60)
    senior = context(passenger=Passenger(PassengerType.SENIOR, date(1955, 1, 1)))
    assert not rule.evaluate(senior).failed


def test_a_passenger_under_the_minimum_age_does_not():
    rule = Eligibility(min_age=60)
    outcome = rule.evaluate(context(passenger=Passenger(PassengerType.SENIOR, date(1985, 1, 1))))
    assert outcome.failed
    assert outcome.details["age"] == 40


def test_a_passenger_over_the_maximum_age_does_not():
    rule = Eligibility(max_age=24)
    outcome = rule.evaluate(context(passenger=Passenger(PassengerType.YOUTH, date(1985, 1, 1))))
    assert outcome.failed


def test_a_residency_condition_needs_the_residency_stated():
    rule = Eligibility(residency="DE")
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details == {"wanted": "DE", "had": "unstated"}


def test_a_resident_of_the_right_country_qualifies():
    rule = Eligibility(residency="DE", resident_of="DE")
    assert not rule.evaluate(context()).failed


def test_a_resident_of_another_country_does_not():
    rule = Eligibility(residency="DE", resident_of="GB")
    assert rule.evaluate(context()).failed


def test_a_membership_condition_fails_until_it_is_held():
    rule = Eligibility(membership="senator")
    assert rule.evaluate(context()).failed
    held = Eligibility(membership="senator", holds_membership=True)
    assert not held.evaluate(context()).failed


def test_a_sales_filing_with_nothing_in_it_does_not_apply():
    assert not SalesRestriction().evaluate(context()).applied


def test_a_filing_names_permitted_countries_or_excluded_ones_and_not_both():
    with pytest.raises(ValidationError):
        SalesRestriction(countries=("DE",), excluded_countries=("GB",))


def test_an_unknown_country_in_a_filing_is_refused():
    with pytest.raises(ValidationError):
        SalesRestriction(countries=("QQ",))


def test_a_restricted_fare_with_no_sale_details_fails_rather_than_passing():
    rule = SalesRestriction(countries=("DE",))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert "no sale details" in outcome.message


def test_a_sale_in_a_permitted_country_passes():
    rule = SalesRestriction(countries=("DE",), sale=Sale("DE"))
    assert not rule.evaluate(context()).failed


def test_a_sale_in_another_country_fails_and_names_both():
    rule = SalesRestriction(countries=("DE",), sale=Sale("GB"))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details == {"sold_in": "GB", "permitted": ["DE"]}


def test_an_excluded_country_fails_and_everything_else_passes():
    rule = SalesRestriction(excluded_countries=("US",), sale=Sale("US"))
    assert rule.evaluate(context()).failed
    elsewhere = SalesRestriction(excluded_countries=("US",), sale=Sale("DE"))
    assert not elsewhere.evaluate(context()).failed


def test_an_agency_off_the_list_fails():
    rule = SalesRestriction(agencies=("ABC123",), sale=Sale("DE", agency="XYZ999"))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["agency"] == "XYZ999"


def test_an_agency_on_the_list_passes_whatever_case_it_was_given_in():
    rule = SalesRestriction(agencies=("abc123",), sale=Sale("DE", agency="AbC123"))
    assert not rule.evaluate(context()).failed


def test_a_form_of_payment_condition_is_checked():
    rule = SalesRestriction(payment=FormOfPayment.CARD, sale=Sale("DE", payment=FormOfPayment.CASH))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details == {"wanted": "card", "had": "cash"}


def test_a_matching_form_of_payment_passes():
    rule = SalesRestriction(payment=FormOfPayment.CARD, sale=Sale("DE", payment=FormOfPayment.CARD))
    assert not rule.evaluate(context()).failed


def test_a_sale_before_the_fare_goes_on_sale_fails():
    rule = SalesRestriction(first_sale=date(2025, 3, 1), sale=Sale("DE"))
    outcome = rule.evaluate(context())
    assert outcome.failed
    assert outcome.details["first_sale"] == "2025-03-01"


def test_a_sale_after_the_fare_comes_off_fails():
    rule = SalesRestriction(last_sale=date(2025, 1, 31), sale=Sale("DE"))
    assert rule.evaluate(context()).failed


def test_the_sale_date_falls_back_to_the_ticketing_date():
    rule = SalesRestriction(last_sale=date(2025, 1, 31), sale=Sale("DE", on=date(2025, 1, 5)))
    assert not rule.evaluate(context()).failed
