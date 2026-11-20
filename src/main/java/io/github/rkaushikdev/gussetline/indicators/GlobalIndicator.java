package io.github.rkaushikdev.gussetline.indicators;

/**
 * The global indicators the desk sees, which are part of the fare key and not a description. There
 * are more, and the desk does not sell them.
 */
public enum GlobalIndicator {
    /** Via the Atlantic. */
    AT("AT"),
    /** Via the Pacific. */
    PA("PA"),
    /** Via both, which is a round the world construction. */
    AP("AP"),
    /** Within the eastern hemisphere. */
    EH("EH"),
    /** Within the western hemisphere. */
    WH("WH"),
    /** Via Trans Siberia. */
    TS("TS"),
    /** Via Russia east of the Urals but not the Siberian routing. */
    RU("RU"),
    /** Within the Far East. */
    FE("FE"),
    /** Via the South Atlantic. */
    SA("SA"),
    /** Wholly within one country. */
    DO("DO");

    private final String value;

    GlobalIndicator(String value) {
        this.value = value;
    }

    /** The two letters the tariff files. */
    public String value() {
        return value;
    }

    /**
     * The indicator two letters name.
     *
     * @throws IllegalArgumentException when the letters name no indicator
     */
    public static GlobalIndicator of(String value) {
        for (GlobalIndicator indicator : values()) {
            if (indicator.value.equals(value)) {
                return indicator;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid GlobalIndicator");
    }
}
