package io.github.rkaushikdev.gussetline.routing;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One permitted move on a routing map, and who may fly it.
 *
 * @param origin the city it leaves from; an airport given here is folded up to its city
 * @param destination the city it reaches
 * @param carriers the designators permitted on it, upper case; empty means any carrier may fly it
 */
public record Hop(String origin, String destination, Set<String> carriers) {

    /**
     * Folds both ends to their cities and normalises the carriers.
     *
     * @throws ValidationError when both ends are in one city, or a point or a carrier is unknown or
     *     malformed
     */
    public Hop {
        origin = Geography.cityOf(origin);
        destination = Geography.cityOf(destination);
        if (origin.equals(destination)) {
            throw new ValidationError("a hop goes somewhere", Details.of("point", origin));
        }
        carriers = Objects.requireNonNull(carriers, "carriers").stream()
                .map(Carriers::normaliseCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A hop any carrier may fly. */
    public Hop(String origin, String destination) {
        this(origin, destination, Set.of());
    }

    /** Whether the hop names no carriers, so that anybody may fly it. */
    public boolean anyCarrier() {
        return carriers.isEmpty();
    }

    /** Whether the carrier may fly the hop, in whatever case it is written. */
    public boolean permits(String carrier) {
        return permits(Optional.of(carrier));
    }

    /**
     * Whether a carrier, or no carrier at all, may fly the hop. A hop open to any carrier permits an
     * absent one; a hop that names carriers does not.
     *
     * @throws ValidationError when the hop names carriers and the one given is malformed
     */
    public boolean permits(Optional<String> carrier) {
        if (anyCarrier()) {
            return true;
        }
        return carrier.map(code -> carriers.contains(Carriers.normaliseCode(code)))
                .orElse(false);
    }

    /** The same hop the other way round, for the same carriers. */
    public Hop reversed() {
        return new Hop(destination, origin, carriers);
    }

    /** The hop as a map prints it: {@code FRA-NYC[LH/UA]}, or {@code FRA-NYC[any]}. */
    @Override
    public String toString() {
        String who = carriers.isEmpty() ? "any" : carriers.stream().sorted().collect(Collectors.joining("/"));
        return origin + "-" + destination + "[" + who + "]";
    }
}
