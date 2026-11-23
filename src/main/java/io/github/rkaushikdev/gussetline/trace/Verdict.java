package io.github.rkaushikdev.gussetline.trace;

import io.github.rkaushikdev.gussetline.util.Text;

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
        return Text.named(values(), Verdict::value, value, "Verdict");
    }
}
