package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.util.Text;

/** Who a fare is filed for. Private and published fares sit in the same tariff store. */
public enum FareType {
    PUBLISHED("published"),
    PRIVATE("private"),
    NEGOTIATED("negotiated"),
    /** Filed for sale only as part of a package tour. */
    INCLUSIVE_TOUR("it"),
    CORPORATE("corporate");

    private final String value;

    FareType(String value) {
        this.value = value;
    }

    /** The name the fare type is filed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The fare type a name stands for.
     *
     * @throws IllegalArgumentException when the name is no fare type
     */
    public static FareType of(String value) {
        return Text.named(values(), FareType::value, value, "FareType");
    }
}
