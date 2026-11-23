package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.util.Text;

/** How many times a filed tax is charged on one journey. */
public enum Applies {
    /** Once, at the point the journey starts. */
    PER_TICKET("per_ticket"),
    /** Once for every element of the journey, flown or not. */
    PER_COUPON("per_coupon"),
    /** At every point a passenger departs from in the filing country. */
    PER_DEPARTURE("per_departure"),
    /** At every point a passenger arrives at in the filing country. */
    PER_ARRIVAL("per_arrival"),
    /** At every departure from the filing country to another one. */
    PER_INTERNATIONAL_DEPARTURE("per_international_departure");

    private final String value;

    Applies(String value) {
        this.value = value;
    }

    /** The name the scope is filed under. */
    public String value() {
        return value;
    }

    /**
     * The scope filed under a name.
     *
     * @throws IllegalArgumentException when the name is no scope
     */
    public static Applies of(String value) {
        return Text.named(values(), Applies::value, value, "Applies");
    }
}
