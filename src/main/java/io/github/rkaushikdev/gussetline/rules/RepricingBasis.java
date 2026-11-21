package io.github.rkaushikdev.gussetline.rules;

/**
 * Which day's tariff a reissue is priced from.
 *
 * <p>Historical means the day the ticket was first issued, so a fare that has dropped since then is not
 * available to the ticket at all. Current means the day of the reissue, which is the case worth
 * watching.
 */
public enum RepricingBasis {
    HISTORICAL("historical"),
    CURRENT("current");

    private final String value;

    RepricingBasis(String value) {
        this.value = value;
    }

    /** The name the basis is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The basis stored under a name.
     *
     * @throws IllegalArgumentException when the name is no basis
     */
    public static RepricingBasis of(String value) {
        for (RepricingBasis basis : values()) {
            if (basis.value.equals(value)) {
                return basis;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid RepricingBasis");
    }
}
