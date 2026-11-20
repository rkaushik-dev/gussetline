package io.github.rkaushikdev.gussetline.flights;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects the pieces a parser finds and refuses an incomplete segment.
 *
 * <p>Suppliers send the fields in different orders and some of them arrive in a later block of the
 * same payload, so a parser fills one of these in and calls {@link #build()} once. It exists so that
 * a half read segment fails at the parser with the field named, rather than three layers up as a
 * missing value.
 *
 * <p>Every setter returns the builder. A setter given null leaves its field unknown.
 */
public final class SegmentBuilder {

    private String marketingCarrier;
    private String flightNumber;
    private String origin;
    private String destination;
    private LocalDateTime departure;
    private LocalDateTime arrival;
    private String bookingClass;
    private Cabin cabin;
    private String operatingCarrier;
    private String equipment;
    private Integer dayChange;
    private int stops;
    private Integer elapsedMinutes;
    private final List<String> notes = new ArrayList<>();

    public SegmentBuilder marketingCarrier(String carrier) {
        this.marketingCarrier = carrier;
        return this;
    }

    public SegmentBuilder flightNumber(String number) {
        this.flightNumber = number;
        return this;
    }

    public SegmentBuilder origin(String airport) {
        this.origin = airport;
        return this;
    }

    public SegmentBuilder destination(String airport) {
        this.destination = airport;
        return this;
    }

    public SegmentBuilder departure(LocalDateTime wallClock) {
        this.departure = wallClock;
        return this;
    }

    public SegmentBuilder arrival(LocalDateTime wallClock) {
        this.arrival = wallClock;
        return this;
    }

    public SegmentBuilder bookingClass(String letter) {
        this.bookingClass = letter;
        return this;
    }

    public SegmentBuilder cabin(Cabin booked) {
        this.cabin = booked;
        return this;
    }

    public SegmentBuilder operatingCarrier(String carrier) {
        this.operatingCarrier = carrier;
        return this;
    }

    public SegmentBuilder equipment(String aircraft) {
        this.equipment = aircraft;
        return this;
    }

    /** The day change a supplier sent. Left unknown, {@link #build()} works it out. */
    public SegmentBuilder dayChange(Integer days) {
        this.dayChange = days;
        return this;
    }

    public SegmentBuilder stops(int count) {
        this.stops = count;
        return this;
    }

    /** The block time a supplier sent, which decides the day change when none was sent. */
    public SegmentBuilder elapsedMinutes(Integer minutes) {
        this.elapsedMinutes = minutes;
        return this;
    }

    /** Keep a remark the parser made about this segment. */
    public SegmentBuilder note(String remark) {
        notes.add(remark);
        return this;
    }

    /** The remarks kept so far, in the order made. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * The required fields still unknown, in declaration order, by the names the error details use:
     * {@code marketing_carrier}, {@code flight_number}, {@code origin}, {@code destination}, {@code
     * departure}, {@code arrival}, {@code booking_class} and {@code cabin}.
     */
    public List<String> missing() {
        return required().entrySet().stream()
                .filter(field -> field.getValue() == null)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The segment, once every required field is known.
     *
     * <p>A day change that was not sent is worked out by {@link Flights#inferDayChange}, from the
     * block time when there is one and from the wall clocks when there is not. The block time itself
     * stays here: the segment built does not carry it, and is never marked estimated.
     *
     * @throws ValidationError naming the {@code missing} fields when any is unknown, or whatever the
     *     segment itself refuses
     */
    public Segment build() {
        List<String> gaps = missing();
        if (!gaps.isEmpty()) {
            throw new ValidationError("segment is incomplete", Details.of("missing", gaps));
        }
        int days = dayChange != null
                ? dayChange
                : Flights.inferDayChange(origin, destination, departure, arrival, Optional.ofNullable(elapsedMinutes));
        return new Segment(
                marketingCarrier,
                flightNumber,
                origin,
                destination,
                departure,
                arrival,
                bookingClass,
                cabin,
                Optional.ofNullable(operatingCarrier),
                Optional.ofNullable(equipment),
                days,
                stops,
                Optional.empty(),
                false);
    }

    private Map<String, Object> required() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("marketing_carrier", marketingCarrier);
        fields.put("flight_number", flightNumber);
        fields.put("origin", origin);
        fields.put("destination", destination);
        fields.put("departure", departure);
        fields.put("arrival", arrival);
        fields.put("booking_class", bookingClass);
        fields.put("cabin", cabin);
        return fields;
    }
}
