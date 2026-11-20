package io.github.rkaushikdev.gussetline.geography;

/**
 * A country, the sub area the tariff puts it in, and the currency fares from it are sold in.
 *
 * @param code the ISO 3166 two letter code
 * @param name the country's name
 * @param subArea the sub area the rules name it by
 * @param currency the currency it sells in
 */
public record Country(String code, String name, SubArea subArea, String currency) {

    /** The traffic conference area, which follows the sub area and not the hemisphere. */
    public Area area() {
        return subArea.area();
    }
}
