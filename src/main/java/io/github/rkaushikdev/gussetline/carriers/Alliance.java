package io.github.rkaushikdev.gussetline.carriers;

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
        for (Alliance alliance : values()) {
            if (alliance.value.equals(value)) {
                return alliance;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Alliance");
    }
}
