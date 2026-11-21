package io.github.rkaushikdev.gussetline.rules;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Categories 3, 11 and 14, which are all about dates and none about the same one.
 *
 * <p>{@link Seasonality} says which dates the fare is priced for, and it is checked against the date
 * the first sector of the component departs. {@link Blackout} says which dates travel may not happen
 * at all, and it is checked against every sector, because a journey that flies out before a blackout
 * and home during it is blacked out. {@link TravelDates} says when travel has to start and finish,
 * and it is checked against the ends of the whole journey rather than of the component.
 *
 * <p>Three categories, three different readings of the same itinerary, and getting them confused
 * produces a fare that prices in the wrong season and passes every test written by somebody who made
 * the same mistake twice.
 */
public final class Dates {

    private Dates() {}

    /**
     * Read the shape a loader produces, pairs of ISO dates such as {@code ["2025-03-01",
     * "2025-03-31"]}, into date ranges, in order.
     *
     * @throws IllegalArgumentException when a pair does not hold exactly two dates
     * @throws DateTimeParseException when a date is not an ISO date
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a range closes before it opens
     */
    public static List<DateRange> rangesFrom(List<List<String>> pairs) {
        return pairs.stream().map(Dates::rangeFrom).toList();
    }

    private static DateRange rangeFrom(List<String> pair) {
        if (pair.size() != 2) {
            throw new IllegalArgumentException("a date range is read from two dates, not " + pair.size());
        }
        return new DateRange(LocalDate.parse(pair.get(0)), LocalDate.parse(pair.get(1)));
    }
}
