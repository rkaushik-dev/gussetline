package io.github.rkaushikdev.gussetline.flights;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One flown sector as the timetable describes it.
 *
 * <p>Every supplier sends departure and arrival as local wall clock times with no offset, because
 * that is what a timetable is. Two of them also send a day change digit, one sends none and expects
 * the reader to work it out ({@link Flights#inferDayChange}), and one sends an arrival that is
 * genuinely before the departure and means the next day. So a segment stores wall clock times plus
 * the airport, and converts on demand using the fixed per-airport offsets in the geography table.
 * Summer time is ignored, which is wrong by an hour twice a year for elapsed times and right for
 * everything the tariff cares about, since a connection minimum is checked in local minutes and a
 * fare rule in local dates.
 *
 * <p>The carriers, the airports and the booking class are normalised on the way in. The optional
 * fields default to absent, nought or false; the {@code with} methods give a copy with one of them
 * set.
 *
 * @param marketingCarrier the designator the sector is sold under
 * @param flightNumber the flight number as sent, white space and all; {@link #designator()} trims it
 * @param origin the airport it leaves from
 * @param destination the airport it lands at
 * @param departure the local wall clock at the origin
 * @param arrival the local wall clock at the destination. Only its time of day is read: the date
 *     comes from the departure date and {@code dayChange}
 * @param bookingClass the one letter booking class
 * @param cabin the cabin
 * @param operatingCarrier who actually flies it, when a supplier says
 * @param equipment the aircraft type, when a supplier says
 * @param dayChange how many calendar days the arrival is after the departure date
 * @param stops the intermediate stops on the same flight number
 * @param elapsedMinutes the block time a supplier sent, if it sent one
 * @param estimated whether the times on this sector came from a supplier or were made up because
 *     nobody sent any. Downstream has to be able to tell, since a made up time is fine for a
 *     stopover test and useless for a day and time rule, and nothing else can work out which it is
 */
public record Segment(
        String marketingCarrier,
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departure,
        LocalDateTime arrival,
        String bookingClass,
        Cabin cabin,
        Optional<String> operatingCarrier,
        Optional<String> equipment,
        int dayChange,
        int stops,
        Optional<Integer> elapsedMinutes,
        boolean estimated)
        implements Element {

    private static final DateTimeFormatter TIMETABLE = DateTimeFormatter.ofPattern("ddMMMyy HHmm", Locale.ENGLISH);

    /**
     * Upper cases the carriers and the booking class, and reads both airports to their codes.
     *
     * @throws ValidationError when a carrier or an airport is unknown, the sector ends where it
     *     started, the booking class is not one letter, the flight number is blank, or the day change
     *     or the stops are negative
     */
    public Segment {
        Objects.requireNonNull(marketingCarrier, "marketingCarrier");
        Objects.requireNonNull(flightNumber, "flightNumber");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(bookingClass, "bookingClass");
        Objects.requireNonNull(cabin, "cabin");
        Objects.requireNonNull(operatingCarrier, "operatingCarrier");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(elapsedMinutes, "elapsedMinutes");

        marketingCarrier = Carriers.normaliseCode(marketingCarrier);
        operatingCarrier = operatingCarrier.map(Carriers::normaliseCode);
        origin = Geography.airport(origin).code();
        destination = Geography.airport(destination).code();
        if (origin.equals(destination)) {
            throw new ValidationError("a sector cannot end where it started", Details.of("point", origin));
        }
        if (!isOneLetter(bookingClass)) {
            throw new ValidationError("a booking class is one letter", Details.of("value", bookingClass));
        }
        bookingClass = bookingClass.toUpperCase(Locale.ROOT);
        if (Text.isBlank(flightNumber)) {
            throw new ValidationError("a segment needs a flight number");
        }
        if (dayChange < 0) {
            throw new ValidationError("a flight does not arrive the day before", Details.of("value", dayChange));
        }
        if (stops < 0) {
            throw new ValidationError("stops cannot be negative", Details.of("value", stops));
        }
    }

    /**
     * A nonstop sector the marketing carrier operates, arriving the day it leaves, with nothing else
     * known about it.
     */
    public Segment(
            String marketingCarrier,
            String flightNumber,
            String origin,
            String destination,
            LocalDateTime departure,
            LocalDateTime arrival,
            String bookingClass,
            Cabin cabin) {
        this(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                Optional.empty(),
                false);
    }

    /** A copy flown by another carrier. Naming the marketing carrier itself is not a codeshare. */
    public Segment withOperatingCarrier(String carrier) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                Optional.of(carrier),
                equipment,
                dayChange,
                stops,
                elapsedMinutes,
                estimated);
    }

    /** A copy with the aircraft type. */
    public Segment withEquipment(String aircraft) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                operatingCarrier,
                Optional.of(aircraft),
                dayChange,
                stops,
                elapsedMinutes,
                estimated);
    }

    /** A copy arriving a number of calendar days after the departure date. */
    public Segment withDayChange(int days) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                operatingCarrier,
                equipment,
                days,
                stops,
                elapsedMinutes,
                estimated);
    }

    /** A copy with intermediate stops. */
    public Segment withStops(int count) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                operatingCarrier,
                equipment,
                dayChange,
                count,
                elapsedMinutes,
                estimated);
    }

    /** A copy carrying the block time a supplier sent. */
    public Segment withElapsedMinutes(int minutes) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                operatingCarrier,
                equipment,
                dayChange,
                stops,
                Optional.of(minutes),
                estimated);
    }

    /** A copy marked as carrying made up times, or as carrying real ones. */
    public Segment withEstimated(boolean madeUp) {
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                operatingCarrier,
                equipment,
                dayChange,
                stops,
                elapsedMinutes,
                madeUp);
    }

    /** The carrier and the trimmed flight number together: {@code LH400}. */
    public String designator() {
        return marketingCarrier + Text.strip(flightNumber);
    }

    /** Who actually flies it. Absent means the marketing carrier does. */
    public String operatedBy() {
        return operatingCarrier.orElse(marketingCarrier);
    }

    /** Whether someone other than the marketing carrier flies it. */
    public boolean isCodeshare() {
        return !operatedBy().equals(marketingCarrier);
    }

    /** The local date it leaves. */
    public LocalDate departureDate() {
        return departure.toLocalDate();
    }

    /** The local date it lands: the departure date moved on by the day change. */
    public LocalDate arrivalDate() {
        return departureDate().plusDays(dayChange);
    }

    /** The departure in UTC, through the origin's fixed offset. */
    public LocalDateTime departureUtc() {
        return departure.minusMinutes(Geography.airport(origin).utcOffsetMinutes());
    }

    /** The arrival in UTC: the arrival date at the arrival's time of day, through the destination's offset. */
    public LocalDateTime arrivalUtc() {
        LocalDateTime landed = LocalDateTime.of(arrivalDate(), arrival.toLocalTime());
        return landed.minusMinutes(Geography.airport(destination).utcOffsetMinutes());
    }

    /** Block time, which is the only duration the desk quotes. */
    public Duration elapsed() {
        return Duration.between(departureUtc(), arrivalUtc());
    }

    /** Whether it flies without intermediate stops. */
    public boolean isNonstop() {
        return stops == 0;
    }

    /** What tells this sector from every other one: the flight, its ends and when it leaves. */
    public Key key() {
        return new Key(designator(), origin, destination, Times.iso(departure));
    }

    /** The sector as a timetable line: {@code LH400 FRAJFK 04Mar25 1025}. */
    @Override
    public String toString() {
        return designator() + " " + origin + destination + " " + TIMETABLE.format(departure);
    }

    private static boolean isOneLetter(String text) {
        return text.codePointCount(0, text.length()) == 1 && Character.isLetter(text.codePointAt(0));
    }

    /**
     * The identity of one sector.
     *
     * @param designator the carrier and flight number, such as {@code LH400}
     * @param origin the airport it leaves from
     * @param destination the airport it lands at
     * @param departure the local departure in ISO 8601, such as {@code 2025-03-04T10:25:00}
     */
    public record Key(String designator, String origin, String destination, String departure) {}
}
