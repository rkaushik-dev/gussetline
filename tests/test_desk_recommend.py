from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from conftest import segment
from gussetline.components import FareComponent
from gussetline.desk.recommend import (
    Recommendation,
    State,
    expire_stale,
    open_ones,
    raise_from,
    realised_total,
    take_rate,
)
from gussetline.desk.reprice import decide
from gussetline.desk.ticket import Coupon, Ticket
from gussetline.desk.watch import Observation, Watch
from gussetline.errors import ConflictError, ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.rules.base import Context
from gussetline.rules.changes import Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty

TODAY = date(2025, 6, 24)
DEPARTURE = date(2025, 7, 4)


def ticket() -> Ticket:
    return Ticket(
        number="220-1234567890",
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        coupons=(
            Coupon(
                position=0,
                carrier="LH",
                flight_number="400",
                origin="FRA",
                destination="JFK",
                departure=datetime(2025, 7, 4, 10, 25),
                booking_class="K",
                fare_basis="KLWAP7RT",
            ),
        ),
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_fare=Money.of("840.00", "EUR"),
        total_paid=Money.of("1090.83", "EUR"),
    )


def watch(seen=("700.00", "700.00")) -> Watch:
    row = Watch(
        ticket=ticket(),
        origin="FRA",
        destination="JFK",
        departure=DEPARTURE,
        threshold=Money.of("50.00", "EUR"),
        hysteresis=2,
    )
    for index, amount in enumerate(seen):
        row.record(
            Observation(
                seen_on=date(2025, 6, 20 + index),
                amount=Money.of(amount, "EUR"),
                fingerprint="f" * 32,
            )
        )
    return row


def context() -> Context:
    return Context(
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")]
        ),
        component=FareComponent(
            (segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"),)
        ),
        ticketing=date(2025, 2, 14),
        today=TODAY,
        reissue=True,
    )


def a_decision(seen=("700.00", "700.00")):
    return decide(
        watch(seen),
        VoluntaryChange(residual=Residual.REFUNDABLE),
        [Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR"))],
        context(),
        TODAY,
    )


def recommendation(**over) -> Recommendation:
    return raise_from(a_decision(), TODAY, **over)


def test_a_decision_that_is_not_actionable_does_not_become_a_recommendation():
    with pytest.raises(ValidationError):
        raise_from(a_decision(seen=("840.00",)), TODAY)


def test_a_recommendation_copies_every_number_it_was_made_on():
    made = recommendation()
    assert made.paid == Money.of("840.00", "EUR")
    assert made.new_fare == Money.of("700.00", "EUR")
    assert made.penalty == Money.of("50.00", "EUR")
    assert made.benefit == Money.of("90.00", "EUR")
    assert made.fare_bases == ("KLWAP7RT",)
    assert made.fingerprint == "f" * 32


def test_a_fresh_recommendation_is_open_and_saves_nothing_yet():
    made = recommendation()
    assert made.state is State.OPEN
    assert not made.state.is_settled
    assert made.realised == Money.zero("EUR")
    assert str(made) == "220-1234567890 open, worth 90.00 EUR"


def test_a_recommendation_is_in_one_currency():
    with pytest.raises(ValidationError):
        Recommendation(
            ticket_number="x",
            series_key="k",
            raised_on=TODAY,
            paid=Money.of("840.00", "EUR"),
            new_fare=Money.of("700.00", "USD"),
            penalty=Money.of("50.00", "EUR"),
            benefit=Money.of("90.00", "EUR"),
            residual=Residual.REFUNDABLE,
        )


def test_a_recommendation_lasts_at_least_a_day():
    with pytest.raises(ValidationError):
        recommendation(lifetime=0)


def test_taking_a_recommendation_records_who_and_when():
    made = recommendation()
    made.take(date(2025, 6, 25), "anna", "reissued on LH")
    assert made.state is State.TAKEN
    assert made.decided_by == "anna"
    assert made.decided_on == date(2025, 6, 25)
    assert made.realised == Money.of("90.00", "EUR")
    assert str(made) == "220-1234567890 taken by anna"


def test_killing_a_recommendation_saves_nothing_and_is_not_a_failure():
    made = recommendation()
    made.kill(date(2025, 6, 25), "anna", "customer wanted the later flight")
    assert made.state is State.KILLED
    assert made.realised == Money.zero("EUR")


def test_a_decision_names_who_made_it():
    made = recommendation()
    with pytest.raises(ValidationError):
        made.take(TODAY, "  ")


def test_a_settled_recommendation_cannot_be_settled_again():
    made = recommendation()
    made.take(TODAY, "anna")
    with pytest.raises(ConflictError) as caught:
        made.kill(TODAY, "bruno")
    assert caught.value.details["by"] == "anna"


def test_a_recommendation_expires_after_its_lifetime():
    made = recommendation()
    assert made.expires_on == date(2025, 6, 27)
    assert not made.stale_on(date(2025, 6, 27))
    assert made.stale_on(date(2025, 6, 28))


def test_expiring_records_the_clock_as_the_decider():
    made = recommendation()
    made.expire(date(2025, 6, 28))
    assert made.state is State.EXPIRED
    assert made.decided_by == "the clock"
    assert made.realised == Money.zero("EUR")


def test_a_settled_recommendation_does_not_expire():
    made = recommendation()
    made.take(TODAY, "anna")
    with pytest.raises(ConflictError):
        made.expire(date(2025, 6, 28))


def test_a_longer_lifetime_moves_the_expiry():
    assert recommendation(lifetime=7).expires_on == date(2025, 7, 1)


def test_the_stale_ones_are_closed_together():
    fresh = recommendation()
    old = recommendation()
    old.raised_on = date(2025, 6, 10)
    taken = recommendation()
    taken.take(TODAY, "anna")
    assert expire_stale([fresh, old, taken], TODAY) == 1
    assert old.state is State.EXPIRED
    assert fresh.state is State.OPEN
    assert taken.state is State.TAKEN


def test_the_open_ones_can_be_listed():
    fresh = recommendation()
    taken = recommendation()
    taken.take(TODAY, "anna")
    assert open_ones([fresh, taken]) == [fresh]


def test_only_the_taken_ones_count_toward_savings():
    taken = recommendation()
    taken.take(TODAY, "anna")
    killed = recommendation()
    killed.kill(TODAY, "anna")
    expired = recommendation()
    expired.expire(date(2025, 6, 28))
    assert realised_total([taken, killed, expired, recommendation()], "EUR") == Money.of(
        "90.00", "EUR"
    )


def test_nothing_settled_saves_nothing():
    assert realised_total([], "EUR") == Money.zero("EUR")
    assert realised_total([recommendation()], "EUR") == Money.zero("EUR")


def test_the_take_rate_is_measured_against_the_settled_ones():
    taken = recommendation()
    taken.take(TODAY, "anna")
    killed = recommendation()
    killed.kill(TODAY, "anna")
    still_open = recommendation()
    assert take_rate([taken, killed, still_open]) == Decimal("0.50")


def test_the_take_rate_of_nothing_settled_is_nothing():
    assert take_rate([]) == Decimal(0)
    assert take_rate([recommendation()]) == Decimal(0)


def test_an_expired_recommendation_counts_against_the_take_rate():
    taken = recommendation()
    taken.take(TODAY, "anna")
    expired = recommendation()
    expired.expire(date(2025, 6, 28))
    assert take_rate([taken, expired]) == Decimal("0.50")


def test_a_retained_residual_carries_through_to_the_recommendation():
    made = raise_from(
        decide(
            watch(),
            VoluntaryChange(residual=Residual.RETAINED),
            [Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR"))],
            context(),
            TODAY,
        ),
        TODAY,
    )
    assert made.residual is Residual.RETAINED
    assert made.benefit == Money.of("72.00", "EUR")
