package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Text;

/**
 * What a call did, in the terms the run report is written in.
 *
 * <p>The outcome decides what happens next: retry later, stop for the day, stop until somebody
 * looks, or record and carry on. {@link Registry#classify} turns any failure into one.
 */
public enum Outcome {
    OK("ok"),
    TRANSIENT("transient"),
    THROTTLED("throttled"),
    BLOCKED("blocked"),
    MALFORMED("malformed"),
    BUDGET("budget"),
    REFUSED("refused");

    private final String value;

    Outcome(String value) {
        this.value = value;
    }

    /** The word the run report and the stored results write. */
    public String value() {
        return value;
    }

    /** Whether the same call is worth making again later: a transient failure or a throttle. */
    public boolean retryLater() {
        return this == TRANSIENT || this == THROTTLED;
    }

    /** Whether the supplier is done for now: it blocked us, or the budget for it is spent. */
    public boolean stopsTheSupplier() {
        return this == BLOCKED || this == BUDGET;
    }

    /**
     * The outcome a word stands for.
     *
     * @throws IllegalArgumentException when the word is no outcome
     */
    public static Outcome of(String value) {
        return Text.named(values(), Outcome::value, value, "Outcome");
    }
}
