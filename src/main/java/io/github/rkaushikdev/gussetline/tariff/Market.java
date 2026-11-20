package io.github.rkaushikdev.gussetline.tariff;

import java.util.Objects;

/**
 * One direction between two cities, the way the tariff files a fare.
 *
 * <p>Directional on purpose: Frankfurt to New York and New York to Frankfurt are two markets with
 * two sets of fares, and their amounts differ.
 *
 * @param origin the city the fare is filed from
 * @param destination the city the fare is filed to
 */
public record Market(String origin, String destination) {

    public Market {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
    }
}
