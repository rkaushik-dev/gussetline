package io.github.rkaushikdev.gussetline.carriers;

/**
 * One carrier the desk can ticket or see on a sector.
 *
 * @param code the two character designator
 * @param name the carrier's name
 * @param country the two letter code of the country it is based in
 * @param alliance the alliance it flies in, or {@link Alliance#NONE}
 * @param lowCost whether it sells as a low cost carrier
 */
public record Carrier(String code, String name, String country, Alliance alliance, boolean lowCost) {

    /** A carrier with no low cost flag. */
    public Carrier(String code, String name, String country, Alliance alliance) {
        this(code, name, country, alliance, false);
    }

    /** Whether the carrier is in an alliance at all. */
    public boolean isAligned() {
        return alliance != Alliance.NONE;
    }
}
