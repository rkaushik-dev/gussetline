package io.github.rkaushikdev.gussetline.itinerary;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Time on the ground at an intermediate point.
 *
 * @param point the airport the journey breaks at
 * @param arrived when the passenger got there, local wall clock
 * @param departs when the passenger leaves, local wall clock
 * @param stopover whether the break is a stopover rather than a connection
 */
public record Break(String point, LocalDateTime arrived, LocalDateTime departs, boolean stopover) {

    /** The ground time in whole minutes, rounded down. */
    public int minutes() {
        return (int) Math.floorDiv(duration().getSeconds(), 60);
    }

    /** The ground time. */
    public Duration duration() {
        return Duration.between(arrived, departs);
    }
}
