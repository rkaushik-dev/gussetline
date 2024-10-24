from __future__ import annotations

from datetime import date, datetime

import pytest

from gussetline.collect.calendar import Quote, parse, read_price
from gussetline.errors import MalformedPayload
from gussetline.money import Money

SEEN = datetime(2025, 5, 18, 9, 0)


def page(cells: str, currency="EUR", market="FRA-JFK", carrier="LH") -> str:
    heads = []
    if currency:
        heads.append(f'<meta name="fare-currency" content="{currency}">')
    if market:
        heads.append(f'<meta name="fare-market" content="{market}">')
    if carrier:
        heads.append(f'<meta name="fare-carrier" content="{carrier}">')
    return (
        "<html><head>"
        + "".join(heads)
        + "</head><body><table><tr>"
        + cells
        + "</tr></table></body></html>"
    )


def cell(day="2025-06-04", amount="420.00", text="", cls="fare-day", seats=""):
    bits = [f'class="{cls}"', f"data-date='{day}'"]
    if amount:
        bits.append(f'data-amount="{amount}"')
    if seats:
        bits.append(f'data-seats="{seats}"')
    return f"<td {' '.join(bits)}>{text or amount}</td>"


def test_a_plain_price_reads():
    assert read_price("420.00", "EUR") == Money.of("420.00", "EUR")
    assert read_price("420", "EUR") == Money.of("420", "EUR")


def test_a_currency_symbol_on_either_side_is_ignored():
    assert read_price("EUR 420.00", "EUR") == Money.of("420.00", "EUR")
    assert read_price("420.00 kr", "SEK") == Money.of("420.00", "SEK")


def test_a_thousand_separator_is_read_the_way_the_locale_meant_it():
    assert read_price("1,420.00", "EUR") == Money.of("1420.00", "EUR")
    assert read_price("1.420,00", "EUR") == Money.of("1420.00", "EUR")
    assert read_price("1 420", "EUR") == Money.of("1420", "EUR")


def test_a_comma_that_is_a_decimal_point_is_read_as_one():
    assert read_price("420,50", "EUR") == Money.of("420.50", "EUR")


def test_a_comma_that_is_a_thousand_separator_is_dropped():
    assert read_price("1,420", "EUR") == Money.of("1420", "EUR")


def test_a_cell_with_no_digits_has_no_price():
    assert read_price("sold out", "EUR") is None
    assert read_price("", "EUR") is None
    assert read_price("   ", "EUR") is None


def test_a_page_that_does_not_say_its_market_is_refused():
    with pytest.raises(MalformedPayload) as caught:
        parse(page(cell(), market=""), SEEN)
    assert "which market" in caught.value.message


def test_a_page_that_does_not_say_its_currency_is_refused():
    with pytest.raises(MalformedPayload):
        parse(page(cell(), currency=""), SEEN)


def test_a_single_cell_gives_a_quote():
    reading = parse(page(cell()), SEEN)
    assert reading.clean
    assert len(reading) == 1
    quote = reading.quotes[0]
    assert quote.market == ("FRA", "NYC")
    assert quote.departure == date(2025, 6, 4)
    assert quote.amount == Money.of("420.00", "EUR")
    assert quote.carrier == "LH"
    assert quote.observed_at == SEEN


def test_the_market_is_folded_to_cities():
    reading = parse(page(cell(), market="LHR-EWR"), SEEN)
    assert reading.quotes[0].market == ("LON", "NYC")


def test_a_price_in_the_cell_text_is_used_when_there_is_no_data_attribute():
    reading = parse(page(cell(amount="", text="EUR 512.00")), SEEN)
    assert reading.quotes[0].amount == Money.of("512.00", "EUR")


def test_the_data_attribute_wins_over_the_text():
    reading = parse(page(cell(amount="420.00", text="from EUR 999")), SEEN)
    assert reading.quotes[0].amount == Money.of("420.00", "EUR")


def test_a_sold_out_cell_is_a_quote_with_no_price():
    reading = parse(page(cell(amount="", text="", cls="fare-day sold-out")), SEEN)
    assert len(reading) == 1
    assert reading.quotes[0].sold_out
    assert str(reading.quotes[0]).endswith("sold out")


def test_an_empty_cell_counts_as_sold_out():
    reading = parse(page(cell(amount="", text="")), SEEN)
    assert reading.quotes[0].sold_out


def test_a_cell_with_a_date_we_cannot_read_is_a_problem():
    reading = parse(page(cell(day="the fourth")), SEEN)
    assert len(reading) == 0
    assert "unreadable date" in reading.problems[0]


def test_a_cell_with_text_but_no_readable_price_is_a_problem():
    reading = parse(page(cell(amount="", text="call us")), SEEN)
    assert len(reading) == 0
    assert "no price we could read" in reading.problems[0]


def test_cells_that_are_not_fare_days_are_ignored():
    other = '<td class="month-name">June</td>'
    reading = parse(page(other + cell()), SEEN)
    assert len(reading) == 1


def test_attributes_in_either_quoting_style_and_any_order_read():
    odd = '<td data-amount=\'380.00\' class="fare-day" data-date="2025-06-05">380</td>'
    reading = parse(page(odd), SEEN)
    assert reading.quotes[0].amount == Money.of("380.00", "EUR")
    assert reading.quotes[0].departure == date(2025, 6, 5)


def test_a_seat_count_is_read_when_the_page_carries_one():
    reading = parse(page(cell(seats="4")), SEEN)
    assert reading.quotes[0].seats == 4
    assert parse(page(cell()), SEEN).quotes[0].seats is None


def test_a_month_of_cells_reads_as_a_month_of_quotes():
    cells = "".join(cell(day=f"2025-06-{d:02d}", amount=str(400 + d)) for d in range(1, 31))
    reading = parse(page(cells), SEEN)
    assert len(reading) == 30
    assert reading.clean


def test_the_cheapest_live_quote_is_found():
    cells = cell(day="2025-06-04", amount="420") + cell(day="2025-06-05", amount="380")
    reading = parse(page(cells), SEEN)
    cheapest = reading.cheapest()
    assert cheapest is not None
    assert cheapest.departure == date(2025, 6, 5)


def test_a_sold_out_day_is_never_the_cheapest():
    cells = cell(day="2025-06-04", amount="420") + cell(
        day="2025-06-05", amount="", text="", cls="fare-day sold-out"
    )
    cheapest = parse(page(cells), SEEN).cheapest()
    assert cheapest is not None
    assert cheapest.departure == date(2025, 6, 4)


def test_a_page_where_everything_is_sold_out_has_no_cheapest():
    cells = cell(day="2025-06-04", amount="", text="", cls="fare-day sold-out")
    assert parse(page(cells), SEEN).cheapest() is None


def test_a_particular_day_can_be_looked_up():
    cells = cell(day="2025-06-04") + cell(day="2025-06-05", amount="380")
    reading = parse(page(cells), SEEN)
    found = reading.on(date(2025, 6, 5))
    assert found is not None
    assert found.amount == Money.of("380", "EUR")
    assert reading.on(date(2025, 7, 1)) is None


def test_two_quotes_compare_on_their_amounts():
    left = Quote("FRA", "NYC", date(2025, 6, 4), Money.of("380", "EUR"), SEEN)
    right = Quote("FRA", "NYC", date(2025, 6, 5), Money.of("420", "EUR"), SEEN)
    assert left.cheaper_than(right)
    assert not right.cheaper_than(left)


def test_a_quote_reads_back_in_one_line():
    quote = Quote("FRA", "NYC", date(2025, 6, 4), Money.of("420.00", "EUR"), SEEN)
    assert str(quote) == "FRANYC 2025-06-04 420.00 EUR"


def test_a_reading_reads_back_with_both_counts():
    reading = parse(page(cell() + cell(day="bad")), SEEN)
    assert str(reading) == "1 quotes, 1 problems"
