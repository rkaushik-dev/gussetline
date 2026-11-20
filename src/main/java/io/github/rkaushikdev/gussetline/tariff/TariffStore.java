package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every fare the desk holds, indexed on the key a lookup actually uses: the carrier and the market.
 *
 * <p>Within a market the fares are held in the order they were added. A search answers cheapest
 * first and a dump answers in a settled order, so neither depends on that order; a lookup by basis
 * takes the first one added.
 */
public final class TariffStore {

    private static final Comparator<Fare> CHEAPEST =
            Comparator.comparing((Fare fare) -> fare.amount().amount()).thenComparing(Fare::fareBasis);

    private static final Comparator<Fare> SETTLED =
            Comparator.comparing(Fare::fareBasis).thenComparing(Fare::effective);

    private final Map<CarrierMarket, List<Fare>> byMarket = new TreeMap<>();
    private int count;

    /** Hold a fare after the others in its market, and hand it back. */
    public Fare add(Fare fare) {
        byMarket.computeIfAbsent(marketOf(fare), market -> new ArrayList<>()).add(fare);
        count++;
        return fare;
    }

    /** Hold each fare in turn. */
    public void addMany(Iterable<Fare> fares) {
        for (Fare fare : fares) {
            add(fare);
        }
    }

    /**
     * Drop a fare the carrier has withdrawn. Says whether it was there.
     *
     * <p>Matched on the key and on the effective date together, because a carrier files the same
     * fare basis over and over with new windows and a withdrawal names exactly one of them. Matching
     * on the key alone deletes a fare that is still on sale next month. Every held copy that matches
     * goes.
     */
    public boolean remove(Fare fare) {
        CarrierMarket market = marketOf(fare);
        List<Fare> held = byMarket.get(market);
        if (held == null) {
            return false;
        }
        Fare.Key wanted = fare.key();
        List<Fare> kept = held.stream()
                .filter(f -> !(f.key().equals(wanted) && f.effective().equals(fare.effective())))
                .toList();
        if (kept.size() == held.size()) {
            return false;
        }
        count -= held.size() - kept.size();
        if (kept.isEmpty()) {
            byMarket.remove(market);
        } else {
            byMarket.put(market, new ArrayList<>(kept));
        }
        return true;
    }

    /** Put a fare in, dropping any it amends. Says whether it replaced one. */
    public boolean replace(Fare fare) {
        boolean replaced = remove(fare);
        add(fare);
        return replaced;
    }

    /**
     * Everything held, in a settled order, for a dump or a check: market by market in their order,
     * and within a market by fare basis, then by effective date.
     */
    public List<Fare> allFares() {
        List<Fare> found = new ArrayList<>();
        byMarket.values()
                .forEach(fares -> found.addAll(fares.stream().sorted(SETTLED).toList()));
        return List.copyOf(found);
    }

    /** How many fares are held, across every market. */
    public int size() {
        return count;
    }

    /** Every market something is held for, in order. */
    public List<CarrierMarket> markets() {
        return List.copyOf(byMarket.keySet());
    }

    /** {@link #search(String, String, String, FareFilter)} with no narrowing. */
    public List<Fare> search(String carrier, String origin, String destination) {
        return search(carrier, origin, destination, FareFilter.any());
    }

    /**
     * Every fare that could price this market, cheapest first, then by fare basis.
     *
     * <p>Directional on purpose. A fare filed Frankfurt to New York does not answer a search from New
     * York, because the tariff publishes both directions separately and their amounts differ. The
     * points may be airports or cities; they are read as the cities the tariff files. A market
     * nothing is held for answers empty, which is not an error.
     *
     * @throws ValidationError when the carrier is malformed or a point is unknown
     */
    public List<Fare> search(String carrier, String origin, String destination, FareFilter filter) {
        CarrierMarket market = new CarrierMarket(
                Carriers.normaliseCode(carrier), Geography.cityOf(origin), Geography.cityOf(destination));
        return byMarket.getOrDefault(market, List.of()).stream()
                .filter(filter::admits)
                .sorted(CHEAPEST)
                .toList();
    }

    /** {@link #cheapest(String, String, String, FareFilter)} with no narrowing. */
    public Fare cheapest(String carrier, String origin, String destination) {
        return cheapest(carrier, origin, destination, FareFilter.any());
    }

    /**
     * The cheapest fare that survives the filter, or a refusal saying so.
     *
     * @throws NoFareFound naming the {@code carrier} as given and the {@code origin} and {@code
     *     destination} cities, when nothing survives
     * @throws ValidationError when the carrier is malformed or a point is unknown
     */
    public Fare cheapest(String carrier, String origin, String destination, FareFilter filter) {
        List<Fare> found = search(carrier, origin, destination, filter);
        if (found.isEmpty()) {
            throw new NoFareFound(
                    "nothing published for this market",
                    Details.of(
                            "carrier",
                            carrier,
                            "origin",
                            Geography.cityOf(origin),
                            "destination",
                            Geography.cityOf(destination)));
        }
        return found.get(0);
    }

    /**
     * The first fare added for this market under a fare basis, given in any case and with
     * surrounding white space.
     *
     * @throws NoFareFound naming the {@code carrier} as given, the {@code origin} and {@code
     *     destination} cities and the {@code fare_basis} looked for, when none is held
     * @throws ValidationError when the carrier is malformed or a point is unknown
     */
    public Fare byBasis(String carrier, String origin, String destination, String fareBasis) {
        String wanted = Text.code(fareBasis);
        CarrierMarket market = new CarrierMarket(
                Carriers.normaliseCode(carrier), Geography.cityOf(origin), Geography.cityOf(destination));
        for (Fare fare : byMarket.getOrDefault(market, List.of())) {
            if (fare.fareBasis().equals(wanted)) {
                return fare;
            }
        }
        throw new NoFareFound(
                "no fare with that basis",
                Details.of(
                        "carrier",
                        carrier,
                        "origin",
                        Geography.cityOf(origin),
                        "destination",
                        Geography.cityOf(destination),
                        "fare_basis",
                        wanted));
    }

    private static CarrierMarket marketOf(Fare fare) {
        return new CarrierMarket(fare.carrier(), fare.origin(), fare.destination());
    }
}
