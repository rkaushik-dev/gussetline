package io.github.rkaushikdev.gussetline;

import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.itinerary.SurfaceSector;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared builders. Every test that needs a journey builds it from here.
 *
 * <p>The helpers take wall clock strings because that is what a timetable reads like, and because a
 * test that spells out date and time constructors four times stops being readable at the third
 * sector. Every fixture is built fresh on each call, so a test may change what it is given.
 */
public final class Fixtures {

    private static final DateTimeFormatter WALL_CLOCK =
            DateTimeFormatter.ofPattern("uuuu-M-d H:m", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private Fixtures() {}

    // Times and sectors

    /** Read {@code 2025-03-04 10:25} into a local wall clock time. */
    public static LocalDateTime at(String text) {
        return LocalDateTime.parse(text, WALL_CLOCK);
    }

    /**
     * A sector booked in K in economy, with the day change read off the two wall clock dates.
     *
     * <p>Anything else a test cares about goes on through the segment's {@code with} methods: {@code
     * segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05").withOperatingCarrier("UA")}.
     */
    public static Segment segment(
            String carrier, String number, String origin, String destination, String departure, String arrival) {
        return segment(carrier, number, origin, destination, departure, arrival, "K");
    }

    /** {@link #segment(String, String, String, String, String, String)} booked in another class. */
    public static Segment segment(
            String carrier,
            String number,
            String origin,
            String destination,
            String departure,
            String arrival,
            String bookingClass) {
        return segment(carrier, number, origin, destination, departure, arrival, bookingClass, Cabin.ECONOMY);
    }

    /** {@link #segment(String, String, String, String, String, String)} in another class and cabin. */
    public static Segment segment(
            String carrier,
            String number,
            String origin,
            String destination,
            String departure,
            String arrival,
            String bookingClass,
            Cabin cabin) {
        LocalDateTime departs = at(departure);
        LocalDateTime arrives = at(arrival);
        int dayChange = (int) ChronoUnit.DAYS.between(departs.toLocalDate(), arrives.toLocalDate());
        return new Segment(
                carrier,
                number,
                origin,
                destination,
                departs,
                arrives,
                bookingClass,
                cabin,
                Optional.empty(),
                Optional.empty(),
                dayChange,
                0,
                Optional.empty(),
                false);
    }

    /** A surface sector between two airports. */
    public static SurfaceSector surface(String origin, String destination) {
        return new SurfaceSector(origin, destination);
    }

    // Itineraries

    /** A plain transatlantic round trip, nonstop each way. */
    public static Itinerary frankfurtNewYork() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    /** Frankfurt to New York over London, with a short wait at Heathrow. */
    public static Itinerary withAConnection() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
    }

    /** Frankfurt to New York over London, with two days in London. */
    public static Itinerary withAStopover() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00")));
    }

    // Mileage

    /** Enough published mileage to keep the usual markets off the great circle fallback. */
    public static MileageTable transatlanticMiles() {
        MileageTable table = new MileageTable();
        table.add("FRA", "NYC", 4400);
        table.add("FRA", "LON", 500);
        table.add("LON", "NYC", 4400);
        table.add("FRA", "CHI", 4300);
        table.add("CHI", "NYC", 740);
        table.add("FRA", "DXB", 3000);
        table.add("DXB", "NYC", 6800);
        table.add("FRA", "LAX", 5800);
        table.add("CHI", "LAX", 1700);
        return table;
    }

    /**
     * Mileages that put the usual connecting routings onto a surcharge band.
     *
     * <p>Separate from the roomy table because a test about the band needs the ladder to fire and a
     * test about anything else needs it not to.
     */
    public static MileageTable tightMiles() {
        MileageTable table = new MileageTable();
        table.add("FRA", "NYC", 4000);
        table.add("FRA", "LON", 500);
        table.add("LON", "NYC", 4400);
        table.add("FRA", "DXB", 3000);
        table.add("DXB", "NYC", 6800);
        return table;
    }
}
