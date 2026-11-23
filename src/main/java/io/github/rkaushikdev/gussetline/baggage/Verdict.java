package io.github.rkaushikdev.gussetline.baggage;

import io.github.rkaushikdev.gussetline.util.Text;

/** What a change of fare does to what the passenger may carry. */
public enum Verdict {
    SAME("same"),
    BETTER("better"),
    WORSE("worse"),
    /** One allowance is in pieces and the other in weight, which do not convert. */
    NOT_COMPARABLE("not_comparable"),
    /** One of the fares does not state an allowance at all. */
    UNKNOWN("unknown");

    private final String value;

    Verdict(String value) {
        this.value = value;
    }

    /** The name the verdict is stored and reported under. */
    public String value() {
        return value;
    }

    /**
     * The verdict a name stands for.
     *
     * @throws IllegalArgumentException when the name is no verdict
     */
    public static Verdict of(String value) {
        return Text.named(values(), Verdict::value, value, "Verdict");
    }

    /** Whether the desk has to say something about it before quoting. */
    public boolean costsThePassenger() {
        return this == WORSE || this == NOT_COMPARABLE || this == UNKNOWN;
    }
}
