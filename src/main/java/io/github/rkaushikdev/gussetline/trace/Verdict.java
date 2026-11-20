package io.github.rkaushikdev.gussetline.trace;

/** What one recorded decision came to. */
public enum Verdict {
    PASS("pass"),
    FAIL("fail"),
    INFO("info");

    private final String value;

    Verdict(String value) {
        this.value = value;
    }

    /** The name a verdict is stored under. */
    public String value() {
        return value;
    }

    /**
     * The verdict stored under a name.
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
}
