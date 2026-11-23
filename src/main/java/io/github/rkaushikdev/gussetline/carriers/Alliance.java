package io.github.rkaushikdev.gussetline.carriers;

import io.github.rkaushikdev.gussetline.util.Text;

/** The three global alliances, and the absence of one. */
public enum Alliance {
    STAR("star"),
    ONEWORLD("oneworld"),
    SKYTEAM("skyteam"),
    NONE("none");

    private final String value;

    Alliance(String value) {
        this.value = value;
    }

    /** The name the alliance is stored and sent under. */
    public String value() {
        return value;
    }

    /**
     * The alliance stored under a name.
     *
     * @throws IllegalArgumentException when the name is no alliance
     */
    public static Alliance of(String value) {
        return Text.named(values(), Alliance::value, value, "Alliance");
    }
}
