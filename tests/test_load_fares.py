from __future__ import annotations

from datetime import date
from decimal import Decimal

from gussetline.collect.fixedwidth import write_record
from gussetline.flights import Cabin
from gussetline.indicators import GlobalIndicator
from gussetline.load.fares import (
    FARE_RECORD,
    HEADER_RECORD,
    TRAILER_RECORD,
    Action,
    apply,
    load_into,
    read,
)
from gussetline.money import Money
from gussetline.tariff import FareType, OwRt, TariffStore

FILED = date(2025, 3, 1)


def fare_line(
    action="A",
    carrier="LH",
    origin="FRA",
    destination="JFK",
    basis="KLWAP7RT",
    amount="420.00",
    currency="EUR",
    owrt="2",
    indicator="AT",
    rule="0100",
    routing="0011",
    tariff="001",
    footnote="",
    cabin="Y",
    fare_type="P",
    effective=date(2025, 1, 1),
    discontinue=date(2025, 12, 31),
    first_travel=date(2025, 1, 1),
    last_travel=date(2025, 12, 31),
) -> str:
    return write_record(
        FARE_RECORD,
        {
            "carrier": carrier,
            "origin": origin,
            "destination": destination,
            "fare_basis": basis,
            "currency": currency,
            "amount": Decimal(amount),
            "owrt": owrt,
            "global_indicator": indicator,
            "rule_number": rule,
            "routing_number": routing,
            "tariff_number": tariff,
            "footnote": footnote or None,
            "cabin": cabin,
            "fare_type": fare_type,
            "effective": effective,
            "discontinue": discontinue,
            "first_travel": first_travel,
            "last_travel": last_travel,
            "action": action,
        },
    )


def header(carrier="LH", filed_on=FILED, sequence=41) -> str:
    return write_record(
        HEADER_RECORD, {"carrier": carrier, "filed_on": filed_on, "sequence": sequence}
    )


def trailer(count: int) -> str:
    return write_record(TRAILER_RECORD, {"records": count})


def payload(*lines: str, with_header=True, count: int | None = None) -> str:
    rows = list(lines)
    if with_header:
        rows.insert(0, header())
    if count is not None:
        rows.append(trailer(count))
    return "\n".join(rows)


def test_an_empty_file_reads_as_an_empty_load():
    load = read("")
    assert len(load) == 0
    assert load.clean
    assert load.carrier == ""


def test_the_header_says_which_carrier_and_which_day():
    load = read(payload(fare_line()))
    assert load.carrier == "LH"
    assert load.filed_on == FILED
    assert load.sequence == 41


def test_a_fare_record_reads_into_a_fare_the_store_would_take():
    load = read(payload(fare_line()))
    assert len(load) == 1
    filing = load.filings[0]
    assert filing.action is Action.ADDED
    fare = filing.fare
    assert fare.carrier == "LH"
    assert fare.market == ("FRA", "NYC")
    assert fare.fare_basis == "KLWAP7RT"
    assert fare.amount == Money.of("420.00", "EUR")
    assert fare.owrt is OwRt.ROUND_TRIP_HALF
    assert fare.global_indicator is GlobalIndicator.AT
    assert fare.cabin is Cabin.ECONOMY
    assert fare.fare_type is FareType.PUBLISHED
    assert fare.effective == date(2025, 1, 1)
    assert fare.last_travel == date(2025, 12, 31)


def test_the_amount_arrives_with_no_decimal_point_in_it():
    line = fare_line(amount="1234.50")
    assert "000123450" in line
    assert read(payload(line)).filings[0].fare.amount == Money.of("1234.50", "EUR")


def test_a_missing_footnote_is_empty_and_not_a_problem():
    load = read(payload(fare_line(footnote="")))
    assert load.clean
    assert load.filings[0].fare.footnote == ""


def test_a_footnote_is_carried_through_when_there_is_one():
    assert read(payload(fare_line(footnote="12"))).filings[0].fare.footnote == "12"


def test_the_three_actions_read_as_three_actions():
    load = read(payload(fare_line("A"), fare_line("C", basis="MLWAP7RT"), fare_line("D")))
    assert [f.action for f in load.filings] == [Action.ADDED, Action.CHANGED, Action.DELETED]
    assert len(load.of(Action.DELETED)) == 1


def test_an_action_nobody_files_is_one_bad_record_and_not_a_bad_file():
    load = read(payload(fare_line("A"), fare_line("Z", basis="MLWAP7RT")))
    assert len(load) == 1
    assert len(load.problems) == 1
    assert "filing action" in load.problems[0].reason


def test_an_indicator_we_do_not_sell_is_refused_by_itself():
    load = read(payload(fare_line(indicator="XX")))
    assert len(load) == 0
    assert "global indicator" in load.problems[0].reason


def test_a_cabin_that_is_not_one_of_the_four_is_refused_by_itself():
    load = read(payload(fare_line(cabin="Q")))
    assert "cabin" in load.problems[0].reason


def test_a_fare_type_the_feed_invented_is_refused_by_itself():
    load = read(payload(fare_line(fare_type="X")))
    assert "fare type" in load.problems[0].reason


def test_a_sale_window_that_closes_before_it_opens_is_one_bad_record():
    load = read(
        payload(
            fare_line(effective=date(2025, 6, 1), discontinue=date(2025, 1, 1)),
            fare_line(basis="MLWAP7RT"),
        )
    )
    assert len(load) == 1
    assert len(load.problems) == 1
    assert "closes before it opens" in load.problems[0].reason


def test_a_bad_record_is_reported_against_its_own_line_number():
    load = read(payload(fare_line(), fare_line(cabin="Q")))
    assert load.problems[0].line_number == 3


def test_the_trailer_count_is_checked_rather_than_trusted():
    assert read(payload(fare_line(), count=1)).counted
    assert not read(payload(fare_line(), count=9)).counted


def test_a_file_with_no_trailer_is_not_reported_as_miscounted():
    assert read(payload(fare_line())).counted


def test_the_bad_records_count_towards_the_trailer_too():
    # The carrier counted every record it wrote, including the ones we could
    # not use, so a count that excludes them would fail on every real file.
    load = read(payload(fare_line(), fare_line(cabin="Q"), count=2))
    assert load.counted


def test_a_load_reads_back_in_one_line():
    load = read(payload(fare_line()))
    assert str(load) == "LH 2025-03-01: 1 filings, 0 problems"


def test_an_added_fare_lands_in_the_store():
    store = TariffStore()
    load, applied = load_into(store, payload(fare_line()))
    assert applied.added == 1
    assert len(store) == 1
    assert store.all_fares()[0].fare_basis == "KLWAP7RT"


def test_a_deleted_fare_leaves_the_store():
    store = TariffStore()
    load_into(store, payload(fare_line("A")))
    _, applied = load_into(store, payload(fare_line("D")))
    assert applied.deleted == 1
    assert len(store) == 0


def test_a_delete_for_a_fare_we_never_held_is_counted_and_ignored():
    store = TariffStore()
    _, applied = load_into(store, payload(fare_line("D")))
    assert applied.unknown_deletes == 1
    assert applied.deleted == 0
    assert len(store) == 0


def test_a_change_replaces_the_one_it_amends_rather_than_stacking_on_it():
    store = TariffStore()
    load_into(store, payload(fare_line("A", amount="420.00")))
    _, applied = load_into(store, payload(fare_line("C", amount="380.00")))
    assert applied.changed == 1
    assert len(store) == 1
    assert store.all_fares()[0].amount == Money.of("380.00", "EUR")


def test_a_change_for_a_fare_we_do_not_hold_is_added_and_said_so():
    store = TariffStore()
    _, applied = load_into(store, payload(fare_line("C")))
    assert applied.changed_as_adds == 1
    assert applied.added == 1
    assert len(store) == 1


def test_a_delete_only_takes_the_filing_it_names():
    # Same fare basis, two sale windows. A withdrawal names one of them and
    # matching on the basis alone would take next winter's fare with it.
    store = TariffStore()
    load_into(
        store,
        payload(
            fare_line("A", effective=date(2025, 1, 1), discontinue=date(2025, 6, 30)),
            fare_line("A", effective=date(2025, 7, 1), discontinue=date(2025, 12, 31)),
        ),
    )
    assert len(store) == 2
    _, applied = load_into(
        store,
        payload(fare_line("D", effective=date(2025, 1, 1), discontinue=date(2025, 6, 30))),
    )
    assert applied.deleted == 1
    assert len(store) == 1
    assert store.all_fares()[0].effective == date(2025, 7, 1)


def test_applying_a_load_twice_does_not_double_the_store():
    store = TariffStore()
    file = payload(fare_line("A"), fare_line("A", basis="MLWAP7RT"))
    load_into(store, file)
    load_into(store, file)
    assert len(store) == 2


def test_reading_never_touches_the_store():
    store = TariffStore()
    load = read(payload(fare_line("A"), fare_line("D", basis="MLWAP7RT")))
    assert len(store) == 0
    applied = apply(store, load)
    assert applied.added == 1


def test_what_was_applied_reads_back_in_one_line():
    store = TariffStore()
    _, applied = load_into(store, payload(fare_line("A"), fare_line("D", basis="MLWAP7RT")))
    assert applied.touched == 1
    assert str(applied) == "1 added, 0 changed, 0 deleted, 1 deletes for fares we do not hold"


def test_a_short_line_is_a_problem_and_the_rest_of_the_file_still_loads():
    store = TariffStore()
    load, applied = load_into(store, payload(fare_line(), "FR" + " " * 10))
    assert applied.added == 1
    assert len(load.problems) == 1


def test_a_record_tag_nobody_declared_is_a_problem_and_not_a_stop():
    store = TariffStore()
    load, applied = load_into(store, payload("ZZ" + " " * 20, fare_line()))
    assert applied.added == 1
    assert len(load.problems) == 1
