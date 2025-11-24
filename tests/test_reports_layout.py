from __future__ import annotations

from gussetline.reports.layout import GUTTER, figures, heading, page, section


def test_names_are_padded_to_the_widest_one_and_not_to_a_typed_number():
    lines = figures([("found", 1), ("take rate", 2)])
    assert lines == ["found      1", "take rate  2"]


def test_the_values_all_start_in_the_same_column():
    lines = figures([("a", 1), ("bb", 2), ("ccc", 3)])
    assert len({line.index(str(index + 1)) for index, line in enumerate(lines)}) == 1


def test_a_longer_name_moves_the_column_for_everybody():
    # The whole reason this is derived. Adding a figure used to leave the
    # block crooked until somebody counted the spaces again.
    narrow = figures([("found", 1)])
    wide = figures([("found", 1), ("other currency", 2)])
    assert wide[0].index("1") > narrow[0].index("1")


def test_the_gutter_is_the_space_between_the_widest_name_and_the_values():
    assert figures([("found", 1)])[0] == "found" + " " * GUTTER + "1"


def test_nothing_to_report_is_no_lines_at_all():
    assert figures([]) == []


def test_a_value_that_is_not_a_number_is_printed_as_it_reads():
    assert figures([("claims", "1 worth 9.00 EUR")]) == ["claims  1 worth 9.00 EUR"]


def test_a_section_with_nothing_in_it_is_left_off_the_page():
    # An empty heading reads as a report that failed rather than as a desk
    # with no problems.
    assert section("worth a letter", []) == []


def test_a_section_carries_its_rows_indented_under_its_title():
    assert section("gaps", ["one", "two"]) == ["", "gaps", "  one", "  two"]


def test_a_section_that_fits_says_nothing_about_the_count():
    assert section("gaps", ["one", "two"], limit=5)[1] == "gaps"


def test_a_section_that_is_cut_says_how_much_of_it_is_on_the_page():
    # The count is usually the finding. Ten claims is a busy week and two
    # hundred is a supplier having an outage, and both print ten rows.
    lines = section("gaps", ["one", "two", "three"], limit=2)
    assert lines[1] == "gaps, showing 2 of 3"
    assert lines[2:] == ["  one", "  two"]


def test_a_section_cut_exactly_at_its_limit_does_not_claim_to_be_cut():
    assert section("gaps", ["one", "two"], limit=2)[1] == "gaps"


def test_a_section_prints_everything_when_nobody_sets_a_limit():
    assert len(section("gaps", [str(index) for index in range(50)])) == 52


def test_a_section_takes_whatever_it_is_given_and_prints_it():
    assert section("gaps", (n for n in range(2))) == ["", "gaps", "  0", "  1"]


def test_a_heading_leaves_a_blank_line_before_the_figures():
    assert heading("savings in EUR") == ["savings in EUR", ""]


def test_a_heading_carries_whatever_qualifies_it():
    assert heading("reliability", "monday to friday") == ["reliability", "monday to friday", ""]


def test_a_page_is_its_blocks_in_order():
    out = page(heading("savings in EUR"), figures([("found", 1)]), section("by month", ["july"]))
    assert out == "savings in EUR\n\nfound  1\n\nby month\n  july"


def test_a_page_of_nothing_is_an_empty_string():
    assert page() == ""
    assert page([], []) == ""
