from __future__ import annotations

from datetime import date
from decimal import Decimal

from gussetline.money import Money
from gussetline.persistence.models import FareRow
from gussetline.reports.filing import build, identity, render

EFFECTIVE = date(2025, 1, 1)
DISCONTINUE = date(2025, 12, 31)


def fare(basis="KLWAP7RT", amount="420.00", **over) -> FareRow:
    made = {
        "carrier": "LH",
        "origin": "FRA",
        "destination": "NYC",
        "fare_basis": basis,
        "amount": Decimal(amount),
        "currency": "EUR",
        "owrt": "2",
        "global_indicator": "AT",
        "rule_number": "0100",
        "routing_number": "0011",
        "tariff_number": "1",
        "footnote": "",
        "cabin": "Y",
        "fare_type": "published",
        "effective": EFFECTIVE,
        "discontinue": DISCONTINUE,
        "first_travel": EFFECTIVE,
        "last_travel": DISCONTINUE,
    }
    made.update(over)
    return FareRow(**made)


def test_two_filings_of_the_same_fare_show_nothing_moving():
    report = build([fare()], [fare()])
    assert report.moves == []
    assert report.withdrawn == []
    assert report.added == 0
    assert report.unchanged == 1


def test_a_price_that_moved_is_a_move_and_carries_both_numbers():
    report = build([fare(amount="420.00")], [fare(amount="380.00")])
    assert len(report.moves) == 1
    move = report.moves[0]
    assert move.was == Money.of("420.00", "EUR")
    assert move.now == Money.of("380.00", "EUR")
    assert move.difference == Money.of("-40.00", "EUR")


def test_a_price_that_fell_says_so_and_says_how_far():
    move = build([fare(amount="400.00")], [fare(amount="300.00")]).moves[0]
    assert move.fell
    assert move.percent == Decimal("-25.00")


def test_a_price_that_rose_is_not_a_fall():
    move = build([fare(amount="400.00")], [fare(amount="500.00")]).moves[0]
    assert not move.fell
    assert move.percent == Decimal("25.00")


def test_a_fare_that_was_free_does_not_divide_by_zero():
    move = build([fare(amount="0.00")], [fare(amount="10.00")]).moves[0]
    assert move.percent == Decimal(0)


def test_a_fare_filed_in_another_currency_counts_as_a_move():
    # Not an amendment. The number is the same and what the passenger pays
    # is not, which is the whole difference between the two lists.
    report = build([fare(amount="420.00")], [fare(amount="420.00", currency="USD")])
    assert len(report.moves) == 1


def test_a_fare_the_carrier_stopped_filing_is_withdrawn():
    report = build([fare(), fare(basis="MLWAP7RT")], [fare()])
    assert [g.fare[3] for g in report.withdrawn] == ["MLWAP7RT"]
    assert report.withdrawn[0].was == Money.of("420.00", "EUR")


def test_a_fare_that_was_not_there_before_is_added():
    report = build([fare()], [fare(), fare(basis="MLWAP7RT")])
    assert report.added == 1
    assert report.withdrawn == []


def test_a_new_fare_is_counted_and_not_listed():
    # Carriers file promotional fares constantly and most of them are for
    # markets nobody watches. Listing them buries the two lists that matter.
    report = build([], [fare(basis=f"K{index:03d}") for index in range(40)])
    assert report.added == 40
    assert "added" in render(report)
    assert "K000" not in render(report)


def test_a_fare_is_the_same_fare_across_a_change_of_rule():
    report = build([fare()], [fare(rule_number="0200")])
    assert report.withdrawn == []
    assert report.added == 0
    assert len(report.amendments) == 1


def test_a_rule_change_on_an_unchanged_price_is_reported_and_not_swallowed():
    # The amendment that gets missed, because nothing about the price moved.
    amendment = build([fare()], [fare(rule_number="0200")]).amendments[0]
    assert amendment.field == "rule number"
    assert amendment.was == "0100"
    assert amendment.now == "0200"


def test_a_fare_that_moved_on_price_is_not_also_reported_as_amended():
    # It is one event. Reporting it twice makes a refile look like two.
    report = build([fare()], [fare(amount="380.00", rule_number="0200")])
    assert len(report.moves) == 1
    assert report.amendments == []


def test_several_terms_moving_at_once_are_several_amendments():
    report = build([fare()], [fare(rule_number="0200", routing_number="0022")])
    assert [a.field for a in report.amendments] == ["rule number", "routing number"]


def test_a_refile_that_only_extends_the_sale_window_is_nothing_happening():
    # The most common single edit in a tariff. Treating it as a withdrawal
    # plus an addition would bury everything else.
    report = build([fare()], [fare(discontinue=date(2026, 12, 31))])
    assert report.moves == []
    assert report.amendments == []
    assert report.withdrawn == []
    assert report.added == 0


def test_the_same_basis_in_another_market_is_another_fare():
    report = build([fare()], [fare(destination="LON")])
    assert report.added == 1
    assert len(report.withdrawn) == 1


def test_the_same_basis_in_another_cabin_is_another_fare():
    report = build([fare()], [fare(cabin="C")])
    assert report.added == 1


def test_a_one_way_and_a_round_trip_filing_are_two_fares():
    report = build([fare(owrt="1"), fare(owrt="2")], [fare(owrt="1"), fare(owrt="2")])
    assert report.unchanged == 2


def test_the_falls_come_back_steepest_first():
    before = [fare(basis="A", amount="100.00"), fare(basis="B", amount="100.00")]
    after = [fare(basis="A", amount="90.00"), fare(basis="B", amount="50.00")]
    assert [m.fare[3] for m in build(before, after).falls()] == ["B", "A"]


def test_the_rises_come_back_steepest_first_too():
    before = [fare(basis="A", amount="100.00"), fare(basis="B", amount="100.00")]
    after = [fare(basis="A", amount="110.00"), fare(basis="B", amount="150.00")]
    assert [m.fare[3] for m in build(before, after).rises()] == ["B", "A"]


def test_a_filing_that_only_grew_needs_nobody_before_the_desk_opens():
    assert not build([fare()], [fare(), fare(basis="MLWAP7RT")]).worth_a_look()


def test_a_filing_with_a_fall_in_it_needs_somebody():
    assert build([fare()], [fare(amount="380.00")]).worth_a_look()


def test_a_filing_with_a_withdrawal_in_it_needs_somebody():
    # The dangerous one. The old fare still prices and the carrier will
    # refuse it at the ticket desk.
    assert build([fare(), fare(basis="MLWAP7RT")], [fare()]).worth_a_look()


def test_a_filing_that_only_rose_does_not_page_anybody():
    assert not build([fare()], [fare(amount="500.00")]).worth_a_look()


def test_the_report_reads_back_in_one_line():
    report = build([fare(), fare(basis="B")], [fare(amount="380.00"), fare(basis="C")])
    assert str(report) == "1 moved, 1 withdrawn, 1 added over 2 fares"


def test_the_report_knows_whose_filing_it_is():
    assert build([fare()], [fare()]).carrier == "LH"


def test_a_comparison_across_carriers_says_so_rather_than_picking_one():
    assert build([fare(carrier="LH")], [fare(carrier="UA")]).carrier == "LH+UA"


def test_an_empty_comparison_names_nobody():
    assert build([], []).carrier == ""


def test_the_rendered_report_counts_every_kind_of_change():
    report = build([fare(), fare(basis="B")], [fare(amount="380.00"), fare(basis="C")])
    out = render(report)
    assert "filing for LH" in out
    assert "moved      1" in out
    assert "withdrawn  1" in out
    assert "added      1" in out


def test_the_rendered_report_leads_with_the_falls():
    out = render(build([fare()], [fare(amount="380.00")]))
    assert "reprice these" in out
    assert out.index("reprice these") < out.index("380.00 EUR")


def test_the_rendered_report_says_the_withdrawn_ones_still_price():
    out = render(build([fare(), fare(basis="MLWAP7RT")], [fare()]))
    assert "still price and should not" in out
    assert "MLWAP7RT" in out


def test_the_rendered_report_leaves_out_the_lists_it_has_nothing_for():
    out = render(build([fare()], [fare()]))
    assert "reprice these" not in out
    assert "rose" not in out


def test_the_rendered_report_says_how_much_of_a_long_list_it_showed():
    before = [fare(basis=f"K{index:03d}", amount="100.00") for index in range(20)]
    after = [fare(basis=f"K{index:03d}", amount="90.00") for index in range(20)]
    assert "showing 3 of 20" in render(build(before, after), limit=3)


def test_the_identity_is_the_fare_and_never_the_price():
    assert identity(fare(amount="420.00")) == identity(fare(amount="1.00"))
