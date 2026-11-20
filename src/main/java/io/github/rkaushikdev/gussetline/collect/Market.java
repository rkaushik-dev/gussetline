package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.Objects;

/**
 * A city pair, folded, because the tariff and the schedule agree on cities.
 *
 * @param origin the city it starts from; an airport is read to its city
 * @param destination the city it goes to; an airport is read to its city
 */
public record Market(String origin, String destination) {

    /**
     * Reads both ends to their cities.
     *
     * @throws ValidationError when an end is neither a city nor an airport, or both ends are the same
     *     city, naming that city as the point
     */
    public Market {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        origin = Geography.cityOf(origin);
        destination = Geography.cityOf(destination);
        if (origin.equals(destination)) {
            throw new ValidationError("a market goes between two places", Details.of("point", origin));
        }
    }

    /** The pair the schedule and the stored results key the market by: {@code FRA-NYC}. */
    public String key() {
        return origin + "-" + destination;
    }

    /** The key: {@code FRA-NYC}. */
    @Override
    public String toString() {
        return key();
    }
}
