from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from conftest import segment
from gussetline.components import FareComponent
from gussetline.desk.recommend import Recommendation, State
from gussetline.desk.session import SUPERSEDE_MARGIN, run
from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.desk.watch import Observation, Retired, Watch
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.rules.base import Context
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty

TODAY = date(2025, 6, 24)
DEPARTURE = date(2025, 7, 4)


def ticket(status=CouponStatus.OPEN, departure=DEPARTURE, number="220-1234567890") -> Ticket:
    return Ticket(
        number=number,
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        coupons=(
            Coupon(
                position=0,
                carrier="LH",
                flight_number="400",
                origin="FRA",
                destination="JFK",
                departure=datetime(departure.year, departure.month, departure.day, 10, 25),
                booking_class="K",
                fare_basis="KLWAP7RT",
                status=status,
            ),
        ),
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_fare=Money.of("840.00", "EUR"),
        total_paid=Money.of("1090.83", "EUR"),
    )


def watch(seen=("700.00", "700.00"), **over) -> Watch:
    row = Watch(
        ticket=over.pop("ticket", ticket()),
        origin=over.pop("origin", "FRA"),
        destination=over.pop("destination", "JFK"),
        departure=over.pop("departure", DEPARTURE),
        threshold=Money.of("50.00", "EUR"),
        hysteresis=2,
        **over,
    )
    for index, amount in enumerate(seen):
        row.record(
            Observation(
                seen_on=date(2025, 6, 20 + index),
                amount=Money.of(amount, "EUR"),
                fingerprint=f"{amount}-fingerprint",
            )
        )
    return row


def context() -> Context:
    leg = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")
    return Context(
        itinerary=Itinerary([leg]),
        component=FareComponent((leg,)),
        ticketing=date(2025, 2, 14),
        today=TODAY,
        reissue=True,
    )


def rules_of(
    permitted=True,
    basis=RepricingBasis.CURRENT,
    residual=Residual.REFUNDABLE,
    fee="50.00",
):
    change = VoluntaryChange(permitted=permitted, basis=basis, residual=residual)
    penalties = [Penalty(action=Action.CHANGE, before_departure=Money.of(fee, "EUR"))]

    def rules(_watch):
        return change, penalties, context()

    return rules


def test_a_night_with_nothing_to_look_at_does_nothing():
    night = run([], [], rules_of(), TODAY, "EUR")
    assert night.looked_at == 0
    assert night.raised == []
    assert night.benefit == Money.zero("EUR")


def test_a_drop_that_holds_becomes_a_recommendation():
    book: list[Recommendation] = []
    night = run([watch()], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 1
    assert night.raised[0].benefit == Money.of("90.00", "EUR")
    assert night.benefit == Money.of("90.00", "EUR")
    assert book == night.raised


def test_the_stale_ones_are_closed_before_anything_else_happens():
    # This is the ordering that matters. The old one has to be off the book
    # before the new one is raised or the ticket has two open numbers on it.
    old = Recommendation(
        ticket_number="220-1234567890",
        series_key="FRA-NYC-2025-07-04-Y",
        raised_on=date(2025, 6, 1),
        paid=Money.of("840.00", "EUR"),
        new_fare=Money.of("800.00", "EUR"),
        penalty=Money.of("50.00", "EUR"),
        benefit=Money.of("60.00", "EUR"),
        residual=Residual.REFUNDABLE,
    )
    book = [old]
    night = run([watch()], book, rules_of(), TODAY, "EUR")
    assert night.expired == 1
    assert old.state is State.EXPIRED
    assert len(night.raised) == 1
    assert len([r for r in book if r.state is State.OPEN]) == 1


def test_a_better_fare_withdraws_the_open_one_and_raises_a_new_one():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR")
    first = book[0]

    night = run([watch(seen=("600.00", "600.00"))], book, rules_of(), TODAY, "EUR")
    assert first.state is State.SUPERSEDED
    assert first.replaced_by == "600.00-fingerprint"
    assert night.withdrawn == [first]
    assert len(night.raised) == 1
    assert night.raised[0].benefit == Money.of("190.00", "EUR")


def test_a_barely_better_fare_is_held_back_rather_than_churning_the_queue():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR")
    first = book[0]

    night = run([watch(seen=("695.00", "695.00"))], book, rules_of(), TODAY, "EUR")
    assert night.held_back == 1
    assert night.raised == []
    assert first.state is State.OPEN
    assert len(book) == 1


def test_the_margin_is_the_line_between_those_two():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR")
    better = Decimal("700.00") - SUPERSEDE_MARGIN
    night = run([watch(seen=(str(better), str(better)))], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 1


def test_a_worse_fare_never_replaces_a_standing_recommendation():
    book: list[Recommendation] = []
    run([watch(seen=("600.00", "600.00"))], book, rules_of(), TODAY, "EUR")
    night = run([watch()], book, rules_of(), TODAY, "EUR")
    assert night.held_back == 1
    assert book[0].state is State.OPEN


def test_a_watch_whose_departure_has_gone_is_retired_without_being_priced():
    flown = watch(departure=date(2025, 6, 1), ticket=ticket(departure=date(2025, 6, 1)))
    night = run([flown], [], rules_of(), TODAY, "EUR")
    assert night.retired == [(flown.series_key, Retired.FLOWN)]
    assert night.looked_at == 0
    assert night.decisions == []
    assert not flown.active


def test_a_ticket_with_nothing_open_is_retired_without_being_priced():
    used = watch(ticket=ticket(status=CouponStatus.USED))
    night = run([used], [], rules_of(), TODAY, "EUR")
    assert night.retired == [(used.series_key, Retired.TICKET_FINISHED)]
    assert night.looked_at == 0


def test_a_watch_that_is_already_retired_is_skipped_and_counted():
    done = watch()
    done.retire(Retired.FLOWN)
    night = run([done], [], rules_of(), TODAY, "EUR")
    assert night.skipped_inactive == 1
    assert night.looked_at == 0
    assert night.retired == []


def test_a_fare_that_cannot_be_changed_retires_the_watch_after_one_look():
    row = watch()
    night = run([row], [], rules_of(permitted=False), TODAY, "EUR")
    assert night.looked_at == 1
    assert night.retired == [(row.series_key, Retired.NOT_CHANGEABLE)]
    assert night.raised == []
    assert not row.active


def test_a_fare_that_reprices_historically_retires_the_watch_too():
    row = watch()
    night = run([row], [], rules_of(basis=RepricingBasis.HISTORICAL), TODAY, "EUR")
    assert night.retired == [(row.series_key, Retired.HISTORICAL)]


def test_a_drop_too_small_to_be_worth_it_leaves_the_watch_alone():
    row = watch(seen=("820.00", "820.00"))
    night = run([row], [], rules_of(), TODAY, "EUR")
    assert night.raised == []
    assert night.retired == []
    assert row.active
    assert night.why_not() == {"not_worth_it": 1}


def test_the_verdicts_that_went_nowhere_are_counted_for_the_morning():
    night = run(
        [watch(), watch(seen=("820.00", "820.00")), watch(seen=("830.00", "830.00"))],
        [],
        rules_of(),
        TODAY,
        "EUR",
    )
    assert night.why_not() == {"not_worth_it": 2}
    assert len(night.actionable) == 1


def test_every_watch_gets_its_own_decision_recorded():
    night = run([watch(), watch(seen=("600.00", "600.00"))], [], rules_of(), TODAY, "EUR")
    assert len(night.decisions) == 2
    assert night.looked_at == 2


def test_two_watches_on_one_series_leave_exactly_one_open_recommendation():
    # The second is raised inside the same run, so it has to see the first.
    book: list[Recommendation] = []
    night = run([watch(), watch(seen=("600.00", "600.00"))], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 2
    assert len(night.withdrawn) == 1
    open_now = [r for r in book if r.state is State.OPEN]
    assert len(open_now) == 1
    assert open_now[0].benefit == Money.of("190.00", "EUR")


def test_a_second_look_at_one_series_inside_a_run_can_be_held_back_too():
    book: list[Recommendation] = []
    night = run([watch(), watch(seen=("695.00", "695.00"))], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 1
    assert night.held_back == 1
    assert len([r for r in book if r.state is State.OPEN]) == 1


def test_a_second_series_gets_its_own_recommendation():
    book: list[Recommendation] = []
    other = watch(destination="ORD", ticket=ticket(number="220-9999999999"))
    night = run([watch(), other], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 2
    assert night.benefit == Money.of("180.00", "EUR")


def test_a_settled_recommendation_does_not_block_a_new_one():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR")
    book[0].take(TODAY, "anna")
    night = run([watch(seen=("690.00", "690.00"))], book, rules_of(), TODAY, "EUR")
    assert len(night.raised) == 1
    assert night.held_back == 0


def test_the_lifetime_is_carried_into_what_is_raised():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR", lifetime=7)
    assert book[0].lifetime_days == 7
    assert book[0].expires_on == date(2025, 7, 1)


def test_the_margin_can_be_widened_for_a_desk_that_hates_churn():
    book: list[Recommendation] = []
    run([watch()], book, rules_of(), TODAY, "EUR")
    night = run(
        [watch(seen=("600.00", "600.00"))],
        book,
        rules_of(),
        TODAY,
        "EUR",
        margin=Decimal("500.00"),
    )
    assert night.held_back == 1
    assert night.raised == []


def test_a_night_reads_back_in_one_line():
    night = run([watch()], [], rules_of(), TODAY, "EUR")
    assert str(night) == ("2025-06-24: looked at 1, raised 1 worth 90.00 EUR, retired 0, expired 0")
