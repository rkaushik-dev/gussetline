package io.github.rkaushikdev.gussetline.itinerary;

import io.github.rkaushikdev.gussetline.flights.Segment;

/**
 * One element of a ticket: a flown {@link Segment} or a {@link SurfaceSector} the passenger covers
 * themselves. Those two are the only kinds; code that needs to tell them apart asks which one it
 * holds.
 *
 * <p>Both ends are airport codes, already normalised.
 */
public interface Element {

    /** The airport the element starts at. */
    String origin();

    /** The airport the element ends at. */
    String destination();
}
