package io.github.rkaushikdev.gussetline.routing;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The paths one routing number permits.
 *
 * <p>Every published fare carries a routing number. If it is {@link #MILEAGE_ROUTING}, the fare may
 * go anywhere the mileage principle permits and the surcharge bands decide the price. If it is a
 * real number, the fare names the paths it is good for and nothing else will do, however short the
 * detour.
 *
 * <p>A routing map is a directed graph of city pairs with the carriers permitted on each {@link
 * Hop}, and a component matches when its cities trace a path from the board point to the off point
 * using only permitted hops. Two things make that less trivial than it sounds. A map can be filed
 * both directions and read either way round, and a hop can permit any carrier, which is how a
 * carrier files its own online routings and then adds an interline hop for the last leg.
 *
 * <p>Carriers are given positionally against the hops of the path checked, and the list may be
 * shorter than the path: the hops past its end are checked with no carrier, and so pass only on a
 * hop that permits any carrier. A null entry reads as no carrier too.
 */
public final class RoutingMap {

    /** The routing number that means there is no map: price it on mileage. */
    public static final String MILEAGE_ROUTING = "0000";

    private final String number;
    private final boolean bidirectional;
    private final List<Hop> hops = new ArrayList<>();
    private final Map<String, List<Hop>> out = new HashMap<>();

    /** A map filed both directions. */
    public RoutingMap(String number) {
        this(number, true);
    }

    /**
     * A map under a routing number, trimmed.
     *
     * @param bidirectional whether every hop added may also be flown the other way round
     * @throws ValidationError when the number is blank
     */
    public RoutingMap(String number, boolean bidirectional) {
        this.number = Text.strip(number);
        if (this.number.isEmpty()) {
            throw new ValidationError("a routing map needs a number");
        }
        this.bidirectional = bidirectional;
    }

    /** The routing number, trimmed. */
    public String number() {
        return number;
    }

    /** Whether every hop added is also filed the other way round. */
    public boolean isBidirectional() {
        return bidirectional;
    }

    /** {@link #add(String, String, Collection)} for a hop any carrier may fly. */
    public Hop add(String origin, String destination) {
        return add(origin, destination, Set.of());
    }

    /**
     * File one hop, and its reverse too when the map is bidirectional.
     *
     * @return the hop as filed in the direction given
     */
    public Hop add(String origin, String destination, Collection<String> carriers) {
        Hop hop = new Hop(origin, destination, Set.copyOf(carriers));
        register(hop);
        if (bidirectional) {
            register(hop.reversed());
        }
        return hop;
    }

    /** {@link #addString(List, Collection)} with every hop open to any carrier. */
    public void addString(List<String> points) {
        addString(points, List.of());
    }

    /**
     * File a whole path at once, which is how maps are actually filed: one hop between each point
     * and the next, every one of them for the same carriers.
     *
     * @throws ValidationError when the path has fewer than two points
     */
    public void addString(List<String> points, Collection<String> carriers) {
        if (points.size() < 2) {
            throw new ValidationError("a routing string needs two points");
        }
        List<String> held = List.copyOf(carriers);
        for (int index = 0; index < points.size() - 1; index++) {
            add(points.get(index), points.get(index + 1), held);
        }
    }

    /** Whether this is the mileage routing, which has no map. */
    public boolean isMileage() {
        return number.equals(MILEAGE_ROUTING);
    }

    /** Every hop filed, reverses included, in the order filed. */
    public List<Hop> hops() {
        return List.copyOf(hops);
    }

    /** Every city on the map, sorted. */
    public List<String> points() {
        Set<String> found = new TreeSet<>();
        for (Hop hop : hops) {
            found.add(hop.origin());
            found.add(hop.destination());
        }
        return List.copyOf(found);
    }

    /** {@link #permits(List, List)} with no carriers given. */
    public boolean permits(List<String> cities) {
        return permits(cities, List.of());
    }

    /**
     * Whether a city sequence traces a permitted path through the map. The mileage routing permits
     * every path. Airports are folded up to their cities first.
     *
     * @throws ValidationError when fewer than two points are given to a real map
     */
    public boolean permits(List<String> cities, List<String> carriers) {
        if (isMileage()) {
            return true;
        }
        if (cities.size() < 2) {
            throw new ValidationError("a routing needs two points to check");
        }
        List<String> folded = cities.stream().map(Geography::cityOf).toList();
        for (int index = 0; index < folded.size() - 1; index++) {
            if (!hopPermits(folded.get(index), folded.get(index + 1), carrierAt(carriers, index))) {
                return false;
            }
        }
        return true;
    }

    /** {@link #refusal(List, List)} with no carriers given. */
    public Optional<String> refusal(List<String> cities) {
        return refusal(cities, List.of());
    }

    /**
     * The first hop the map refuses, said in words, or nothing when the path passes.
     *
     * <p>The desk sends these to suppliers, so the sentence matters more than the boolean does. A
     * refusal that says the routing failed and nothing else costs an analyst twenty minutes with a
     * printed map. A hop the map files for other carriers reads {@code routing 0112 does not permit
     * BA on FRA-NYC}, the carrier as given and {@code None} when there was none; a hop it does not
     * file at all reads {@code routing 0111 does not permit FRA-AMS at all}.
     */
    public Optional<String> refusal(List<String> cities, List<String> carriers) {
        if (isMileage()) {
            return Optional.empty();
        }
        List<String> folded = cities.stream().map(Geography::cityOf).toList();
        for (int index = 0; index < folded.size() - 1; index++) {
            Optional<String> carrier = carrierAt(carriers, index);
            String origin = folded.get(index);
            String destination = folded.get(index + 1);
            if (hopPermits(origin, destination, carrier)) {
                continue;
            }
            boolean filed = out.getOrDefault(origin, List.of()).stream()
                    .anyMatch(hop -> hop.destination().equals(destination));
            if (filed) {
                String who = Text.plain(carrier.orElse(null));
                return Optional.of(
                        "routing " + number + " does not permit " + who + " on " + origin + "-" + destination);
            }
            return Optional.of("routing " + number + " does not permit " + origin + "-" + destination + " at all");
        }
        return Optional.empty();
    }

    /** How many hops are filed, reverses included. */
    public int size() {
        return hops.size();
    }

    /** The map in one line: {@code routing 0114 over 3 points}. */
    @Override
    public String toString() {
        return "routing " + number + " over " + points().size() + " points";
    }

    private void register(Hop hop) {
        hops.add(hop);
        out.computeIfAbsent(hop.origin(), city -> new ArrayList<>()).add(hop);
    }

    private boolean hopPermits(String origin, String destination, Optional<String> carrier) {
        for (Hop hop : out.getOrDefault(origin, List.of())) {
            if (hop.destination().equals(destination) && hop.permits(carrier)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> carrierAt(List<String> carriers, int index) {
        return index < carriers.size() ? Optional.ofNullable(carriers.get(index)) : Optional.empty();
    }
}
