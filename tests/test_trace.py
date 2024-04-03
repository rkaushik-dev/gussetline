from __future__ import annotations

from gussetline.trace import Stage, Step, Trace, Verdict, combine


def test_a_fresh_trace_holds_nothing_and_has_not_failed():
    trace = Trace("FRAJFK")
    assert len(trace) == 0
    assert not trace.failed
    assert trace.reasons() == ()
    assert not trace


def test_a_trace_is_truthy_on_steps_and_not_on_passing():
    trace = Trace()
    trace.failed_step(Stage.ROUTING, "routing 0101 refused FRA-AMS")
    assert trace
    assert trace.failed


def test_recording_keeps_the_order():
    trace = Trace()
    trace.passed(Stage.SELECTION, "took KLWAP7RT")
    trace.note(Stage.MILEAGE, "104 percent of maximum")
    trace.passed(Stage.MILEAGE, "5M surcharge applied")
    assert [s.message for s in trace] == [
        "took KLWAP7RT",
        "104 percent of maximum",
        "5M surcharge applied",
    ]


def test_only_failures_come_back_as_reasons():
    trace = Trace()
    trace.passed(Stage.SELECTION, "took KLWAP7RT")
    trace.failed_step(Stage.RULE, "cat 5 wants 7 days and there are 3")
    trace.note(Stage.TAX, "DE exempt in transit")
    assert trace.reasons() == ("cat 5 wants 7 days and there are 3",)
    assert len(trace.failures()) == 1


def test_details_ride_along_with_the_step():
    trace = Trace()
    trace.failed_step(Stage.MILEAGE, "over the maximum", flown=8120, maximum=7500)
    step = trace.steps[0]
    assert step.details == {"flown": 8120, "maximum": 7500}
    assert step.failed


def test_a_step_reads_back_with_its_details_sorted():
    step = Step(Stage.MILEAGE, Verdict.FAIL, "over the maximum", {"maximum": 7500, "flown": 8120})
    assert str(step) == "no mileage       over the maximum flown=8120 maximum=7500"


def test_a_step_with_no_details_reads_back_plainly():
    step = Step(Stage.ROUTING, Verdict.PASS, "routing 0101 permits it")
    assert str(step) == "ok routing       routing 0101 permits it"


def test_an_info_step_is_neither_a_pass_nor_a_failure():
    trace = Trace()
    trace.note(Stage.MILEAGE, "fallback mileage used")
    assert not trace.failed
    assert trace.reasons() == ()


def test_steps_can_be_read_back_by_stage():
    trace = Trace()
    trace.passed(Stage.RULE, "cat 5 satisfied")
    trace.failed_step(Stage.RULE, "cat 16 refuses a change")
    trace.passed(Stage.TAX, "DE applied")
    assert len(trace.for_stage(Stage.RULE)) == 2
    assert trace.stage_failed(Stage.RULE)
    assert not trace.stage_failed(Stage.TAX)


def test_a_stage_with_nothing_recorded_has_not_failed():
    assert not Trace().stage_failed(Stage.MILEAGE)


def test_as_dict_is_the_shape_the_audit_stores():
    trace = Trace()
    trace.failed_step(Stage.RULE, "cat 5 wants 7 days", needed=7, had=3)
    assert trace.as_list() == [
        {
            "stage": "rule",
            "verdict": "fail",
            "message": "cat 5 wants 7 days",
            "details": {"needed": 7, "had": 3},
        }
    ]


def test_the_stored_details_are_a_copy():
    trace = Trace()
    trace.note(Stage.TAX, "applied", codes=["DE"])
    trace.as_list()[0]["details"]["codes"] = ["GB"]
    assert trace.steps[0].details["codes"] == ["DE"]


def test_extending_names_which_component_a_step_came_from():
    outbound = Trace("FRAJFK")
    outbound.passed(Stage.MILEAGE, "within the maximum")
    journey = Trace("journey")
    journey.extend(outbound, "fc1")
    assert journey.steps[0].details["component"] == "fc1"
    assert journey.steps[0].message == "within the maximum"


def test_extending_without_a_prefix_leaves_the_details_alone():
    inner = Trace()
    inner.passed(Stage.MILEAGE, "within the maximum", flown=3851)
    outer = Trace()
    outer.extend(inner)
    assert outer.steps[0].details == {"flown": 3851}


def test_extending_carries_the_failure_across():
    inner = Trace()
    inner.failed_step(Stage.ROUTING, "refused")
    outer = Trace()
    assert not outer.failed
    outer.extend(inner, "fc2")
    assert outer.failed


def test_combine_names_each_trace_by_its_subject():
    one = Trace("FRAJFK")
    one.passed(Stage.MILEAGE, "within")
    two = Trace("JFKFRA")
    two.failed_step(Stage.MILEAGE, "over")
    joined = combine([one, two], "round trip")
    assert joined.subject == "round trip"
    assert [s.details["component"] for s in joined] == ["FRAJFK", "JFKFRA"]
    assert joined.failed


def test_combine_falls_back_to_a_position_when_a_trace_is_unnamed():
    joined = combine([Trace(), Trace()])
    for trace in (Trace(),):
        trace.passed(Stage.RULE, "x")
    assert len(joined) == 0


def test_combine_numbers_unnamed_traces_from_one():
    one, two = Trace(), Trace()
    one.passed(Stage.RULE, "a")
    two.passed(Stage.RULE, "b")
    joined = combine([one, two])
    assert [s.details["component"] for s in joined] == ["fc1", "fc2"]


def test_render_puts_the_subject_on_the_first_line():
    trace = Trace("FRAJFK")
    trace.passed(Stage.SELECTION, "took KLWAP7RT")
    lines = trace.render().splitlines()
    assert lines[0] == "trace FRAJFK"
    assert lines[1].startswith("ok selection")


def test_render_without_a_subject_still_has_a_head():
    assert Trace().render() == "trace"
