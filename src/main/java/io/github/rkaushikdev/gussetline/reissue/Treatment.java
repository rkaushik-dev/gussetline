package io.github.rkaushikdev.gussetline.reissue;

/**
 * What happens to one tax line when a ticket is reissued.
 *
 * <p>Collected and refunded move money between the passenger and the carrier. Kept is a carrier
 * surcharge that followed a residual the carrier keeps. Flown is a tax whose taxable event already
 * happened. Unchanged is a line that did not move.
 */
public enum Treatment {
    COLLECTED("collected"),
    REFUNDED("refunded"),
    KEPT("kept"),
    FLOWN("flown"),
    UNCHANGED("unchanged");

    private final String value;

    Treatment(String value) {
        this.value = value;
    }

    /** The name the treatment is printed and stored under. */
    public String value() {
        return value;
    }

    /** Whether the line moves money between the passenger and the carrier: collected or refunded. */
    public boolean movesMoney() {
        return this == COLLECTED || this == REFUNDED;
    }

    /**
     * The treatment stored under a name.
     *
     * @throws IllegalArgumentException when the name is no treatment
     */
    public static Treatment of(String value) {
        for (Treatment treatment : values()) {
            if (treatment.value.equals(value)) {
                return treatment;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Treatment");
    }
}
