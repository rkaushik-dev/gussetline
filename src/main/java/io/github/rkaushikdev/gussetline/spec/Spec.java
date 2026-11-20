package io.github.rkaushikdev.gussetline.spec;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Flights;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.flights.YearRoll;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.itinerary.SurfaceSector;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading an itinerary the way an agent types one.
 *
 * <p>Nobody at the desk is going to fill in a form. They have been typing sell entries at a green
 * screen for twenty years and the shape of one is fixed: {@code FRA LH400 K 04AUG JFK LH401 K 18AUG
 * FRA}. A point, then a flight and a class and a date, then the point it lands at, and so on. A
 * surface sector, where the passenger gets themselves between two points, is two slashes in place
 * of the flight: {@code FRA LH400 K 04AUG MUC // VIE LH500 K 18AUG FRA}.
 *
 * <p>Dates have no year on them, because the tariff's own date format has none, and the year cannot
 * be read off one date in isolation. It comes from the order: the first date is the next occurrence
 * on or after the day the entry is being priced for, and every later date rolls forward the moment
 * the month goes backwards ({@link YearRoll}). An outbound on 28DEC and an inbound on 04JAN is a
 * nine day trip over a new year, not a journey that finishes eleven months before it starts. Getting
 * that wrong produces a negative maximum stay and a fare that fails a category 7 check nobody filed.
 *
 * <p>Times are optional, written after the date as {@code 04AUG/1025}, and when they are missing
 * they are made up rather than left out. A segment with no times cannot be tested for a stopover,
 * cannot have its day change worked out, and cannot be priced against a category 2 rule, so a
 * missing time is filled with a nominal block off the great circle distance ({@link #nominalBlock})
 * and the departure is put at midday. Midday rather than midnight because a midnight departure with
 * a nominal block turns every eastbound sector into a day change that did not happen. A made up time
 * is marked {@linkplain Segment#estimated() estimated}, so nothing downstream mistakes it for
 * something a supplier said.
 *
 * <p>Made up times also have to agree with each other. Two sectors on one date both put at midday
 * means the second leaves an hour before the first lands, and the itinerary refuses itself. So a
 * made up departure is pushed past the previous arrival by a nominal connection when the two share
 * a date. That is not a claim about the real timetable. It is the least the entry has to say for
 * the rest of the ladder to be able to read it at all.
 */
public final class Spec {

    /** What an entry types in place of a flight for a sector the passenger covers on the ground. */
    public static final String SURFACE = "//";

    /** The cruising speed a made up sector assumes, which is a jet's. */
    public static final int CRUISE_MILES_PER_HOUR = 500;

    /** The taxiing a made up sector adds to its time in the air. */
    public static final int GROUND_MINUTES = 30;

    /** The hour a sector typed without a time is put at. */
    public static final int NOMINAL_DEPARTURE_HOUR = 12;

    /**
     * The least time a made up connection is given, so a same day connection does not read as a
     * sector that leaves before the one before it lands.
     */
    public static final int NOMINAL_CONNECTION_MINUTES = 60;

    /** The tariff's three letter months, by name, January first. */
    public static final Map<String, Integer> MONTHS = months();

    /** The shortest block a made up sector is given, in minutes. */
    private static final int SHORTEST_BLOCK_MINUTES = 60;

    private static final Pattern POINT = Pattern.compile("[A-Z]{3}");
    private static final Pattern FLIGHT = Pattern.compile("([A-Z0-9]{2})(\\d{1,4})", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DATE =
            Pattern.compile("(\\d{1,2})([A-Z]{3})(?:/(\\d{4}))?", Pattern.UNICODE_CHARACTER_CLASS);

    private Spec() {}

    /**
     * The tariff's three letter month, in any case and with white space around it. Anything else is a
     * refusal.
     *
     * @throws ValidationError naming the {@code month} when it is not one
     */
    public static int monthOf(String name) {
        Integer month = MONTHS.get(Text.code(name));
        if (month == null) {
            throw new ValidationError("that is not a month", Details.of("month", name));
        }
        return month;
    }

    /**
     * Minutes a sector would take if nobody told us: the great circle at {@link
     * #CRUISE_MILES_PER_HOUR}, in whole minutes rounded down, plus {@link #GROUND_MINUTES}. Never less
     * than an hour.
     *
     * @throws ValidationError when either code is not an airport
     */
    public static int nominalBlock(String origin, String destination) {
        int miles = Distance.greatCircle(origin, destination);
        int flying = Math.floorDiv(miles * 60, CRUISE_MILES_PER_HOUR);
        return Math.max(flying + GROUND_MINUTES, SHORTEST_BLOCK_MINUTES);
    }

    /**
     * Read a sell entry into an itinerary, priced from a given day.
     *
     * <p>The entry is split on white space and commas, and is read in any case. An agent who breaks
     * the entry across sectors types the board point again on the next one, and the repeat is
     * skipped.
     *
     * @param text the entry as typed
     * @param on the day the entry is priced for, which decides the year of its first date
     * @throws ValidationError when the entry is empty, has no sector, or has a token that is not what
     *     its place in the entry calls for, naming the {@code token}; or when the itinerary it
     *     describes is refused
     */
    public static Itinerary parse(String text, LocalDate on) {
        List<String> tokens = Text.words(text.replace(",", " "));
        if (tokens.isEmpty()) {
            throw new ValidationError("nothing to price");
        }

        YearRoll roll = YearRoll.fromDate(on);
        List<Element> elements = new ArrayList<>();
        String point = readPoint(tokens.get(0));
        int index = 1;

        while (index < tokens.size()) {
            String token = Text.code(tokens.get(index));
            if (token.equals(SURFACE)) {
                point = addSurface(tokens, index, point, elements);
                index += 2;
            } else if (token.equals(point)) {
                index += 1;
            } else {
                point = addFlown(tokens, index, point, elements, roll);
                index += 4;
            }
        }

        if (elements.isEmpty()) {
            throw new ValidationError("an entry needs at least one sector", Details.of("entry", text));
        }
        return new Itinerary(elements);
    }

    /**
     * Write an itinerary back out in the form it was typed in.
     *
     * <p>A round trip through this and back is the only check that the reader and the writer agree,
     * and it is worth having because the writer is what a stored itinerary is shown as. Times are not
     * written, only dates.
     */
    public static String render(Itinerary itinerary) {
        List<Element> elements = itinerary.elements();
        List<String> parts = new ArrayList<>();
        parts.add(elements.get(0).origin());
        for (Element element : elements) {
            if (element instanceof Segment flown) {
                parts.add(flown.marketingCarrier() + flown.flightNumber());
                parts.add(flown.bookingClass());
                parts.add(String.format(
                        Locale.ROOT, "%02d%s", flown.departure().getDayOfMonth(), monthName(flown.departure())));
                parts.add(flown.destination());
            } else {
                parts.add(SURFACE);
                parts.add(element.destination());
            }
        }
        return String.join(" ", parts);
    }

    /** A surface sector from the point reached, to the token after the slashes. Returns where it lands. */
    private static String addSurface(List<String> tokens, int index, String point, List<Element> elements) {
        if (index + 1 >= tokens.size()) {
            throw new ValidationError("a surface sector needs somewhere to arrive", Details.of("at", point));
        }
        String landing = readPoint(tokens.get(index + 1));
        elements.add(new SurfaceSector(point, landing));
        return landing;
    }

    /** A flown sector from the point reached, read from the next four tokens. Returns where it lands. */
    private static String addFlown(
            List<String> tokens, int index, String point, List<Element> elements, YearRoll roll) {
        if (index + 3 >= tokens.size()) {
            throw new ValidationError(
                    "a sector is a flight, a class, a date and a point",
                    Details.of("after", point, "saw", String.join(" ", tokens.subList(index, tokens.size()))));
        }
        Flight flight = readFlight(tokens.get(index));
        String booking = readBookingClass(tokens.get(index + 1));
        When when = readWhen(tokens.get(index + 2), roll);
        String landing = readPoint(tokens.get(index + 3));

        LocalDateTime departure = when.timed() ? when.at() : after(when.at(), elements);
        elements.add(segment(flight, booking, point, landing, departure, when.timed()));
        return landing;
    }

    /**
     * Push a made up departure past the sector before it, on the same date.
     *
     * <p>Only for made up times, and only within a date. A real time is what the supplier said and is
     * never moved, and a sector on a later date has a whole night in front of it.
     */
    private static LocalDateTime after(LocalDateTime when, List<Element> elements) {
        Optional<Segment> before = elements.stream()
                .filter(Segment.class::isInstance)
                .map(Segment.class::cast)
                .reduce((first, second) -> second);
        if (before.isEmpty()) {
            return when;
        }
        LocalDateTime landed = before.get().arrival();
        if (!landed.toLocalDate().equals(when.toLocalDate())) {
            return when;
        }
        LocalDateTime earliest = landed.plusMinutes(NOMINAL_CONNECTION_MINUTES);
        return when.isBefore(earliest) ? earliest : when;
    }

    private static Segment segment(
            Flight flight, String booking, String origin, String destination, LocalDateTime departure, boolean timed) {
        int block = nominalBlock(origin, destination);
        LocalDateTime arrival = departure.plusMinutes(block);
        int change = Flights.inferDayChange(origin, destination, departure, arrival, block);
        return new Segment(
                flight.carrier(),
                flight.number(),
                origin,
                destination,
                departure,
                arrival,
                booking,
                Cabin.ECONOMY,
                Optional.empty(),
                Optional.empty(),
                change,
                0,
                Optional.of(block),
                !timed);
    }

    private static String readPoint(String token) {
        String code = Text.code(token);
        if (!POINT.matcher(code).matches()) {
            throw new ValidationError("that is not a point", Details.of("token", token));
        }
        Geography.airport(code);
        return code;
    }

    /** A flight, with the leading zeros of its number dropped: {@code LH0400} is LH 400. */
    private static Flight readFlight(String token) {
        Matcher found = FLIGHT.matcher(Text.code(token));
        if (!found.matches()) {
            throw new ValidationError("that is not a flight", Details.of("token", token));
        }
        String number = found.group(2).replaceFirst("^0+", "");
        return new Flight(found.group(1), number.isEmpty() ? "0" : number);
    }

    private static String readBookingClass(String token) {
        String code = Text.code(token);
        if (code.codePointCount(0, code.length()) != 1 || !Character.isLetter(code.codePointAt(0))) {
            throw new ValidationError("a booking class is one letter", Details.of("token", token));
        }
        return code;
    }

    /** A date with an optional time, the year taken from the roll. */
    private static When readWhen(String token, YearRoll roll) {
        Matcher found = DATE.matcher(Text.code(token));
        if (!found.matches()) {
            throw new ValidationError("that is not a date", Details.of("token", token));
        }
        int day = Numbers.parseInt(found.group(1));
        int month = monthOf(found.group(2));
        String clock = found.group(3);

        LocalDate date;
        try {
            date = roll.resolve(month, day);
        } catch (IllegalArgumentException noSuchDay) {
            ValidationError refusal = new ValidationError("no such day", Details.of("token", token));
            refusal.initCause(noSuchDay);
            throw refusal;
        }

        if (clock == null) {
            return new When(date.atTime(NOMINAL_DEPARTURE_HOUR, 0), false);
        }
        int hour = Numbers.parseInt(Text.slice(clock, 0, 2));
        int minute = Numbers.parseInt(Text.slice(clock, 2, 4));
        if (hour > 23 || minute > 59) {
            throw new ValidationError("that is not a time", Details.of("token", token));
        }
        return new When(date.atTime(hour, minute), true);
    }

    private static String monthName(LocalDateTime departure) {
        int month = departure.getMonthValue();
        return MONTHS.entrySet().stream()
                .filter(entry -> entry.getValue() == month)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Integer> months() {
        Map<String, Integer> byName = new LinkedHashMap<>();
        List<String> names =
                List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
        for (int index = 0; index < names.size(); index++) {
            byName.put(names.get(index), index + 1);
        }
        return Collections.unmodifiableMap(byName);
    }

    /** A carrier and a flight number as an entry typed them. */
    private record Flight(String carrier, String number) {}

    /** A departure, and whether its time was typed or made up. */
    private record When(LocalDateTime at, boolean timed) {}
}
