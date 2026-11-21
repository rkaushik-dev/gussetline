package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A closed range of dates, both ends included, as a season, a blackout or a sale window is filed.
 *
 * @param start the first date in the range
 * @param end the last date in the range, on or after the start
 */
public record DateRange(LocalDate start, LocalDate end) {

    /**
     * Checks the range opens before it closes. A range of one day is allowed.
     *
     * @throws ValidationError when the end is before the start, naming both
     */
    public DateRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new ValidationError(
                    "a date range closes before it opens",
                    Details.of("start", start.toString(), "end", end.toString()));
        }
    }

    /** Whether a date falls in the range, either end included. */
    public boolean covers(LocalDate when) {
        return !when.isBefore(start) && !when.isAfter(end);
    }

    /** Whether two ranges share a date. Ranges that only touch at one end share that date. */
    public boolean overlaps(DateRange other) {
        return !start.isAfter(other.end) && !other.start.isAfter(end);
    }

    /** The two ends: {@code 2025-03-01 to 2025-03-31}. */
    @Override
    public String toString() {
        return start + " to " + end;
    }
}
