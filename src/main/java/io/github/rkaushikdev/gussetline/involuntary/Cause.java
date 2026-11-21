package io.github.rkaushikdev.gussetline.involuntary;

/** Why the carrier is reaccommodating. Never a default and never blank. */
public enum Cause {
    CANCELLED("cancelled"),
    SCHEDULE_CHANGE("schedule_change"),
    MISCONNECTED("misconnected"),
    DOWNGRADED("downgraded"),
    DENIED_BOARDING("denied_boarding");

    private final String value;

    Cause(String value) {
        this.value = value;
    }

    /** The name the cause is printed and stored under. */
    public String value() {
        return value;
    }

    /** Whether the carrier brought this about. Every cause is, which is what makes the module necessary. */
    public boolean isTheCarriersDoing() {
        return true;
    }

    /** Whether the passenger is owed the difference between two cabins: a downgrade or denied boarding. */
    public boolean owesADowngradeRefund() {
        return this == DOWNGRADED || this == DENIED_BOARDING;
    }

    /**
     * The cause stored under a name.
     *
     * @throws IllegalArgumentException when the name is no cause
     */
    public static Cause of(String value) {
        for (Cause cause : values()) {
            if (cause.value.equals(value)) {
                return cause;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Cause");
    }
}
