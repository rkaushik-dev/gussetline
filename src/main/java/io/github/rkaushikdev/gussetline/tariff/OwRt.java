package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.util.Text;

/**
 * What the filed amount means: the one way round trip indicator.
 *
 * <p>A type 2 fare is filed as half of a round trip. Its amount is not what a one way costs, and
 * doubling it is not optional, it is what the filing means.
 */
public enum OwRt {
    /** A one way that may not be doubled into a round trip. */
    ONE_WAY_ONLY("1"),
    /** Half of a round trip, which cannot be sold on its own. */
    ROUND_TRIP_HALF("2"),
    /** A one way that may also be doubled into a round trip. */
    ONE_WAY_MAY_DOUBLE("3");

    private final String value;

    OwRt(String value) {
        this.value = value;
    }

    /** The digit the tariff files. */
    public String value() {
        return value;
    }

    /**
     * The indicator a digit names.
     *
     * @throws IllegalArgumentException when the digit names no indicator
     */
    public static OwRt of(String value) {
        return Text.named(values(), OwRt::value, value, "OwRt");
    }
}
