package io.github.rkaushikdev.gussetline.geography;

/** The three IATA traffic conference areas. */
public enum Area {
    ONE("1"),
    TWO("2"),
    THREE("3");

    private final String value;

    Area(String value) {
        this.value = value;
    }

    /** The digit the tariff writes for this area. */
    public String value() {
        return value;
    }

    /**
     * The area a digit names.
     *
     * @throws IllegalArgumentException when the digit names no area
     */
    public static Area of(String value) {
        for (Area area : values()) {
            if (area.value.equals(value)) {
                return area;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Area");
    }
}
