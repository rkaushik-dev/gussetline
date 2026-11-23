package io.github.rkaushikdev.gussetline.audit;

import io.github.rkaushikdev.gussetline.util.Text;

/** What kind of disagreement an audit found, which decides who gets the letter. */
public enum Finding {
    AGREES("agrees"),
    /** Money the customer should not have paid, and worth a claim. */
    SUPPLIER_HIGH("supplier_high"),
    /** Usually a fare we do not hold: our tariff is stale rather than their price wrong. */
    SUPPLIER_LOW("supplier_low"),
    /** A tax filing we have not loaded, which is neither side's price. */
    TAX_DIFFERENCE("tax_difference"),
    /** A gap that lands on a minimum check, which is nearly always us. */
    MINIMUM_CHECK("minimum_check"),
    /** The tariff could not reprice the itinerary at all. */
    NOT_PRICEABLE("not_priceable");

    private final String value;

    Finding(String value) {
        this.value = value;
    }

    /** The name the finding is stored and reported under. */
    public String value() {
        return value;
    }

    /**
     * The finding a name stands for.
     *
     * @throws IllegalArgumentException when the name is no finding
     */
    public static Finding of(String value) {
        return Text.named(values(), Finding::value, value, "Finding");
    }
}
