from __future__ import annotations

import io
import logging
from datetime import date, datetime
from decimal import Decimal

from gussetline import logs


def test_a_line_opens_with_its_event():
    assert logs.line("night.started").startswith("event=night.started")


def test_the_fields_are_sorted_so_two_lines_line_up():
    assert logs.line("x", zebra=1, apple=2) == "event=x apple=2 zebra=1"


def test_a_field_with_nothing_in_it_is_left_out():
    assert logs.line("x", present=1, missing=None) == "event=x present=1"


def test_a_value_with_a_space_in_it_is_quoted():
    assert logs.line("x", why="no fare found") == 'event=x why="no fare found"'


def test_a_value_with_an_equals_in_it_is_quoted():
    assert 'why="a=b"' in logs.line("x", why="a=b")


def test_an_empty_value_is_quoted_so_the_field_is_still_visible():
    assert logs.line("x", why="") == 'event=x why=""'


def test_a_quote_inside_a_value_does_not_break_the_line():
    assert logs.line("x", why='he said "no"') == "event=x why=\"he said 'no'\""


def test_a_boolean_reads_as_a_word():
    assert logs.line("x", dry=True, live=False) == "event=x dry=yes live=no"


def test_a_date_reads_the_way_everything_else_writes_one():
    assert "on=2025-08-04" in logs.line("x", on=date(2025, 8, 4))


def test_a_time_loses_its_microseconds():
    assert "at=2025-08-04T10:25:00" in logs.line("x", at=datetime(2025, 8, 4, 10, 25, 0, 12345))


def test_a_decimal_keeps_its_trailing_zeroes():
    assert "worth=90.00" in logs.line("x", worth=Decimal("90.00"))


def test_a_ticket_number_is_masked_to_its_last_four():
    # A payment instrument. Anybody holding one can call a carrier.
    assert "ticket=**********7890" in logs.line("x", ticket="220-1234567890")


def test_a_passenger_name_is_masked_as_well():
    assert "MUELLER" not in logs.line("x", passenger="MUELLER/ANNA")


def test_something_too_short_to_mask_is_hidden_whole():
    assert logs.line("x", ticket="12") == "event=x ticket=**"


def test_a_key_never_reaches_a_line_even_masked():
    # A masked key is still a key narrowed down, and no question a log
    # answers needs one.
    line = logs.line("x", key="secret", api_key="secret", token="secret", why="refused")
    assert "secret" not in line
    assert "key" not in line
    assert "why=refused" in line


def test_masking_keeps_the_shape_of_the_value():
    assert len(logs.mask("220-1234567890")) == len("220-1234567890")


def test_a_line_carries_no_run_when_there_is_none():
    assert "run=" not in logs.line("x")


def test_every_line_inside_a_run_carries_the_same_identifier():
    with logs.run() as run_id:
        first = logs.line("one")
        second = logs.line("two")
    assert f"run={run_id}" in first
    assert f"run={run_id}" in second


def test_a_run_can_be_given_its_own_identifier():
    with logs.run("abc123"):
        assert "run=abc123" in logs.line("x")


def test_the_run_is_put_back_when_the_block_ends():
    with logs.run("abc123"):
        pass
    assert logs.current_run() == ""


def test_a_run_inside_a_run_puts_the_outer_one_back():
    with logs.run("outer"):
        with logs.run("inner"):
            assert logs.current_run() == "inner"
        assert logs.current_run() == "outer"


def test_two_runs_are_never_the_same():
    assert logs.new_run_id() != logs.new_run_id()


def test_an_event_reaches_the_stream_it_was_pointed_at():
    stream = io.StringIO()
    logs.setup(stream=stream)
    logs.event("night.started", watches=9)
    assert "event=night.started watches=9" in stream.getvalue()


def test_a_problem_is_written_at_a_level_that_stands_out():
    stream = io.StringIO()
    logs.setup(stream=stream)
    logs.problem("supplier.blocked", supplier="gds")
    assert "WARNING" in stream.getvalue()


def test_setting_up_twice_does_not_write_every_line_twice():
    # The second copy is the one that makes a file unreadable.
    stream = io.StringIO()
    logs.setup(stream=stream)
    logs.setup(stream=stream)
    logs.event("once")
    assert stream.getvalue().count("event=once") == 1


def test_pointing_it_somewhere_else_stops_writing_to_the_first_place():
    first = io.StringIO()
    second = io.StringIO()
    logs.setup(stream=first)
    logs.setup(stream=second)
    logs.event("here")
    assert "here" not in first.getvalue()
    assert "here" in second.getvalue()


def test_the_level_can_be_turned_down():
    stream = io.StringIO()
    logs.setup(level="WARNING", stream=stream)
    logs.event("quiet")
    logs.problem("loud")
    assert "quiet" not in stream.getvalue()
    assert "loud" in stream.getvalue()
    logs.setup(level="INFO", stream=stream)


def test_the_logger_does_not_hand_its_lines_to_the_root():
    logs.setup(stream=io.StringIO())
    assert not logging.getLogger("gussetline").propagate
