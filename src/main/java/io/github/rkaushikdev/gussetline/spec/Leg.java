package io.github.rkaushikdev.gussetline.spec;

import java.time.LocalDateTime;

/**
 * One flown sector as a sell entry described it, before it becomes a segment.
 *
 * @param carrier the carrier the sector is sold under
 * @param number the flight number, without leading zeros
 * @param bookingClass the one letter booking class
 * @param origin the airport it leaves from
 * @param destination the airport it lands at
 * @param departure the local wall clock at the origin
 * @param arrival the local wall clock at the destination
 * @param timed whether the entry gave a time, or the times were made up
 */
public record Leg(
        String carrier,
        String number,
        String bookingClass,
        String origin,
        String destination,
        LocalDateTime departure,
        LocalDateTime arrival,
        boolean timed) {

    /** The ends, the flight and the class: {@code FRAJFK LH400 K}. */
    @Override
    public String toString() {
        return origin + destination + " " + carrier + number + " " + bookingClass;
    }
}
