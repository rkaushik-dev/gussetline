from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.desk.watch import (
    Observation,
    Retired,
    Watch,
    default_margin,
    series_key_for,
    threshold_for,
    total_dropped,
)
from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.money import Money

TODAY = date(2025, 6, 20)
DEPARTURE = date(2025, 7, 4)


def coupon(position=0, origin="FRA", destination="JFK", when="2025-07-04 10:25", **over):
    fields = {
        "position": position,
        "carrier": "LH",
        "flight_number": "400",
        "origin": origin,
        "destination": destination,
        "departure": datetime.strptime(when, "%Y-%m-%d %H:%M"),
        "booking_class": "K",
        "fare_basis": "KLWAP7RT",
    }
    fields.update(over)
    return Coupon(**fields)


def ticket(coupons=None, base="840.00") -> Ticket:
    return Ticket(
        number="220-1234567890",
        record_locator="ABC123",
        passenger_name="MUELLER/ANNA",
        coupons=coupons if coupons is not None else (coupon(0),),
        issued_on=date(2025, 2, 14),
        validating_carrier="LH",
        base_fare=Money.of(base, "EUR"),
        total_paid=Money.of("1090.83", "EUR"),
    )


def watch(threshold="50.00", **over) -> Watch:
    fields = {
        "ticket": ticket(),
        "origin": "FRA",
        "destination": "JFK",
        "departure": DEPARTURE,
        "threshold": Money.of(threshold, "EUR"),
    }
    fields.update(over)
    return Watch(**fields)


def seen(amount, day=TODAY) -> Observation:
    return Observation(seen_on=day, amount=Money.of(amount, "EUR"))


def test_a_watch_folds_its_market_to_cities():
    assert watch(origin="LHR", destination="EWR").series_key.startswith("LON-NYC")


def test_a_threshold_is_not_negative():
    with pytest.raises(ValidationError):
        watch(threshold="-1")


def test_a_drop_has_to_hold_at_least_once():
    with pytest.raises(ValidationError):
        watch(hysteresis=0)


def test_the_series_key_is_built_the_one_way():
    assert watch().series_key == series_key_for("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)


def test_a_fresh_watch_is_live_and_has_seen_nothing():
    row = watch()
    assert row.active
    assert row.checks == 0
    assert str(row) == "FRA-NYC-2025-07-04-Y live after 0 checks"


def test_recording_a_poll_counts_it_whether_or_not_it_was_interesting():
    row = watch()
    row.record(seen("840.00"))
    row.record(seen("840.00"))
    assert row.checks == 2
    assert len(row.history) == 2


def test_a_drop_is_what_is_below_what_was_paid():
    row = watch()
    assert row.drop_against(seen("790.00")) == Money.of("50.00", "EUR")


def test_a_price_above_what_was_paid_is_not_a_negative_drop():
    row = watch()
    assert row.drop_against(seen("900.00")) == Money.zero("EUR")


def test_a_reading_in_another_currency_is_refused():
    row = watch()
    with pytest.raises(ValidationError):
        row.drop_against(Observation(seen_on=TODAY, amount=Money.of("790.00", "USD")))


def test_a_drop_on_the_threshold_clears_it():
    row = watch(threshold="50.00")
    assert row.clears_threshold(seen("790.00"))
    assert not row.clears_threshold(seen("791.00"))


def test_one_reading_is_not_a_drop_worth_reporting():
    row = watch(hysteresis=2)
    row.record(seen("790.00"))
    assert row.held_drop() is None


def test_two_readings_in_a_row_are():
    row = watch(hysteresis=2)
    row.record(seen("790.00", date(2025, 6, 18)))
    row.record(seen("780.00", TODAY))
    held = row.held_drop()
    assert held is not None
    assert held.amount == Money.of("790.00", "EUR")


def test_the_dearer_of_the_run_is_reported_and_not_the_cheaper():
    # The cheapest of a run is the one most likely to have been the outlier,
    # and quoting a number the customer then cannot book is worse.
    row = watch(hysteresis=2)
    row.record(seen("700.00", date(2025, 6, 18)))
    row.record(seen("780.00", TODAY))
    held = row.held_drop()
    assert held is not None
    assert held.amount == Money.of("780.00", "EUR")


def test_a_drop_that_went_away_is_not_reported():
    row = watch(hysteresis=2)
    row.record(seen("790.00", date(2025, 6, 18)))
    row.record(seen("845.00", TODAY))
    assert row.held_drop() is None


def test_a_drop_that_came_back_is_reported_again():
    row = watch(hysteresis=2)
    row.record(seen("790.00", date(2025, 6, 16)))
    row.record(seen("845.00", date(2025, 6, 18)))
    row.record(seen("789.00", date(2025, 6, 19)))
    assert row.held_drop() is None
    row.record(seen("788.00", TODAY))
    assert row.held_drop() is not None


def test_a_longer_hysteresis_needs_a_longer_run():
    row = watch(hysteresis=3)
    for day in (16, 18):
        row.record(seen("790.00", date(2025, 6, day)))
    assert row.held_drop() is None
    row.record(seen("790.00", TODAY))
    assert row.held_drop() is not None


def test_a_retired_watch_reports_nothing_however_good_the_drop():
    row = watch(hysteresis=1)
    row.record(seen("400.00"))
    row.retire(Retired.TAKEN)
    assert row.held_drop() is None
    assert str(row) == "FRA-NYC-2025-07-04-Y retired, a reissue was made"


def test_a_departure_that_has_gone_should_be_retired():
    row = watch()
    assert row.should_retire(TODAY) is None
    assert row.should_retire(date(2025, 7, 5)) is Retired.FLOWN


def test_a_finished_ticket_should_be_retired_even_before_the_departure():
    row = watch(ticket=ticket(coupons=(coupon(0, status=CouponStatus.USED),)))
    assert row.should_retire(TODAY) is Retired.TICKET_FINISHED


def test_the_best_reading_since_a_date_is_found():
    row = watch()
    row.record(seen("840.00", date(2025, 6, 10)))
    row.record(seen("700.00", date(2025, 6, 12)))
    row.record(seen("790.00", date(2025, 6, 18)))
    best = row.best_since(date(2025, 6, 15))
    assert best is not None
    assert best.amount == Money.of("790.00", "EUR")


def test_a_window_with_nothing_in_it_has_no_best():
    assert watch().best_since(TODAY) is None


def test_a_quiet_run_counts_repeats_of_the_latest_amount():
    row = watch()
    row.record(seen("840.00", date(2025, 6, 10)))
    row.record(seen("840.00", date(2025, 6, 12)))
    row.record(seen("840.00", date(2025, 6, 14)))
    assert row.quiet_run() == 2
    row.record(seen("790.00", TODAY))
    assert row.quiet_run() == 0


def test_a_watch_with_one_reading_has_no_quiet_run():
    row = watch()
    row.record(seen("840.00"))
    assert row.quiet_run() == 0


def test_a_threshold_is_the_fee_plus_the_margin():
    assert threshold_for(Money.of("50.00", "EUR"), Money.of("25.00", "EUR")) == Money.of(
        "75.00", "EUR"
    )


def test_a_threshold_across_two_currencies_is_refused():
    with pytest.raises(ValidationError):
        threshold_for(Money.of("50.00", "EUR"), Money.of("25.00", "USD"))


def test_the_default_margin_is_the_same_everywhere():
    assert default_margin("EUR") == Money.of("25", "EUR")
    assert default_margin("USD") == Money.of("25", "USD")


def test_the_held_drops_across_a_set_of_watches_add_up():
    one = watch(hysteresis=1)
    one.record(seen("790.00"))
    two = watch(hysteresis=1)
    two.record(seen("760.00"))
    quiet = watch(hysteresis=1)
    quiet.record(seen("841.00"))
    assert total_dropped([one, two, quiet], "EUR") == Money.of("130.00", "EUR")


def test_nothing_dropping_totals_to_nothing():
    assert total_dropped([], "EUR") == Money.zero("EUR")


def test_the_paid_amount_comes_off_the_ticket():
    assert watch().paid == Money.of("840.00", "EUR")


def test_a_business_cabin_watch_has_its_own_series():
    assert watch(cabin=Cabin.BUSINESS).series_key.endswith("-C")
