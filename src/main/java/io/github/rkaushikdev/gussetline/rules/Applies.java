package io.github.rkaushikdev.gussetline.rules;

/**
 * How many times a filed category 16 fee is charged.
 *
 * <p>Per ticket means once. Per fare component means once per component, so a round trip pays twice
 * and a circle trip three times, and on a cheap fare the fee can exceed what the ticket cost. Highest
 * of means take the dearest of the applicable component fees and charge it once, which is different
 * from both.
 */
public enum Applies {
    PER_TICKET("per_ticket"),
    PER_COMPONENT("per_component"),
    HIGHEST_OF("highest_of");

    private final String value;

    Applies(String value) {
        this.value = value;
    }

    /** The name the scope is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The scope stored under a name.
     *
     * @throws IllegalArgumentException when the name is no scope
     */
    public static Applies of(String value) {
        for (Applies applies : values()) {
            if (applies.value.equals(value)) {
                return applies;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Applies");
    }
}
