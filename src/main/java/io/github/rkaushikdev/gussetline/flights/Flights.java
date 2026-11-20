package io.github.rkaushikdev.gussetline.flights;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Time arithmetic on flight segments that survives crossing a date line.
 *
 * <p>There is no time zone database here on purpose. The offsets are fixed per airport and summer
 * time is ignored, which is wrong by an hour twice a year for elapsed times and right for
 * everything the tariff cares about.
 */
public final class Flights {

    /** The most days a sector is searched over when only its wall clocks are known. */
    private static final int DAYS_SEARCHED = 4;

    private Flights() {}

    /**
     * How many days a sector crosses, worked out from its wall clocks alone.
     *
     * <p>The day change is the smallest number of days that puts the arrival after the departure,
     * which is right for every nonstop sector and wrong only for the multi day positioning legs the
     * desk does not sell. Only the arrival's time of day is read. Nought when no day up to three
     * puts it after the departure.
     *
     * @throws ValidationError when either airport is unknown
     */
    public static int inferDayChange(
            String origin, String destination, LocalDateTime departure, LocalDateTime arrival) {
        return inferDayChange(origin, destination, departure, arrival, Optional.empty());
    }

    /**
     * How many days a sector crosses, given the block time a supplier sent.
     *
     * <p>With a block time the answer is arithmetic. The departure is pushed to UTC with the
     * origin's offset, the block time is added, and the result is read back in the destination's own
     * day. The arrival wall clock is not consulted, and the answer is never below nought.
     *
     * @throws ValidationError when the block time is negative or either airport is unknown
     */
    public static int inferDayChange(
            String origin, String destination, LocalDateTime departure, LocalDateTime arrival, int elapsedMinutes) {
        return inferDayChange(origin, destination, departure, arrival, Optional.of(elapsedMinutes));
    }

    /** Either of the two above, by whether a block time is known. */
    static int inferDayChange(
            String origin,
            String destination,
            LocalDateTime departure,
            LocalDateTime arrival,
            Optional<Integer> elapsedMinutes) {
        if (elapsedMinutes.isPresent() && elapsedMinutes.get() < 0) {
            throw new ValidationError("block time cannot be negative", Details.of("minutes", elapsedMinutes.get()));
        }

        int originOffset = Geography.airport(origin).utcOffsetMinutes();
        int destinationOffset = Geography.airport(destination).utcOffsetMinutes();
        LocalDateTime departureUtc = departure.minusMinutes(originOffset);

        if (elapsedMinutes.isPresent()) {
            LocalDateTime landedUtc = departureUtc.plusMinutes(elapsedMinutes.get());
            LocalDateTime landedLocal = landedUtc.plusMinutes(destinationOffset);
            long days = ChronoUnit.DAYS.between(departure.toLocalDate(), landedLocal.toLocalDate());
            return (int) Math.max(0, days);
        }

        for (int candidate = 0; candidate < DAYS_SEARCHED; candidate++) {
            LocalDateTime landed = LocalDateTime.of(departure.toLocalDate().plusDays(candidate), arrival.toLocalTime());
            if (landed.minusMinutes(destinationOffset).isAfter(departureUtc)) {
                return candidate;
            }
        }
        return 0;
    }
}
