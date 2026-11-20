package io.github.rkaushikdev.gussetline.timelimit;

/** Which of the clocks set the deadline on a held booking. */
public enum Clock {
    HOLD("the hold on the booking"),
    ADVANCE_PURCHASE("the advance purchase backstop"),
    DEPARTURE("the departure itself"),
    IMMEDIATE("instant purchase");

    private final String value;

    Clock(String value) {
        this.value = value;
    }

    /** The reason as an agent reads it. */
    public String value() {
        return value;
    }

    /** Whether a rule set this deadline, rather than the calendar. */
    public boolean isFiled() {
        return this != DEPARTURE;
    }

    /**
     * The clock a reason names.
     *
     * @throws IllegalArgumentException when the reason names no clock
     */
    public static Clock of(String value) {
        for (Clock clock : values()) {
            if (clock.value.equals(value)) {
                return clock;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Clock");
    }
}
