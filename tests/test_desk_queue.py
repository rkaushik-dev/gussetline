from __future__ import annotations

from datetime import date
from decimal import Decimal

from gussetline.desk.queue import (
    DEPARTURE_WEIGHT,
    EXPIRY_WEIGHT,
    build,
    has_moved,
    render,
    score,
    urgency,
    urgent,
    worth_of,
)
from gussetline.desk.recommend import Recommendation
from gussetline.desk.watch import Observation
from gussetline.money import Money
from gussetline.rules.changes import Residual

TODAY = date(2025, 10, 1)
FAR = date(2026, 3, 1)
SOON = date(2025, 10, 3)


def made(
    benefit="90.00",
    ticket="220-1111111111",
    series="FRA-NYC-2026-03-01-Y",
    raised=TODAY,
    lifetime=3,
    fingerprint="a" * 32,
) -> Recommendation:
    return Recommendation(
        ticket_number=ticket,
        series_key=series,
        raised_on=raised,
        paid=Money.of("840.00", "EUR"),
        new_fare=Money.of("700.00", "EUR"),
        penalty=Money.of("50.00", "EUR"),
        benefit=Money.of(benefit, "EUR"),
        residual=Residual.REFUNDABLE,
        fingerprint=fingerprint,
        lifetime_days=lifetime,
    )


def seen(amount="700.00", on=TODAY, fingerprint="a" * 32) -> Observation:
    return Observation(seen_on=on, amount=Money.of(amount, "EUR"), fingerprint=fingerprint)


def test_a_far_off_recommendation_with_days_left_has_no_hurry_on_it():
    assert urgency(days_left=3, days_out=90) == Decimal(1)


def test_a_recommendation_on_its_last_day_carries_the_expiry_weight():
    assert urgency(days_left=0, days_out=90) == EXPIRY_WEIGHT


def test_a_departure_today_carries_the_departure_weight():
    assert urgency(days_left=3, days_out=0) == DEPARTURE_WEIGHT


def test_both_clocks_running_down_multiply_rather_than_add():
    # A recommendation nearly expired on a departure next week is a different
    # problem from being nearly out of time on one of them.
    assert urgency(days_left=0, days_out=0) == EXPIRY_WEIGHT * DEPARTURE_WEIGHT


def test_the_hurry_climbs_as_the_clock_runs_down():
    assert urgency(2, 90) < urgency(1, 90) < urgency(0, 90)


def test_a_score_is_the_benefit_when_nothing_is_urgent():
    assert score(made("90.00"), TODAY, FAR) == Decimal("90.00")


def test_a_score_climbs_as_the_recommendation_nears_expiry():
    older = made("90.00", raised=date(2025, 9, 29))
    assert score(older, TODAY, FAR) > score(made("90.00"), TODAY, FAR)


def test_a_smaller_benefit_about_to_expire_beats_a_larger_one_that_can_wait():
    # The whole reason the queue is not sorted by value.
    urgent_one = made("90.00", raised=date(2025, 9, 28), ticket="220-2222222222")
    fat_one = made("200.00", ticket="220-1111111111")
    departures = {
        urgent_one.series_key: SOON,
        "FRA-LON-2026-03-01-Y": FAR,
    }
    fat_one = made("200.00", series="FRA-LON-2026-03-01-Y")
    order = build([fat_one, urgent_one], TODAY, departures)
    assert order[0].recommendation is urgent_one


def test_a_fare_seen_dearer_since_is_demoted_and_not_dropped():
    history = [seen("700.00", TODAY), seen("760.00", date(2025, 10, 2), fingerprint="b" * 32)]
    assert has_moved(TODAY, "a" * 32, history)
    quiet = score(made(), TODAY, FAR)
    moved = score(made(), TODAY, FAR, fare_moved=True)
    assert moved < quiet
    assert moved > Decimal(0)


def test_a_fare_seen_cheaper_since_has_not_moved_against_us():
    history = [seen("700.00", TODAY), seen("650.00", date(2025, 10, 2), fingerprint="b" * 32)]
    assert not has_moved(TODAY, "a" * 32, history)


def test_a_reading_older_than_the_recommendation_says_nothing():
    history = [seen("760.00", date(2025, 9, 20), fingerprint="b" * 32), seen("700.00", TODAY)]
    assert not has_moved(TODAY, "a" * 32, history)


def test_a_recommendation_whose_own_reading_has_rolled_off_says_nothing():
    # The desk keeps a handful of readings and an old recommendation falls
    # off the end of them.
    history = [seen("760.00", date(2025, 10, 2), fingerprint="z" * 32)]
    assert not has_moved(TODAY, "a" * 32, history)


def test_an_empty_queue_is_empty():
    assert build([], TODAY, {}) == []
    assert render([]) == "nothing open"


def test_a_settled_recommendation_is_not_in_the_queue():
    row = made()
    row.take(TODAY, "anna")
    assert build([row], TODAY, {row.series_key: FAR}) == []


def test_a_recommendation_with_no_departure_known_is_left_out():
    # Nothing can be said about how urgent it is, and guessing puts it in
    # the wrong place either way.
    assert build([made()], TODAY, {}) == []


def test_the_queue_is_ordered_by_score_and_not_by_value():
    departures = {"a": SOON, "b": FAR}
    small = made("90.00", ticket="220-2222222222", series="a", raised=date(2025, 9, 29))
    big = made("150.00", ticket="220-1111111111", series="b")
    order = build([big, small], TODAY, departures)
    assert [p.recommendation.ticket_number for p in order] == [
        "220-2222222222",
        "220-1111111111",
    ]


def test_equal_scores_break_on_the_ticket_number_and_never_on_anything_that_moves():
    # An agent halfway down a list that reorders under them loses their place.
    departures = {"a": FAR, "b": FAR}
    first = made(ticket="220-1111111111", series="a")
    second = made(ticket="220-2222222222", series="b")
    once = build([second, first], TODAY, departures)
    again = build([first, second], TODAY, departures)
    assert [p.recommendation.ticket_number for p in once] == [
        p.recommendation.ticket_number for p in again
    ]


def test_a_place_knows_how_long_it_has_and_how_far_out_it_is():
    place = build([made()], TODAY, {"FRA-NYC-2026-03-01-Y": FAR})[0]
    assert place.days_left == 3
    assert place.days_out == 151
    assert not place.fare_moved


def test_the_urgent_ones_are_the_ones_that_go_today():
    departures = {"a": FAR, "b": SOON}
    lasting = made(ticket="220-1111111111", series="a")
    leaving = made(ticket="220-2222222222", series="b")
    places = build([lasting, leaving], TODAY, departures)
    assert [p.recommendation.ticket_number for p in urgent(places)] == ["220-2222222222"]


def test_a_recommendation_expiring_today_is_urgent_however_far_the_departure():
    row = made(raised=date(2025, 9, 28))
    place = build([row], TODAY, {row.series_key: FAR})[0]
    assert place.urgent
    assert "expires today" in place.why()


def test_a_place_says_why_it_is_where_it_is():
    row = made(raised=date(2025, 9, 30))
    place = build([row], TODAY, {row.series_key: SOON})[0]
    assert "days left" in place.why()
    assert "departs within three days" in place.why()


def test_a_place_with_no_hurry_says_so():
    assert build([made()], TODAY, {"FRA-NYC-2026-03-01-Y": FAR})[0].why() == "no hurry"


def test_a_place_that_lost_its_fare_says_that_too():
    history = [seen("700.00", TODAY), seen("760.00", date(2025, 10, 2), fingerprint="b" * 32)]
    place = build(
        [made()],
        TODAY,
        {"FRA-NYC-2026-03-01-Y": FAR},
        {"FRA-NYC-2026-03-01-Y": history},
    )[0]
    assert place.fare_moved
    assert "dearer since" in place.why()


def test_a_place_reads_back_in_one_line():
    place = build([made()], TODAY, {"FRA-NYC-2026-03-01-Y": FAR})[0]
    assert str(place) == "220-1111111111 90.00 EUR score 90.00 (no hurry)"


def test_the_queue_is_worth_what_its_recommendations_are_worth():
    departures = {"a": FAR, "b": FAR}
    places = build(
        [made("90.00", series="a"), made("60.00", ticket="220-2222222222", series="b")],
        TODAY,
        departures,
    )
    assert worth_of(places, "EUR") == Money.of("150.00", "EUR")


def test_an_empty_queue_is_worth_nothing():
    assert worth_of([], "EUR") == Money.zero("EUR")


def test_the_rendered_queue_says_how_many_and_how_many_for_today():
    departures = {"a": SOON, "b": FAR}
    places = build(
        [made(series="a"), made(ticket="220-2222222222", series="b")],
        TODAY,
        departures,
    )
    out = render(places)
    assert "2 open, 1 for today" in out


def test_the_rendered_queue_stops_at_the_limit_and_says_so():
    departures = {str(index): FAR for index in range(5)}
    places = build(
        [made(ticket=f"220-{index}", series=str(index)) for index in range(5)],
        TODAY,
        departures,
    )
    assert "and 3 more" in render(places, limit=2)


def test_a_place_says_what_the_new_fare_includes():
    # An agent who finds out about a smaller allowance halfway through the
    # call has already quoted the saving.
    row = made()
    row.baggage = "1PC"
    place = build([row], TODAY, {row.series_key: FAR})[0]
    assert place.baggage == "1PC"
    assert "includes 1PC" in place.why()


def test_a_place_says_nothing_when_the_supplier_said_nothing():
    place = build([made()], TODAY, {"FRA-NYC-2026-03-01-Y": FAR})[0]
    assert place.baggage == ""
    assert "includes" not in place.why()
