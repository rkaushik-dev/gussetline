package io.github.rkaushikdev.gussetline.flights;

import io.github.rkaushikdev.gussetline.util.Text;

/** The four cabins the tariff prices. Anything else is marketing. */
public enum Cabin {
    FIRST("F", 4),
    BUSINESS("C", 3),
    PREMIUM("W", 2),
    ECONOMY("Y", 1);

    private final String value;
    private final int rank;

    Cabin(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    /** The one letter the tariff writes for this cabin. */
    public String value() {
        return value;
    }

    /** How far up the aircraft the cabin is: first ranks highest, economy lowest. */
    public int rank() {
        return rank;
    }

    /** Whether this cabin sits strictly above the other one. A cabin never outranks itself. */
    public boolean outranks(Cabin other) {
        return rank > other.rank;
    }

    /**
     * The cabin a letter names.
     *
     * @throws IllegalArgumentException when the letter names no cabin
     */
    public static Cabin of(String value) {
        return Text.named(values(), Cabin::value, value, "Cabin");
    }
}
