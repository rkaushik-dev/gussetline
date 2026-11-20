package io.github.rkaushikdev.gussetline.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Times the desk writes and reads.
 *
 * <p>Every stored time is a wall clock reading with no zone on it: local to the airport for a
 * timetable, UTC for anything the desk stamps itself. Times are kept to the microsecond.
 */
public final class Times {

    private Times() {}

    /**
     * ISO 8601 with the seconds always written and the microseconds only when there are some:
     * {@code 2025-03-04T10:25:00}, {@code 2025-03-04T10:25:00.012345}.
     */
    public static String iso(LocalDateTime moment) {
        String seconds = isoSeconds(moment);
        int micros = moment.getNano() / 1_000;
        return micros == 0 ? seconds : seconds + String.format(Locale.ROOT, ".%06d", micros);
    }

    /** ISO 8601 to the second, whatever fraction of a second there is: {@code 2025-03-04T10:25:00}. */
    public static String isoSeconds(LocalDateTime moment) {
        return String.format(
                Locale.ROOT,
                "%s-%02d-%02dT%02d:%02d:%02d",
                year(moment.getYear()),
                moment.getMonthValue(),
                moment.getDayOfMonth(),
                moment.getHour(),
                moment.getMinute(),
                moment.getSecond());
    }

    /** The current time in UTC, with no zone attached, to the microsecond. */
    public static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String year(int year) {
        return String.format(Locale.ROOT, "%04d", year);
    }
}
