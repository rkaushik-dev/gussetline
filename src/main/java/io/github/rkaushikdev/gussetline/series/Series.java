package io.github.rkaushikdev.gussetline.series;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key that groups observations into one watchable price series.
 *
 * <p>A series is a market, a departure and a cabin: every price anybody has seen for New York on
 * the fourth of August in economy, whatever supplier said it and whatever airport they said it out
 * of. The watch reads its history by this key and the collectors write theirs under it, so the two
 * only ever meet if both spell it the same way.
 *
 * <p>Two spellings of one series fail silently. A ticket on Newark that writes its watch under EWR,
 * while every observation for the same flight lands under NYC, raises no error and returns no empty
 * result anybody looks at: there are simply two sets of rows in one table, each looking perfectly
 * correct, and the night run finds no history for the watch, every night. So one method builds the
 * key, everything else calls it, and the fold from airports to cities happens once, where it cannot
 * be forgotten.
 *
 * <p>The format is positional and readable on purpose, because it turns up in log lines and in the
 * database where somebody is going to have to eyeball it: {@code FRA-NYC-2025-08-04-Y}. Origin city,
 * destination city, departure, cabin. The departure carries its own dashes, which is why reading one
 * back counts from both ends rather than splitting on the separator and hoping.
 */
public final class Series {

    /** What the fields of a key are joined with. */
    public static final String SEPARATOR = "-";

    /**
     * How many pieces a key splits into on the separator: origin, destination, cabin, and the three
     * the date contributes.
     */
    public static final int FIELDS = 6;

    private static final Pattern PIECES = Pattern.compile(Pattern.quote(SEPARATOR));

    /** A calendar date in ISO 8601, ASCII digits only: {@code 2025-08-04}. */
    private static final Pattern CALENDAR_DATE = Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})");

    private Series() {}

    /**
     * The key for one market, one departure and one cabin.
     *
     * <p>Takes airports or cities and returns cities either way, so a caller that happens to hold a
     * flight's own airport code cannot open a second series for a market that already has one.
     *
     * @throws ValidationError when either end is neither a city nor an airport
     */
    public static String key(String origin, String destination, LocalDate departure, Cabin cabin) {
        return parts(origin, destination, departure, cabin).joined();
    }

    /**
     * {@link #key(String, String, LocalDate, Cabin)} for a cabin given as its letter, in either case,
     * which is how the database holds it.
     */
    public static String key(String origin, String destination, LocalDate departure, String cabin) {
        return parts(origin, destination, departure, cabin).joined();
    }

    /**
     * {@link #key(String, String, LocalDate, Cabin)} for a departure moment. The time of day is not
     * part of the series: two departures on one day are one series, because a watch is on a day and
     * a market, and that is what the passenger can be moved between.
     */
    public static String key(String origin, String destination, LocalDateTime departure, Cabin cabin) {
        return parts(origin, destination, departure, cabin).joined();
    }

    /** {@link #key(String, String, LocalDateTime, Cabin)} for a cabin given as its letter. */
    public static String key(String origin, String destination, LocalDateTime departure, String cabin) {
        return parts(origin, destination, departure, cabin).joined();
    }

    /**
     * The four fields of the key, folded and normalised, before they are joined.
     *
     * @throws ValidationError when either end is neither a city nor an airport
     */
    public static Parts parts(String origin, String destination, LocalDate departure, Cabin cabin) {
        return parts(origin, destination, departure, cabin.value());
    }

    /** {@link #parts(String, String, LocalDate, Cabin)} for a cabin given as its letter, in either case. */
    public static Parts parts(String origin, String destination, LocalDate departure, String cabin) {
        return new Parts(
                Geography.cityOf(origin),
                Geography.cityOf(destination),
                departure.toString(),
                cabin.toUpperCase(Locale.ROOT));
    }

    /** {@link #parts(String, String, LocalDate, Cabin)} for a departure moment, read as its day. */
    public static Parts parts(String origin, String destination, LocalDateTime departure, Cabin cabin) {
        return parts(origin, destination, departure.toLocalDate(), cabin);
    }

    /** {@link #parts(String, String, LocalDate, String)} for a departure moment, read as its day. */
    public static Parts parts(String origin, String destination, LocalDateTime departure, String cabin) {
        return parts(origin, destination, departure.toLocalDate(), cabin);
    }

    /**
     * A key taken back apart, for the places that hold one and want a date.
     *
     * <p>Counted from both ends. The departure contributes three pieces of its own, so splitting on
     * the separator and taking the third piece gets the year. The departure has to be a calendar date
     * written the way {@link #key} writes one.
     *
     * @throws ValidationError naming the {@code key} when it does not split into {@link #FIELDS}
     *     pieces or its departure is not a day that exists
     */
    public static Reading read(String made) {
        String[] pieces = PIECES.split(made, -1);
        if (pieces.length != FIELDS) {
            throw notAKey(made);
        }
        LocalDate departure;
        try {
            departure = calendarDate(String.join(SEPARATOR, pieces[2], pieces[3], pieces[4]));
        } catch (DateTimeException unreadable) {
            ValidationError refusal = notAKey(made);
            refusal.initCause(unreadable);
            throw refusal;
        }
        return new Reading(pieces[0], pieces[1], departure, pieces[FIELDS - 1]);
    }

    /** A calendar date from the year 1 on, which is as far back as a departure is ever written. */
    private static LocalDate calendarDate(String text) {
        Matcher date = CALENDAR_DATE.matcher(text);
        if (!date.matches()) {
            throw new DateTimeException("not a calendar date: " + text);
        }
        int year = Integer.parseInt(date.group(1));
        if (year < 1) {
            throw new DateTimeException("no year before the first: " + text);
        }
        return LocalDate.of(year, Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
    }

    private static ValidationError notAKey(String made) {
        return new ValidationError("not a series key", Details.of("key", made));
    }

    /**
     * The four fields of a series key before they are joined.
     *
     * <p>Kept apart from the join so that an offer can group on the fields directly. Grouping on the
     * joined string and taking it apart again is how the format leaks back out into the callers.
     *
     * @param origin the origin city
     * @param destination the destination city
     * @param departure the departure day in ISO 8601, such as {@code 2025-08-04}
     * @param cabin the cabin letter, upper case
     */
    public record Parts(String origin, String destination, String departure, String cabin) {

        /** The key the fields make: {@code FRA-NYC-2025-08-04-Y}. */
        public String joined() {
            return String.join(SEPARATOR, origin, destination, departure, cabin);
        }
    }

    /**
     * A series key read back into its fields.
     *
     * @param origin the origin, as the key has it
     * @param destination the destination, as the key has it
     * @param departure the departure day
     * @param cabin the cabin, as the key has it
     */
    public record Reading(String origin, String destination, LocalDate departure, String cabin) {}
}
