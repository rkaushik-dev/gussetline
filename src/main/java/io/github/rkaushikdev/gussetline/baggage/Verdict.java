package io.github.rkaushikdev.gussetline.baggage;

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
        for (Verdict verdict : values()) {
            if (verdict.value.equals(value)) {
                return verdict;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Verdict");
    }

    /** Whether the desk has to say something about it before quoting. */
    public boolean costsThePassenger() {
        return this == WORSE || this == NOT_COMPARABLE || this == UNKNOWN;
    }
}
