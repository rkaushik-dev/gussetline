package io.github.rkaushikdev.gussetline.itinerary;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The journey: an ordered run of segments and surface sectors that joins up, and the breaks in it
 * that cost money.
 *
 * <p>A passenger who waits three hours in Frankfurt is connecting. A passenger who waits thirty
 * hours in Frankfurt has taken a stopover, which is a priced thing the fare either permits free,
 * permits for a charge or forbids outright. The line between the two is a clock reading and nothing
 * else, and the reading is different for domestic travel, which is why it lives here and not in the
 * rule engine.
 *
 * <p>An itinerary that simply jumps from one point to a different one is refused, because the
 * alternative is silently inventing a {@link SurfaceSector} that the fare may not permit. It opens
 * and closes with a flown segment.
 *
 * <p>An itinerary is immutable. Two itineraries are the same only when they are the same object.
 */
public final class Itinerary implements Iterable<Element> {

    /**
     * A break longer than this is a stopover on a journey with any international sector, for every
     * break in it, the domestic ones included.
     */
    public static final Duration STOPOVER_INTERNATIONAL = Duration.ofHours(24);

    /** A break longer than this is a stopover on a wholly domestic journey. */
    public static final Duration STOPOVER_DOMESTIC = Duration.ofHours(4);

    private final List<Element> elements;

    /**
     * An itinerary of the elements in order.
     *
     * @throws ValidationError when there are no elements, when one element does not end where the
     *     next starts (naming the index {@code at} and the points it {@code arrives} at and {@code
     *     departs} from), or when the first or last element is a surface sector
     */
    public Itinerary(List<? extends Element> elements) {
        if (elements.isEmpty()) {
            throw new ValidationError("an itinerary needs at least one element");
        }
        this.elements = List.copyOf(elements);
        checkContinuity(this.elements);
    }

    private static void checkContinuity(List<Element> elements) {
        for (int index = 0; index < elements.size() - 1; index++) {
            Element left = elements.get(index);
            Element right = elements.get(index + 1);
            if (!left.destination().equals(right.origin())) {
                throw new ValidationError(
                        "itinerary does not join up",
                        Details.of("at", index, "arrives", left.destination(), "departs", right.origin()));
            }
        }
        if (elements.get(0) instanceof SurfaceSector) {
            throw new ValidationError("an itinerary does not open with a surface sector");
        }
        if (elements.get(elements.size() - 1) instanceof SurfaceSector) {
            throw new ValidationError("an itinerary does not close with a surface sector");
        }
    }

    /** Every element, flown or not, in order. */
    public List<Element> elements() {
        return elements;
    }

    /** The flown segments alone, in order. */
    public List<Segment> segments() {
        return elements.stream()
                .filter(Segment.class::isInstance)
                .map(Segment.class::cast)
                .toList();
    }

    /** Where the journey starts. */
    public String origin() {
        return elements.get(0).origin();
    }

    /** Where the journey ends. */
    public String destination() {
        return elements.get(elements.size() - 1).destination();
    }

    /** The origin, then where each element ends: one more point than there are elements. */
    public List<String> points() {
        List<String> points = new ArrayList<>(elements.size() + 1);
        points.add(origin());
        elements.forEach(element -> points.add(element.destination()));
        return List.copyOf(points);
    }

    /** The marketing carriers, each once, in the order they first fly. */
    public List<String> carriers() {
        Set<String> seen = new LinkedHashSet<>();
        segments().forEach(segment -> seen.add(segment.marketingCarrier()));
        return List.copyOf(seen);
    }

    /** Whether any element, flown or not, crosses a border. */
    public boolean isInternational() {
        return elements.stream().anyMatch(element -> !Geography.sameCountry(element.origin(), element.destination()));
    }

    /** When the first segment leaves, local wall clock. */
    public LocalDateTime departure() {
        return segments().get(0).departure();
    }

    /** When the last segment lands, local wall clock on its arrival date. */
    public LocalDateTime arrival() {
        List<Segment> flown = segments();
        return landing(flown.get(flown.size() - 1));
    }

    /** The ground time beyond which a break is a stopover. */
    public Duration stopoverThreshold() {
        return isInternational() ? STOPOVER_INTERNATIONAL : STOPOVER_DOMESTIC;
    }

    /**
     * Every intermediate point, with the ground time and the verdict.
     *
     * <p>A surface sector is not a break with a duration, it is a break by definition, so the point
     * where one starts or ends is always a stopover whatever the clock says. Its times run from the
     * last landing before it to the next departure after it. Between two flown segments the break is
     * a stopover when it is longer than {@link #stopoverThreshold()}.
     *
     * @throws ValidationError when a segment leaves before the one before it lands, naming the {@code
     *     point}
     */
    public List<Break> breaks() {
        Duration threshold = stopoverThreshold();
        List<Break> found = new ArrayList<>();
        for (int index = 0; index < elements.size() - 1; index++) {
            Element left = elements.get(index);
            Element right = elements.get(index + 1);
            if (left instanceof Segment landed && right instanceof Segment next) {
                LocalDateTime arrived = landing(landed);
                LocalDateTime departs = next.departure();
                if (departs.isBefore(arrived)) {
                    throw new ValidationError(
                            "the next sector leaves before the last one lands",
                            Details.of("point", left.destination()));
                }
                boolean stopover = Duration.between(arrived, departs).compareTo(threshold) > 0;
                found.add(new Break(left.destination(), arrived, departs, stopover));
            } else {
                found.add(new Break(left.destination(), arrivalOf(index), departureOf(index + 1), true));
            }
        }
        return List.copyOf(found);
    }

    /**
     * The break after an element when it and the next one are both flown, which is the only kind of
     * break a passenger connects through. Empty after a surface sector, before one, and after the
     * last element.
     *
     * @throws ValidationError as {@link #breaks()} does
     */
    public Optional<Break> connectionAfter(int index) {
        if (index < 0
                || index >= elements.size() - 1
                || !(elements.get(index) instanceof Segment)
                || !(elements.get(index + 1) instanceof Segment)) {
            return Optional.empty();
        }
        return Optional.of(breaks().get(index));
    }

    /**
     * Every connection between two flown sectors shorter than the airport's minimum ({@link
     * MinimumConnectingTime#between}), in order, each as {@code LHR 35 minutes, 90 needed}. Empty
     * when every connection can be made.
     */
    public List<String> shortConnections() {
        List<String> found = new ArrayList<>();
        for (int index = 0; index < elements.size() - 1; index++) {
            Optional<Break> ground = connectionAfter(index);
            if (ground.isEmpty()) {
                continue;
            }
            Duration needed =
                    MinimumConnectingTime.between((Segment) elements.get(index), (Segment) elements.get(index + 1));
            if (ground.get().duration().compareTo(needed) < 0) {
                found.add(ground.get().point() + " " + ground.get().minutes() + " minutes, " + needed.toMinutes()
                        + " needed");
            }
        }
        return List.copyOf(found);
    }

    /** The points where the journey stops over, in order. */
    public List<String> stopovers() {
        return breaks().stream().filter(Break::stopover).map(Break::point).toList();
    }

    /** The points where the journey only connects, in order. */
    public List<String> connections() {
        return breaks().stream()
                .filter(found -> !found.stopover())
                .map(Break::point)
                .toList();
    }

    /**
     * Which of the four shapes the tariff prices this as.
     *
     * <p>The test for a round trip is that the way home mirrors the way out, city for city, and not
     * that the journey ends where it started. A loop out through New York and home through Los
     * Angeles also ends where it started and is a circle trip, which prices on a different minimum. A
     * journey that ends in another city is a one way when it ends at its furthest point and an open
     * jaw when it turned round somewhere else. Airports of one city count as the same point.
     */
    public JourneyType journeyType() {
        List<String> cities = points().stream().map(Geography::cityOf).toList();
        int last = cities.size() - 1;
        if (!cities.get(0).equals(cities.get(last))) {
            return turnaroundIndex() != last ? JourneyType.OPEN_JAW : JourneyType.ONE_WAY;
        }
        int turn = turnaroundIndex();
        List<String> outbound = cities.subList(0, turn + 1);
        List<String> inbound = cities.subList(turn, cities.size());
        return inbound.equals(outbound.reversed()) ? JourneyType.ROUND_TRIP : JourneyType.CIRCLE_TRIP;
    }

    /**
     * Where in {@link #points()} the journey turns round: the point furthest from the origin by great
     * circle.
     *
     * <p>The first point at the maximum distance wins, so a journey that sits at its furthest city
     * for two sectors breaks the fare on arrival there rather than on departure, which is what the
     * tariff does.
     */
    public int turnaroundIndex() {
        List<String> points = points();
        int bestIndex = 0;
        int bestMiles = -1;
        for (int index = 0; index < points.size(); index++) {
            int miles = Distance.greatCircle(origin(), points.get(index));
            if (miles > bestMiles) {
                bestIndex = index;
                bestMiles = miles;
            }
        }
        return bestIndex;
    }

    /** The point furthest from the origin, which is where a fare breaks. */
    public String turnaround() {
        return points().get(turnaroundIndex());
    }

    /** The great circle miles of every element added up, the surface sectors included. */
    public int totalMiles() {
        return elements.stream()
                .mapToInt(element -> Distance.greatCircle(element.origin(), element.destination()))
                .sum();
    }

    /** Whether any element is a surface sector. */
    public boolean hasSurface() {
        return elements.stream().anyMatch(SurfaceSector.class::isInstance);
    }

    /** How many elements the itinerary has, flown or not. */
    public int size() {
        return elements.size();
    }

    @Override
    public Iterator<Element> iterator() {
        return elements.iterator();
    }

    /** Each element as a ticket shows it, separated by spaces. */
    @Override
    public String toString() {
        return elements.stream().map(Element::toString).collect(Collectors.joining(" "));
    }

    /** When the passenger last landed at or before an element. */
    private LocalDateTime arrivalOf(int index) {
        int at = index;
        Element element = elements.get(at);
        while (!(element instanceof Segment segment)) {
            element = elements.get(--at);
        }
        return landing(segment);
    }

    /** When the passenger next takes off at or after an element. */
    private LocalDateTime departureOf(int index) {
        int at = index;
        Element element = elements.get(at);
        while (!(element instanceof Segment segment)) {
            element = elements.get(++at);
        }
        return segment.departure();
    }

    /** The landing of a segment as a wall clock reading on its arrival date. */
    private static LocalDateTime landing(Segment segment) {
        return LocalDateTime.of(segment.arrivalDate(), segment.arrival().toLocalTime());
    }
}
