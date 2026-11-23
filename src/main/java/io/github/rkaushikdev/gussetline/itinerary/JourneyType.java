package io.github.rkaushikdev.gussetline.itinerary;

import io.github.rkaushikdev.gussetline.util.Text;

/** The four shapes of journey the tariff prices, each on its own minimum. */
public enum JourneyType {
    ONE_WAY("OW"),
    ROUND_TRIP("RT"),
    CIRCLE_TRIP("CT"),
    OPEN_JAW("OJ");

    private final String value;

    JourneyType(String value) {
        this.value = value;
    }

    /** The two letters the tariff writes for this shape. */
    public String value() {
        return value;
    }

    /**
     * The journey type two letters name.
     *
     * @throws IllegalArgumentException when the letters name no journey type
     */
    public static JourneyType of(String value) {
        return Text.named(values(), JourneyType::value, value, "JourneyType");
    }
}
