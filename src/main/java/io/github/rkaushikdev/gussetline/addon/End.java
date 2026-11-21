package io.github.rkaushikdev.gussetline.addon;

/** Which end of the gateway fare an arbitrary attaches to. */
public enum End {
    /** The end the journey starts from: the arbitrary reaches the gateway from the interior point. */
    ORIGIN("origin"),
    /** The end the journey finishes at: the arbitrary reaches the interior point from the gateway. */
    DESTINATION("destination");

    private final String value;

    End(String value) {
        this.value = value;
    }

    /** The name the end is filed and written under. */
    public String value() {
        return value;
    }

    /**
     * The end a name stands for.
     *
     * @throws IllegalArgumentException when the name is no end
     */
    public static End of(String value) {
        for (End end : values()) {
            if (end.value.equals(value)) {
                return end;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid End");
    }
}
