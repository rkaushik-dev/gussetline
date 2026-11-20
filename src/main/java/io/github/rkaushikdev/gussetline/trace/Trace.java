package io.github.rkaushikdev.gussetline.trace;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trail a price leaves behind it: an ordered record of what a price did and why.
 *
 * <p>The product is not the number. The desk can get a number from any supplier for free. The
 * product is being able to say, in one screen, that the fare selected was this one, that the
 * routing passed, that the mileage came in at a hundred and four percent of the maximum so a five
 * percent surcharge applied, that the higher intermediate point check raised the component to the
 * Chicago fare, and that the supplier's total is therefore eleven euro light.
 *
 * <p>A trace never throws. Recording a failure is not the same as failing, because some checks are
 * allowed to fail and be recovered from, and a trace that threw on the first refusal could not
 * describe the alternative that was taken.
 *
 * <p>A trace with steps is not empty whether or not it passed; ask {@link #failed()} for the
 * verdict and {@link #isEmpty()} for whether anything was recorded.
 */
public final class Trace implements Iterable<Step> {

    private final String subject;
    private final List<Step> steps = new ArrayList<>();

    /** A trace with no subject. */
    public Trace() {
        this("");
    }

    /** A trace named for what it priced, such as {@code FRAJFK}. */
    public Trace(String subject) {
        this.subject = subject;
    }

    /**
     * Fold several traces into one, naming each step by the trace it came from: its subject, or
     * {@code fc1}, {@code fc2}... by position when it has none.
     */
    public static Trace combine(Iterable<Trace> traces, String subject) {
        Trace combined = new Trace(subject);
        int index = 1;
        for (Trace trace : traces) {
            combined.extend(trace, trace.subject.isEmpty() ? "fc" + index : trace.subject);
            index++;
        }
        return combined;
    }

    /** {@link #combine(Iterable, String)} with no subject. */
    public static Trace combine(Iterable<Trace> traces) {
        return combine(traces, "");
    }

    public String subject() {
        return subject;
    }

    /** Record one decision. */
    public Step record(Stage stage, Verdict verdict, String message, Map<String, ?> details) {
        Step step = new Step(stage, verdict, message, new LinkedHashMap<>(details));
        steps.add(step);
        return step;
    }

    public Step passed(Stage stage, String message) {
        return passed(stage, message, Map.of());
    }

    public Step passed(Stage stage, String message, Map<String, ?> details) {
        return record(stage, Verdict.PASS, message, details);
    }

    public Step failedStep(Stage stage, String message) {
        return failedStep(stage, message, Map.of());
    }

    public Step failedStep(Stage stage, String message, Map<String, ?> details) {
        return record(stage, Verdict.FAIL, message, details);
    }

    public Step note(Stage stage, String message) {
        return note(stage, message, Map.of());
    }

    public Step note(Stage stage, String message, Map<String, ?> details) {
        return record(stage, Verdict.INFO, message, details);
    }

    /** Every step, in the order recorded. */
    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /** Whether any step refused. */
    public boolean failed() {
        return steps.stream().anyMatch(Step::failed);
    }

    /** The steps that refused. */
    public List<Step> failures() {
        return steps.stream().filter(Step::failed).toList();
    }

    /** Just the sentences of the refusals, which is what a report prints. */
    public List<String> reasons() {
        return steps.stream().filter(Step::failed).map(Step::message).toList();
    }

    /** The steps recorded against one stage. */
    public List<Step> forStage(Stage stage) {
        return steps.stream().filter(step -> step.stage() == stage).toList();
    }

    /** Whether any step of one stage refused. */
    public boolean stageFailed(Stage stage) {
        return forStage(stage).stream().anyMatch(Step::failed);
    }

    /**
     * Fold another trace in, which is how a component joins a journey.
     *
     * <p>The prefix names which component the steps came from, as a {@code component} detail,
     * because a journey with three components produces three mileage checks and a trail that cannot
     * tell them apart is not a trail. An empty prefix leaves the details alone.
     */
    public void extend(Trace other, String prefix) {
        for (Step step : other.steps()) {
            Map<String, Object> details = new LinkedHashMap<>(step.details());
            if (!prefix.isEmpty()) {
                details.put("component", prefix);
            }
            steps.add(new Step(step.stage(), step.verdict(), step.message(), details));
        }
    }

    /** {@link #extend(Trace, String)} without a prefix. */
    public void extend(Trace other) {
        extend(other, "");
    }

    /** Each step in the shape the audit stores. */
    public List<Map<String, Object>> asList() {
        return steps.stream().map(Step::asMap).toList();
    }

    /** The subject on a head line, then one line per step. */
    public String render() {
        List<String> lines = new ArrayList<>();
        lines.add(subject.isEmpty() ? "trace" : "trace " + subject);
        steps.forEach(step -> lines.add(step.toString()));
        return String.join("\n", lines);
    }

    /** How many steps are recorded. */
    public int size() {
        return steps.size();
    }

    /** Whether nothing has been recorded, which says nothing about whether it passed. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    @Override
    public Iterator<Step> iterator() {
        return steps().iterator();
    }
}
