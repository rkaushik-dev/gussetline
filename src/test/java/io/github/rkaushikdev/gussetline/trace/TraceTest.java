package io.github.rkaushikdev.gussetline.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.Details;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class TraceTest {

    static List<Step> each(Trace trace) {
        return StreamSupport.stream(trace.spliterator(), false).toList();
    }

    @Test
    void aFreshTraceHoldsNothingAndHasNotFailed() {
        Trace trace = new Trace("FRAJFK");
        assertThat(trace.size()).isZero();
        assertThat(trace.failed()).isFalse();
        assertThat(trace.reasons()).isEmpty();
        assertThat(trace.isEmpty()).isTrue();
    }

    @Test
    void aTraceIsNonEmptyOnStepsAndNotOnPassing() {
        Trace trace = new Trace();
        trace.failedStep(Stage.ROUTING, "routing 0101 refused FRA-AMS");
        assertThat(trace.isEmpty()).isFalse();
        assertThat(trace.failed()).isTrue();
    }

    @Test
    void recordingKeepsTheOrder() {
        Trace trace = new Trace();
        trace.passed(Stage.SELECTION, "took KLWAP7RT");
        trace.note(Stage.MILEAGE, "104 percent of maximum");
        trace.passed(Stage.MILEAGE, "5M surcharge applied");
        assertThat(each(trace).stream().map(Step::message))
                .containsExactly("took KLWAP7RT", "104 percent of maximum", "5M surcharge applied");
    }

    @Test
    void onlyFailuresComeBackAsReasons() {
        Trace trace = new Trace();
        trace.passed(Stage.SELECTION, "took KLWAP7RT");
        trace.failedStep(Stage.RULE, "cat 5 wants 7 days and there are 3");
        trace.note(Stage.TAX, "DE exempt in transit");
        assertThat(trace.reasons()).containsExactly("cat 5 wants 7 days and there are 3");
        assertThat(trace.failures()).hasSize(1);
    }

    @Test
    void detailsRideAlongWithTheStep() {
        Trace trace = new Trace();
        trace.failedStep(Stage.MILEAGE, "over the maximum", Details.of("flown", 8120, "maximum", 7500));
        Step step = trace.steps().get(0);
        assertThat(step.details()).isEqualTo(Map.of("flown", 8120, "maximum", 7500));
        assertThat(step.failed()).isTrue();
    }

    @Test
    void aStepReadsBackWithItsDetailsSorted() {
        Step step =
                new Step(Stage.MILEAGE, Verdict.FAIL, "over the maximum", Details.of("maximum", 7500, "flown", 8120));
        assertThat(step.toString()).isEqualTo("no mileage       over the maximum flown=8120 maximum=7500");
    }

    @Test
    void aStepWithNoDetailsReadsBackPlainly() {
        Step step = new Step(Stage.ROUTING, Verdict.PASS, "routing 0101 permits it");
        assertThat(step.toString()).isEqualTo("ok routing       routing 0101 permits it");
    }

    @Test
    void anInfoStepIsNeitherAPassNorAFailure() {
        Trace trace = new Trace();
        trace.note(Stage.MILEAGE, "fallback mileage used");
        assertThat(trace.failed()).isFalse();
        assertThat(trace.reasons()).isEmpty();
    }

    @Test
    void stepsCanBeReadBackByStage() {
        Trace trace = new Trace();
        trace.passed(Stage.RULE, "cat 5 satisfied");
        trace.failedStep(Stage.RULE, "cat 16 refuses a change");
        trace.passed(Stage.TAX, "DE applied");
        assertThat(trace.forStage(Stage.RULE)).hasSize(2);
        assertThat(trace.stageFailed(Stage.RULE)).isTrue();
        assertThat(trace.stageFailed(Stage.TAX)).isFalse();
    }

    @Test
    void aStageWithNothingRecordedHasNotFailed() {
        assertThat(new Trace().stageFailed(Stage.MILEAGE)).isFalse();
    }

    @Test
    void asListIsTheShapeTheAuditStores() {
        Trace trace = new Trace();
        trace.failedStep(Stage.RULE, "cat 5 wants 7 days", Details.of("needed", 7, "had", 3));
        assertThat(trace.asList())
                .containsExactly(Map.of(
                        "stage",
                        "rule",
                        "verdict",
                        "fail",
                        "message",
                        "cat 5 wants 7 days",
                        "details",
                        Map.of("needed", 7, "had", 3)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStoredDetailsAreACopy() {
        Trace trace = new Trace();
        trace.note(Stage.TAX, "applied", Details.of("codes", List.of("DE")));
        ((Map<String, Object>) trace.asList().get(0).get("details")).put("codes", List.of("GB"));
        assertThat(trace.steps().get(0).details()).containsEntry("codes", List.of("DE"));
    }

    @Test
    void extendingNamesWhichComponentAStepCameFrom() {
        Trace outbound = new Trace("FRAJFK");
        outbound.passed(Stage.MILEAGE, "within the maximum");
        Trace journey = new Trace("journey");
        journey.extend(outbound, "fc1");
        assertThat(journey.steps().get(0).details()).containsEntry("component", "fc1");
        assertThat(journey.steps().get(0).message()).isEqualTo("within the maximum");
    }

    @Test
    void extendingWithoutAPrefixLeavesTheDetailsAlone() {
        Trace inner = new Trace();
        inner.passed(Stage.MILEAGE, "within the maximum", Details.of("flown", 3851));
        Trace outer = new Trace();
        outer.extend(inner);
        assertThat(outer.steps().get(0).details()).isEqualTo(Map.of("flown", 3851));
    }

    @Test
    void extendingCarriesTheFailureAcross() {
        Trace inner = new Trace();
        inner.failedStep(Stage.ROUTING, "refused");
        Trace outer = new Trace();
        assertThat(outer.failed()).isFalse();
        outer.extend(inner, "fc2");
        assertThat(outer.failed()).isTrue();
    }

    @Test
    void combineNamesEachTraceByItsSubject() {
        Trace one = new Trace("FRAJFK");
        one.passed(Stage.MILEAGE, "within");
        Trace two = new Trace("JFKFRA");
        two.failedStep(Stage.MILEAGE, "over");
        Trace joined = Trace.combine(List.of(one, two), "round trip");
        assertThat(joined.subject()).isEqualTo("round trip");
        assertThat(each(joined).stream().map(s -> s.details().get("component"))).containsExactly("FRAJFK", "JFKFRA");
        assertThat(joined.failed()).isTrue();
    }

    @Test
    void combineFallsBackToAPositionWhenATraceIsUnnamed() {
        Trace joined = Trace.combine(List.of(new Trace(), new Trace()));
        new Trace().passed(Stage.RULE, "x");
        assertThat(joined.size()).isZero();
    }

    @Test
    void combineNumbersUnnamedTracesFromOne() {
        Trace one = new Trace();
        Trace two = new Trace();
        one.passed(Stage.RULE, "a");
        two.passed(Stage.RULE, "b");
        Trace joined = Trace.combine(List.of(one, two));
        assertThat(each(joined).stream().map(s -> s.details().get("component"))).containsExactly("fc1", "fc2");
    }

    @Test
    void renderPutsTheSubjectOnTheFirstLine() {
        Trace trace = new Trace("FRAJFK");
        trace.passed(Stage.SELECTION, "took KLWAP7RT");
        List<String> lines = trace.render().lines().toList();
        assertThat(lines.get(0)).isEqualTo("trace FRAJFK");
        assertThat(lines.get(1)).startsWith("ok selection");
    }

    @Test
    void renderWithoutASubjectStillHasAHead() {
        assertThat(new Trace().render()).isEqualTo("trace");
    }
}
