from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from gussetline.collect.fixedwidth import (
    Field,
    Layout,
    Problem,
    Record,
    RecordSpec,
    fields,
)
from gussetline.errors import MalformedPayload

HEADER = RecordSpec(
    "H1",
    fields(
        ("source", 2, 8),
        ("stamp", 10, 8, "date"),
        ("origin", 18, 3),
        ("destination", 21, 3),
    ),
)

AMOUNT = RecordSpec(
    "F1",
    fields(
        ("basis", 2, 10),
        ("currency", 12, 3),
        ("amount", 15, 9, "amount"),
        ("seats", 24, 2, "int", True),
    ),
)

LAYOUT = Layout([HEADER, AMOUNT])

# Counted against the layout above. The basis field is ten wide and the code
# is eight, so it carries two trailing spaces and not one, which is exactly
# the kind of thing that makes a fixed width payload readable only against
# its spec sheet.
GOOD = "H1GDSFARE 20250214FRAJFK\nF1KLWAP7RT  EUR000042000 2\n"


def test_a_field_knows_where_it_ends():
    column = Field("basis", 2, 10)
    assert column.end == 12
    assert column.slice_from("F1KLWAP7RT  EUR") == "KLWAP7RT  "


def test_a_layout_lists_the_tags_it_knows():
    assert LAYOUT.tags() == ["F1", "H1"]
    assert LAYOUT.spec_for("H1") is HEADER
    assert LAYOUT.spec_for("ZZ") is None


def test_a_clean_payload_reads_every_record():
    reading = LAYOUT.read(GOOD)
    assert reading.clean
    assert len(reading) == 2
    assert str(reading) == "2 records, 0 problems"


def test_the_header_fields_come_out_typed():
    header = LAYOUT.read(GOOD).require("H1")
    assert header["source"] == "GDSFARE"
    assert header["stamp"] == date(2025, 2, 14)
    assert header["origin"] == "FRA"
    assert header["destination"] == "JFK"


def test_an_implied_decimal_is_read_with_its_scale():
    fare = LAYOUT.read(GOOD).require("F1")
    assert fare["amount"] == Decimal("420.00")
    assert fare["currency"] == "EUR"
    assert fare["seats"] == 2


def test_a_payload_can_be_bytes_or_text():
    assert len(LAYOUT.read(GOOD.encode())) == 2


def test_blank_lines_are_skipped_and_are_not_problems():
    reading = LAYOUT.read("\n\n" + GOOD + "   \n")
    assert reading.clean
    assert len(reading) == 2


def test_an_unknown_tag_is_a_problem_and_the_rest_still_reads():
    reading = LAYOUT.read("Z9something\n" + GOOD)
    assert not reading.clean
    assert len(reading) == 2
    assert "unknown record tag" in reading.problems[0].reason
    assert reading.problems[0].line_number == 1


def test_a_short_line_is_a_problem_and_names_both_widths():
    reading = LAYOUT.read("H1GDSFARE 2025\n")
    assert len(reading) == 0
    assert "the layout wants 24" in reading.problems[0].reason


def test_a_number_with_a_letter_in_it_is_a_problem():
    reading = LAYOUT.read("F1KLWAP7RT  EURxxxxx2000 2\n")
    assert len(reading) == 0
    assert "amount did not read" in reading.problems[0].reason


def test_a_bad_date_is_a_problem():
    reading = LAYOUT.read("H1GDSFARE 2025FEB14FRAJFK\n")
    assert len(reading) == 0
    assert "stamp did not read" in reading.problems[0].reason


def test_an_optional_field_may_be_blank():
    reading = LAYOUT.read("F1KLWAP7RT  EUR000042000  \n")
    assert reading.clean
    assert "seats" not in reading.require("F1")


def test_a_required_number_that_is_blank_is_a_problem():
    reading = LAYOUT.read("F1KLWAP7RT  EUR          2\n")
    assert len(reading) == 0
    assert "amount is empty" in reading.problems[0].reason


def test_a_blank_text_field_reads_as_empty_rather_than_failing():
    spec = RecordSpec("T1", fields(("code", 2, 3), ("note", 5, 5)))
    reading = Layout([spec]).read("T1DE      \n")
    assert reading.clean
    assert reading.require("T1")["note"] == ""


def test_a_raw_field_keeps_its_spaces_so_missing_and_zero_stay_apart():
    spec = RecordSpec("Q1", fields(("quantity", 2, 4, "raw")))
    reading = Layout([spec]).read("Q1    \nQ10000\n")
    assert [r["quantity"] for r in reading.tagged("Q1")] == ["    ", "0000"]


def test_records_can_be_read_back_by_tag():
    reading = LAYOUT.read(GOOD + "F1QLWAP14RT EUR000038000 4\n")
    assert len(reading.tagged("F1")) == 2
    assert reading.first("F1")["basis"] == "KLWAP7RT"
    assert reading.first("ZZ") is None


def test_requiring_a_record_that_is_not_there_is_a_malformed_payload():
    with pytest.raises(MalformedPayload) as caught:
        LAYOUT.read("").require("H1")
    assert caught.value.details["tag"] == "H1"


def test_a_record_answers_like_a_mapping():
    record = Record("F1", {"basis": "KLWAP7RT"})
    assert record["basis"] == "KLWAP7RT"
    assert record.get("missing", "none") == "none"
    assert "basis" in record
    assert "missing" not in record


def test_a_problem_reads_back_with_its_line_number():
    assert str(Problem(4, "junk", "unknown record tag")) == "line 4: unknown record tag"


def test_a_zero_scale_amount_has_no_decimal_places():
    spec = RecordSpec("Y1", fields(("yen", 2, 6, "amount0")))
    assert Layout([spec]).read("Y1018100\n").require("Y1")["yen"] == Decimal("18100")


def test_a_three_scale_amount_reads_three_places():
    spec = RecordSpec("K1", fields(("dinar", 2, 7, "amount3")))
    assert Layout([spec]).read("K10012999\n").require("K1")["dinar"] == Decimal("12.999")


def test_a_negative_amount_keeps_its_sign():
    spec = RecordSpec("A1", fields(("adjust", 2, 8, "amount")))
    assert Layout([spec]).read("A1-0001250\n").require("A1")["adjust"] == Decimal("-12.50")


def test_a_day_and_time_field_comes_out_as_four_numbers():
    spec = RecordSpec("S1", fields(("when", 2, 8, "daytime")))
    assert Layout([spec]).read("S103041025\n").require("S1")["when"] == (3, 4, 10, 25)


def test_a_day_and_time_that_is_not_digits_is_a_problem():
    spec = RecordSpec("S1", fields(("when", 2, 8, "daytime")))
    reading = Layout([spec]).read("S103MAR102\n")
    assert not reading.clean
