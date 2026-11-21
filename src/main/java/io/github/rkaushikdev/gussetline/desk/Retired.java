package io.github.rkaushikdev.gussetline.desk;

/** Why a watch was closed. A watch is never closed without one of these. */
public enum Retired {
    FLOWN("the departure has gone"),
    TICKET_FINISHED("every coupon has been used"),
    TAKEN("a reissue was made"),
    NOT_CHANGEABLE("the fare does not permit a change"),
    HISTORICAL("the fare reprices off the original ticketing date"),
    BY_HAND("closed by the desk");

    private final String value;

    Retired(String value) {
        this.value = value;
    }

    /** The reason as the desk reads it, which is also how it is stored. */
    public String value() {
        return value;
    }

    /**
     * The reason stored under a sentence.
     *
     * @throws IllegalArgumentException when the sentence is no reason
     */
    public static Retired of(String value) {
        for (Retired reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Retired");
    }
}
