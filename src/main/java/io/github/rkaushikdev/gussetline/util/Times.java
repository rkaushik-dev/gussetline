package io.github.rkaushikdev.gussetline.util;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Times the desk writes and reads.
 *
 * <p>Every stored time is a wall clock reading with no zone on it: local to the airport for a
 * timetable, UTC for anything the desk stamps itself. Times are kept to the microsecond.
 */
public final class Times {

    /** A date, then one separator and the time to any precision from the hour down; ASCII digits. */
    private static final Pattern READING = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:.(\\d{2})(?::(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?)?", Pattern.DOTALL);

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

    /**
     * A wall clock reading in ISO 8601, the way {@link #iso} and {@link Text#plain} write one: a date,
     * then optionally one separator character and the time as hours, hours and minutes, or hours,
     * minutes and seconds with any fraction. {@code 2025-03-04T10:25:00}, {@code 2025-03-04
     * 10:25:00.012345}, {@code 2025-03-04T10} and {@code 2025-03-04} all read. A date alone is
     * midnight, and a fraction finer than a microsecond is dropped.
     *
     * @throws DateTimeParseException when the text is not such a reading, names a day or a time that
     *     does not exist, or carries a zone, which no stored time does
     */
    public static LocalDateTime parseIso(String text) {
        Matcher reading = READING.matcher(text);
        if (!reading.matches()) {
            throw new DateTimeParseException("not an ISO 8601 date and time: " + Text.quote(text), text, 0);
        }
        try {
            return LocalDateTime.of(
                    Integer.parseInt(reading.group(1)),
                    Integer.parseInt(reading.group(2)),
                    Integer.parseInt(reading.group(3)),
                    field(reading.group(4)),
                    field(reading.group(5)),
                    field(reading.group(6)),
                    nanos(reading.group(7)));
        } catch (DateTimeException impossible) {
            throw new DateTimeParseException(impossible.getMessage(), text, 0, impossible);
        }
    }

    private static int field(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }

    /** A fraction of a second, read to the microsecond. */
    private static int nanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        String micros =
                fraction.length() >= 6 ? fraction.substring(0, 6) : fraction + "0".repeat(6 - fraction.length());
        return Integer.parseInt(micros) * 1_000;
    }

    private static String year(int year) {
        return String.format(Locale.ROOT, "%04d", year);
    }
}
