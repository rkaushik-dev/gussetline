package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.discounts.Passenger;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a provision is allowed to look at.
 *
 * <p>Deliberately the whole itinerary and not a digest of it. Category 8 needs the stopovers,
 * category 6 needs the arrival at the turnaround, and both of those are readings of the same journey
 * that have to agree with each other and with the tax assessment.
 *
 * @param itinerary the whole journey
 * @param component the fare component whose rule is being read
 * @param ticketing the date the ticket is issued, or would be
 * @param passenger who travels
 * @param today the date the question is asked on, when it is not the ticketing date
 * @param reissue whether the ticket is being reissued rather than sold
 */
public record Context(
        Itinerary itinerary,
        FareComponent component,
        LocalDate ticketing,
        Passenger passenger,
        Optional<LocalDate> today,
        boolean reissue) {

    public Context {
        Objects.requireNonNull(itinerary, "itinerary");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(ticketing, "ticketing");
        Objects.requireNonNull(passenger, "passenger");
        Objects.requireNonNull(today, "today");
    }

    /** An adult buying a new ticket, asked about on the ticketing date. */
    public Context(Itinerary itinerary, FareComponent component, LocalDate ticketing) {
        this(itinerary, component, ticketing, new Passenger(PassengerType.ADULT), Optional.empty(), false);
    }

    /** A copy for another traveller. */
    public Context withPassenger(Passenger traveller) {
        return new Context(itinerary, component, ticketing, traveller, today, reissue);
    }

    /** A copy asked about on another day than the ticketing date. */
    public Context withToday(LocalDate day) {
        return new Context(itinerary, component, ticketing, passenger, Optional.of(day), reissue);
    }

    /** A copy for a reissue, or for a first sale. */
    public Context withReissue(boolean reissuing) {
        return new Context(itinerary, component, ticketing, passenger, today, reissuing);
    }

    /** The local date the component's first sector leaves. */
    public LocalDate departure() {
        return component.segments().get(0).departure().toLocalDate();
    }

    /** The local date the journey's first sector leaves. */
    public LocalDate journeyDeparture() {
        return itinerary.departure().toLocalDate();
    }

    /**
     * When the passenger got to the far end, which is when a stay starts: the arrival date of the
     * last flown sector before the turnaround. Empty when the journey has no turnaround after its
     * origin.
     *
     * <p>Not the arrival on the itinerary, which is the arrival back home and is the wrong end of the
     * journey. A minimum stay measured from there comes out negative on every round trip, and because
     * it then fails every fare, it reads as a strict rule rather than as a bug.
     */
    public Optional<LocalDate> turnaroundArrival() {
        int turn = itinerary.turnaroundIndex();
        if (turn <= 0 || turn >= itinerary.points().size()) {
            return Optional.empty();
        }
        for (int index = turn - 1; index >= 0; index--) {
            Element element = itinerary.elements().get(index);
            if (element instanceof Segment segment) {
                return Optional.of(segment.arrivalDate());
            }
        }
        return Optional.empty();
    }

    /**
     * When the passenger starts home, or empty on a one way.
     *
     * <p>Read off the first sector after the turnaround rather than off the last sector, because a
     * journey with two sectors home starts home on the first of them and the minimum stay is measured
     * from there.
     */
    public Optional<LocalDate> returnDeparture() {
        int turn = itinerary.turnaroundIndex();
        if (turn <= 0 || turn >= itinerary.elements().size()) {
            return Optional.empty();
        }
        return Optional.of(itinerary.segments().get(turn).departure().toLocalDate());
    }
}
