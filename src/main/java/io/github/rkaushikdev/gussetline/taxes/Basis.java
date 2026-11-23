package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.util.Text;

/** What the amount in a tax filing means. */
public enum Basis {
    /** An amount of money, in the filing's own currency. */
    FLAT("flat"),
    /** A percentage of the base fare, charged in the fare's currency. */
    PERCENT_OF_BASE("percent_of_base");

    private final String value;

    Basis(String value) {
        this.value = value;
    }

    /** The name the basis is filed under. */
    public String value() {
        return value;
    }

    /**
     * The basis filed under a name.
     *
     * @throws IllegalArgumentException when the name is no basis
     */
    public static Basis of(String value) {
        return Text.named(values(), Basis::value, value, "Basis");
    }
}
