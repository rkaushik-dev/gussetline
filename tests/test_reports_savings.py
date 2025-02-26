from __future__ import annotations

from datetime import date
from decimal import Decimal

from gussetline.desk.recommend import Recommendation, State
from gussetline.money import Money
from gussetline.reports.savings import build, render
from gussetline.rules.changes import Residual

JUNE = date(2025, 6, 10)
JULY = date(2025, 7, 10)


def made(benefit="90.00", raised_on=JUNE, **over) -> Recommendation:
    row = Recommendation(
        ticket_number="220-1234567890",
        series_key="FRA-NYC-2025-08-04-Y",
        raised_on=raised_on,
        paid=Money.of("840.00", "EUR"),
        new_fare=Money.of("700.00", "EUR"),
        penalty=Money.of("50.00", "EUR"),
        benefit=Money.of(benefit, "EUR"),
        residual=Residual.REFUNDABLE,
    )
    state = over.get("state")
    if state is State.TAKEN:
        row.take(raised_on, over.get("by", "anna"))
    elif state is State.KILLED:
        row.kill(raised_on, over.get("by", "anna"))
    elif state is State.EXPIRED:
        row.expire(raised_on)
    return row


def test_an_empty_report_is_all_zeroes():
    report = build([], "EUR")
    assert report.overall.raised == 0
    assert report.overall.found == Money.zero("EUR")
    assert report.overall.take_rate == Decimal(0)
    assert report.by_month == []


def test_what_was_found_counts_every_recommendation():
    report = build([made("90.00"), made("40.00", state=State.KILLED)], "EUR")
    assert report.overall.found == Money.of("130.00", "EUR")
    assert report.overall.raised == 2


def test_what_was_taken_counts_only_the_taken_ones():
    report = build([made("90.00", state=State.TAKEN), made("40.00", state=State.KILLED)], "EUR")
    assert report.overall.realised == Money.of("90.00", "EUR")


def test_what_was_missed_counts_only_the_expired_ones():
    report = build(
        [
            made("90.00", state=State.TAKEN),
            made("40.00", state=State.KILLED),
            made("70.00", state=State.EXPIRED),
        ],
        "EUR",
    )
    assert report.overall.missed == Money.of("70.00", "EUR")


def test_a_killed_recommendation_is_not_a_miss():
    # Somebody looked and said no, usually because the customer wanted the
    # flight they had. Counting that as a miss blames the desk for a correct
    # decision.
    report = build([made("40.00", state=State.KILLED)], "EUR")
    assert report.overall.missed == Money.zero("EUR")
    assert report.overall.killed == 1


def test_an_open_recommendation_is_in_found_and_nowhere_else():
    report = build([made("90.00")], "EUR")
    assert report.overall.found == Money.of("90.00", "EUR")
    assert report.overall.realised == Money.zero("EUR")
    assert report.overall.missed == Money.zero("EUR")
    assert report.overall.still_open == 1


def test_the_take_rate_is_measured_against_the_settled_ones():
    report = build(
        [
            made(state=State.TAKEN),
            made(state=State.KILLED),
            made(),
        ],
        "EUR",
    )
    assert report.overall.settled == 2
    assert report.overall.take_rate == Decimal("0.50")


def test_nothing_settled_gives_no_take_rate_rather_than_a_division():
    assert build([made()], "EUR").overall.take_rate == Decimal(0)


def test_the_rows_are_grouped_by_month():
    report = build([made(raised_on=JUNE), made(raised_on=JULY), made(raised_on=JULY)], "EUR")
    assert [line.label for line in report.by_month] == ["2025-06", "2025-07"]
    assert [line.raised for line in report.by_month] == [1, 2]


def test_the_rows_are_grouped_by_whoever_settled_them():
    report = build(
        [
            made(state=State.TAKEN, by="anna"),
            made(state=State.TAKEN, by="bruno"),
            made(state=State.KILLED, by="anna"),
        ],
        "EUR",
    )
    assert [line.label for line in report.by_agent] == ["anna", "bruno"]
    assert [line.taken for line in report.by_agent] == [1, 1]


def test_an_open_recommendation_belongs_to_no_agent():
    report = build([made(), made(state=State.TAKEN, by="anna")], "EUR")
    assert [line.label for line in report.by_agent] == ["anna"]


def test_an_expired_one_belongs_to_the_clock_and_is_reported_as_such():
    report = build([made(state=State.EXPIRED)], "EUR")
    assert [line.label for line in report.by_agent] == ["the clock"]


def test_the_window_narrows_what_is_counted():
    rows = [made(raised_on=JUNE), made(raised_on=JULY)]
    assert build(rows, "EUR", first=JULY).overall.raised == 1
    assert build(rows, "EUR", last=JUNE).overall.raised == 1
    assert build(rows, "EUR", first=JUNE, last=JULY).overall.raised == 2


def test_a_window_with_nothing_in_it_reports_zeroes():
    report = build([made(raised_on=JUNE)], "EUR", first=date(2025, 9, 1))
    assert report.overall.raised == 0


def test_a_line_reads_back_in_one_row():
    report = build([made("90.00", state=State.TAKEN)], "EUR")
    assert str(report.overall) == ("overall          raised    1 taken    1 worth 90.00 EUR")


def test_the_report_reads_back_as_its_overall_line():
    report = build([made("90.00", state=State.TAKEN)], "EUR")
    assert str(report) == str(report.overall)


def test_the_rendered_report_carries_all_three_numbers():
    report = build(
        [
            made("90.00", state=State.TAKEN),
            made("70.00", state=State.EXPIRED),
            made("40.00"),
        ],
        "EUR",
    )
    out = render(report)
    assert "found     200.00 EUR" in out
    assert "taken     90.00 EUR" in out
    assert "missed    70.00 EUR" in out
    assert "take rate 0.50" in out


def test_the_rendered_report_leaves_out_the_groupings_when_there_are_none():
    out = render(build([], "EUR"))
    assert "by month" not in out
    assert "by agent" not in out


def test_the_rendered_report_shows_the_groupings_when_there_are_some():
    out = render(build([made(state=State.TAKEN, by="anna")], "EUR"))
    assert "by month" in out
    assert "by agent" in out
    assert "anna" in out
