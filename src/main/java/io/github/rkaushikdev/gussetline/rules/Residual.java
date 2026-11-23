package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.util.Text;

/**
 * What happens to the difference when a reissue lands on a lower fare than the one paid.
 *
 * <p>Forfeited means the carrier keeps it and the customer saves nothing, however large the drop.
 * Refundable means it comes back as money. Retained means it becomes a credit for a future ticket,
 * which the desk counts at a discount because credits expire.
 */
public enum Residual {
    FORFEITED("forfeited"),
    REFUNDABLE("refundable"),
    RETAINED("retained");

    private final String value;

    Residual(String value) {
        this.value = value;
    }

    /** The name the residual handling is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The residual handling stored under a name.
     *
     * @throws IllegalArgumentException when the name is no residual handling
     */
    public static Residual of(String value) {
        return Text.named(values(), Residual::value, value, "Residual");
    }
}
