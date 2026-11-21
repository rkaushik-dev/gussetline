package io.github.rkaushikdev.gussetline.minimums;

import java.util.Objects;

/**
 * The market a minimum check found dearer than the fare it was checking: two cities, in the
 * direction the fare between them was looked up.
 *
 * @param origin the city the market starts at
 * @param destination the city the market ends at
 */
public record CityPair(String origin, String destination) {

    public CityPair {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
    }

    /** Both cities joined by a hyphen: {@code FRA-CHI}. */
    @Override
    public String toString() {
        return origin + "-" + destination;
    }
}
