package io.github.rkaushikdev.gussetline.tariff;

import java.util.Comparator;
import java.util.Objects;

/**
 * One carrier's fares in one direction between two cities: the key the tariff store indexes on,
 * because it is the key every lookup arrives with.
 *
 * <p>Ordered by carrier, then origin, then destination, which is the order a dump of the store
 * lists them in.
 *
 * @param carrier the carrier designator
 * @param origin the city the fares are filed from
 * @param destination the city the fares are filed to
 */
public record CarrierMarket(String carrier, String origin, String destination) implements Comparable<CarrierMarket> {

    private static final Comparator<CarrierMarket> ORDER = Comparator.comparing(CarrierMarket::carrier)
            .thenComparing(CarrierMarket::origin)
            .thenComparing(CarrierMarket::destination);

    public CarrierMarket {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
    }

    @Override
    public int compareTo(CarrierMarket other) {
        return ORDER.compare(this, other);
    }
}
