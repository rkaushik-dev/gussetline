package io.github.rkaushikdev.gussetline.collect;

/** What a supplier can be asked for. */
public enum Capability {
    SHOP("shop"),
    QUOTE("quote"),
    REPRICE("reprice"),
    TAX_BREAKDOWN("tax_breakdown"),
    SEAT_COUNTS("seat_counts");

    private final String value;

    Capability(String value) {
        this.value = value;
    }

    /** The name the registry, the API and the reports write for this capability. */
    public String value() {
        return value;
    }

    /**
     * The capability a name stands for.
     *
     * @throws IllegalArgumentException when the name is no capability
     */
    public static Capability of(String value) {
        for (Capability capability : values()) {
            if (capability.value.equals(value)) {
                return capability;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Capability");
    }
}
