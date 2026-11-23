package io.github.rkaushikdev.gussetline.geography;

import io.github.rkaushikdev.gussetline.util.Text;

/** The sub areas the rules actually name, each inside exactly one {@link Area}. */
public enum SubArea {
    NORTH_AMERICA("NA", Area.ONE),
    CENTRAL_AMERICA("CA", Area.ONE),
    SOUTH_AMERICA("SA", Area.ONE),
    CARIBBEAN("CB", Area.ONE),
    EUROPE("EU", Area.TWO),
    AFRICA("AF", Area.TWO),
    MIDDLE_EAST("ME", Area.TWO),
    SOUTH_ASIA("SS", Area.THREE),
    SOUTH_EAST_ASIA("SE", Area.THREE),
    JAPAN_KOREA("JK", Area.THREE),
    SOUTH_WEST_PACIFIC("SW", Area.THREE);

    private final String value;
    private final Area area;

    SubArea(String value, Area area) {
        this.value = value;
        this.area = area;
    }

    /** The two letters the tariff writes for this sub area. */
    public String value() {
        return value;
    }

    /** The area this sub area sits in. */
    public Area area() {
        return area;
    }

    /**
     * The sub area two letters name.
     *
     * @throws IllegalArgumentException when the letters name no sub area
     */
    public static SubArea of(String value) {
        return Text.named(values(), SubArea::value, value, "SubArea");
    }
}
