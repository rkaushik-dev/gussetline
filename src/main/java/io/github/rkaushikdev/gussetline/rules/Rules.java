package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.util.ArrayList;
import java.util.List;

/** Running a rule's provisions against a journey, and reading what they decided. */
public final class Rules {

    private Rules() {}

    /** {@link #evaluate(Iterable, Context, Trace)} with nothing recorded. */
    public static List<Outcome> evaluate(Iterable<? extends Provision> provisions, Context context) {
        return evaluate(provisions, context, new Trace());
    }

    /**
     * Run every provision and keep all the answers, failures included, in order.
     *
     * <p>Nothing short circuits. A fare that fails category 5 and category 6 has two things wrong with
     * it, and the desk needs both, because fixing the advance purchase and finding the fare still
     * refused is the way an hour disappears.
     *
     * <p>Each outcome that applied is recorded as a rule step, failed or passed, carrying its
     * details. One that did not apply is not recorded.
     */
    public static List<Outcome> evaluate(Iterable<? extends Provision> provisions, Context context, Trace trace) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Provision provision : provisions) {
            Outcome outcome = provision.evaluate(context);
            outcomes.add(outcome);
            if (outcome.applied()) {
                if (outcome.failed()) {
                    trace.failedStep(Stage.RULE, outcome.toString(), outcome.details());
                } else {
                    trace.passed(Stage.RULE, outcome.toString(), outcome.details());
                }
            }
        }
        return List.copyOf(outcomes);
    }

    /** The outcomes that refused, each as its one line reading. */
    public static List<String> refusals(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::failed).map(Outcome::toString).toList();
    }

    /** Whether nothing refused. Outcomes that did not apply never refuse. */
    public static boolean permitted(List<Outcome> outcomes) {
        return outcomes.stream().noneMatch(Outcome::failed);
    }
}
