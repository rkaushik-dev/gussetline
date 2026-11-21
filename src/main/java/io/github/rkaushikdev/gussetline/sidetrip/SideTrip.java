package io.github.rkaushikdev.gussetline.sidetrip;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One side trip: an excursion that leaves a journey and comes back to the point it left.
 *
 * <p>A passenger flying Frankfurt to New York who goes Frankfurt, Vienna, Frankfurt, New York has
 * taken a side trip to Vienna. It is a real shape, agents sell it constantly, and it breaks two things
 * that otherwise work.
 *
 * <p>It breaks the mileage check. Vienna is a point the itinerary touches, so the obvious reading puts
 * it on the path of the transatlantic component, and the flown mileage then includes a thousand miles
 * east that the transatlantic fare has nothing to do with. The component fails the mileage ladder and
 * prices as a construction the passenger did not buy. A side trip is priced on its own and its points
 * do not belong to the component it hangs off.
 *
 * <p>It breaks the journey type. Frankfurt to Vienna to Frankfurt to New York is not a circle trip and
 * it is not two round trips. Strip the side trip ({@link #without}) and what is left is a plain one
 * way, which is what it is.
 *
 * <p>Finding one ({@link #find}) is easy to get wrong in the direction that costs money. A point that
 * repeats is not a side trip if the journey ends there, because that is a round trip and it prices as
 * one. It is not a side trip if it repeats at the start, because that is where the journey began. And
 * the run between the two visits has to be a genuine excursion: a connection through Frankfurt on the
 * way to New York touches it twice in an afternoon and is a connection, not a side trip, which is why
 * the time between the two visits is checked.
 *
 * <p>Category 10 has a side trip flag and it is not optional ({@link #permitted}). A fare that does
 * not permit one prices the whole thing as one component, which is exactly the mileage failure above,
 * and that failure is the correct answer: the passenger has to buy something else.
 *
 * @param at the point the excursion hangs off, as the journey names it
 * @param first the index of the excursion's first element in the journey
 * @param last the index of its last element, which comes back to {@code at}
 * @param elements the excursion's elements, in journey order
 */
public record SideTrip(String at, int first, int last, List<Element> elements) {

    /** A point touched twice inside this is a connection and not an excursion. */
    public static final Duration LEAST_SIDE_TRIP = Duration.ofHours(24);

    /**
     * Keeps a copy of the elements.
     *
     * @throws ValidationError when there are no elements (naming the point it is {@code at}), or the
     *     first index is after the last (naming the {@code first})
     */
    public SideTrip {
        Objects.requireNonNull(at, "at");
        elements = List.copyOf(elements);
        if (elements.isEmpty()) {
            throw new ValidationError("a side trip goes somewhere", Details.of("at", at));
        }
        if (first > last) {
            throw new ValidationError("a side trip runs forwards", Details.of("first", first));
        }
    }

    /** {@link #find(Itinerary, Duration)} with the usual {@link #LEAST_SIDE_TRIP}. */
    public static List<SideTrip> find(Itinerary itinerary) {
        return find(itinerary, LEAST_SIDE_TRIP);
    }

    /**
     * Every excursion in a journey, outermost first.
     *
     * <p>Outermost first because a side trip inside a side trip is priced with the outer one and pulling
     * the inner one out first leaves the outer with a hole in the middle of it. An excursion runs from an
     * element to the last later element that comes back to the city it left, provided that is not the
     * journey's last element and the journey was away for at least {@code least}.
     */
    public static List<SideTrip> find(Itinerary itinerary, Duration least) {
        List<Element> elements = itinerary.elements();
        List<SideTrip> found = new ArrayList<>();
        int index = 0;

        while (index < elements.size()) {
            Optional<Integer> close = returnsTo(itinerary, index, least);
            if (close.isEmpty()) {
                index++;
                continue;
            }
            int end = close.get();
            found.add(new SideTrip(elements.get(index).origin(), index, end, elements.subList(index, end + 1)));
            index = end + 1;
        }
        return List.copyOf(found);
    }

    /**
     * The journey with its excursions taken out, which is what prices. No excursions hands back the
     * same journey.
     *
     * <p>What is left has to join up on its own, and it does: an excursion leaves a point and returns to
     * it, so removing the whole run leaves the element before it arriving where the element after it
     * departs.
     *
     * @throws ValidationError when nothing is left
     */
    public static Itinerary without(Itinerary itinerary, List<SideTrip> trips) {
        if (trips.isEmpty()) {
            return itinerary;
        }
        Set<Integer> cut = new HashSet<>();
        for (SideTrip trip : trips) {
            for (int index = trip.first; index <= trip.last; index++) {
                cut.add(index);
            }
        }
        List<Element> kept = new ArrayList<>();
        List<Element> elements = itinerary.elements();
        for (int index = 0; index < elements.size(); index++) {
            if (!cut.contains(index)) {
                kept.add(elements.get(index));
            }
        }
        if (kept.isEmpty()) {
            throw new ValidationError("a journey cannot be all side trip");
        }
        return new Itinerary(kept);
    }

    /**
     * Every reason these excursions may not be priced as side trips. An empty list is the pass.
     *
     * <p>A fare with no category 10 filed refuses, the same as everywhere else: the safe reading of a
     * filing we do not hold is that it does not permit something, because the alternative prices a
     * construction the carrier will not sell.
     */
    public static List<String> permitted(
            List<SideTrip> trips, String carrier, String fareBasis, CombinabilityStore store) {
        if (trips.isEmpty()) {
            return List.of();
        }
        Optional<Combinability> record = store.get(carrier, fareBasis);
        if (record.isEmpty()) {
            return List.of(carrier + " " + fareBasis + " has no combinability filed");
        }
        if (!record.get().sideTrip()) {
            return List.of(carrier + " " + fareBasis + " does not permit a side trip");
        }
        return List.of();
    }

    /**
     * How many great circle miles the excursions account for, flown or driven, for the run report.
     *
     * <p>Worth a number of its own because it is the difference between the mileage a naive check would
     * use and the one the component is entitled to, and that difference is what a surcharge band
     * argument turns on.
     */
    public static int milesOff(Itinerary itinerary, List<SideTrip> trips) {
        int miles = 0;
        for (SideTrip trip : trips) {
            for (Element element : trip.elements) {
                miles += Distance.greatCircle(element.origin(), element.destination());
            }
        }
        return miles;
    }

    /** Everywhere the excursion goes, as cities in the order first reached, not counting where it hangs off. */
    public List<String> points() {
        String home = Geography.cityOf(at);
        Set<String> seen = new LinkedHashSet<>();
        for (Element element : elements) {
            String city = Geography.cityOf(element.destination());
            if (!city.equals(home)) {
                seen.add(city);
            }
        }
        return List.copyOf(seen);
    }

    /** The furthest point of the excursion, which is what it is called by: the last point it reaches. */
    public String turnaround() {
        List<String> found = points();
        return found.isEmpty() ? Geography.cityOf(at) : found.get(found.size() - 1);
    }

    /** How many elements the excursion has. */
    public int size() {
        return elements.size();
    }

    /** {@code side trip to VIE off FRA}. */
    @Override
    public String toString() {
        return "side trip to " + turnaround() + " off " + Geography.cityOf(at);
    }

    /**
     * Where the journey comes back to where element {@code start} left, if it does.
     *
     * <p>The last return and not the first, so an excursion that touches its own board point in the
     * middle comes back as one side trip rather than two.
     */
    private static Optional<Integer> returnsTo(Itinerary itinerary, int start, Duration least) {
        List<Element> elements = itinerary.elements();
        String point = Geography.cityOf(elements.get(start).origin());
        Optional<Integer> close = Optional.empty();
        for (int index = start; index < elements.size(); index++) {
            if (!Geography.cityOf(elements.get(index).destination()).equals(point)) {
                continue;
            }
            if (index == elements.size() - 1) {
                // Ending here means the journey came home, which is a round trip
                // and not an excursion. Only the last element counts: a journey
                // that touches its origin in the middle and carries on has left
                // again, and that is exactly what a side trip is.
                break;
            }
            if (apart(elements.get(start), elements.get(index)).compareTo(least) < 0) {
                continue;
            }
            close = Optional.of(index);
        }
        return close;
    }

    /**
     * How long the journey was away, so a connection is not read as a trip.
     *
     * <p>A surface sector at either end carries no clock. An excursion the passenger drove is still an
     * excursion, so it is let through as if it took {@link #LEAST_SIDE_TRIP}.
     */
    private static Duration apart(Element leaving, Element returning) {
        if (leaving instanceof Segment out && returning instanceof Segment back) {
            LocalDateTime left = out.departure();
            return Duration.between(left, back.arrival());
        }
        return LEAST_SIDE_TRIP;
    }
}
