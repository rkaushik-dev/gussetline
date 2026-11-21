package io.github.rkaushikdev.gussetline.rules;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * A window inside a day, both ends included. A window whose start is after its end wraps midnight
 * and covers the late evening and the early morning.
 *
 * @param start the first time of day covered
 * @param end the last time of day covered
 */
public record TimeWindow(LocalTime start, LocalTime end) {

    private static final DateTimeFormatter TIMETABLE = DateTimeFormatter.ofPattern("HHmm", Locale.ENGLISH);

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    /** Whether a time of day falls in the window, either end included. */
    public boolean covers(LocalTime when) {
        if (!start.isAfter(end)) {
            return !when.isBefore(start) && !when.isAfter(end);
        }
        return !when.isBefore(start) || !when.isAfter(end);
    }

    /** Whether the window runs over midnight. */
    public boolean wrapsMidnight() {
        return start.isAfter(end);
    }

    /** The two ends in timetable form: {@code 0600 to 1200}. */
    @Override
    public String toString() {
        return TIMETABLE.format(start) + " to " + TIMETABLE.format(end);
    }
}
