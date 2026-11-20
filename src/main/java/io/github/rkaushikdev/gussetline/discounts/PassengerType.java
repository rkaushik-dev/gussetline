package io.github.rkaushikdev.gussetline.discounts;

/** The passenger codes the tariff prices and the desk sells. */
public enum PassengerType {
    ADULT("ADT"),
    CHILD("CHD"),
    INFANT("INF"),
    INFANT_WITH_SEAT("INS"),
    YOUTH("YTH"),
    SENIOR("SRC"),
    STUDENT("STU");

    private final String value;

    PassengerType(String value) {
        this.value = value;
    }

    /** The three letter code the tariff files and a ticket prints. */
    public String value() {
        return value;
    }

    /** Whether the passenger may only travel with an adult: a child or an infant, seated or not. */
    public boolean needsAdult() {
        return this == CHILD || this == INFANT || this == INFANT_WITH_SEAT;
    }

    /** Whether the passenger takes a seat, which everybody does except an infant on a lap. */
    public boolean occupiesSeat() {
        return this != INFANT;
    }

    /**
     * The passenger type a code names.
     *
     * @throws IllegalArgumentException when the code names no passenger type
     */
    public static PassengerType of(String value) {
        for (PassengerType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid PassengerType");
    }
}
