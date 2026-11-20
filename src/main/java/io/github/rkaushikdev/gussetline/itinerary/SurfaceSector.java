package io.github.rkaushikdev.gussetline.itinerary;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.geography.Geography;

/**
 * A break the passenger covers themselves. Not flown, still on the ticket.
 *
 * <p>It is written on the ticket as ARNK and it changes the shape of the journey without being
 * flown, so it is an element of the itinerary rather than a gap between elements.
 *
 * @param origin the airport the passenger leaves from on the ground
 * @param destination the airport the passenger picks the journey up again at
 */
public record SurfaceSector(String origin, String destination) implements Element {

    /**
     * Reads both airports to their codes.
     *
     * @throws ValidationError when an airport is unknown or both ends are the same airport
     */
    public SurfaceSector {
        origin = Geography.airport(origin).code();
        destination = Geography.airport(destination).code();
        if (origin.equals(destination)) {
            throw new ValidationError("a surface sector goes somewhere", Details.of("point", origin));
        }
    }

    /** The great circle the passenger covers, in statute miles. */
    public int miles() {
        return Distance.greatCircle(origin, destination);
    }

    /** The sector as a ticket shows it: {@code //JFKBOS}. */
    @Override
    public String toString() {
        return "//" + origin + destination;
    }
}
