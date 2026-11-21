package io.github.rkaushikdev.gussetline.negotiated;

import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Private fares, keyed on the account they are invisible without.
 *
 * <p>Each fare is held under its account's code, its carrier and its market, folded to cities the
 * way the tariff files it, so a caller asking for Frankfurt to Newark finds a fare filed to New York.
 */
public final class NegotiatedStore {

    private static final Comparator<Negotiated> CHEAPEST_FIRST = Comparator.comparing(
                    (Negotiated filed) -> filed.fare().amount().amount())
            .thenComparing(filed -> filed.fare().fareBasis());

    private final Map<Key, List<Negotiated>> byAccount = new LinkedHashMap<>();

    /** Hold a private fare, after any already held for the same account and market, and hand it back. */
    public Negotiated add(Negotiated filed) {
        Key key = new Key(
                filed.account().code(),
                Carriers.normaliseCode(filed.carrier()),
                filed.fare().origin(),
                filed.fare().destination());
        byAccount.computeIfAbsent(key, ignored -> new ArrayList<>()).add(filed);
        return filed;
    }

    /** Hold several private fares, in order. */
    public void addMany(Iterable<Negotiated> filed) {
        filed.forEach(this::add);
    }

    /** {@link #forAccount(List, String, String, String, Optional)} whatever the sale date. */
    public List<Negotiated> forAccount(List<String> codes, String carrier, String origin, String destination) {
        return forAccount(codes, carrier, origin, destination, Optional.empty());
    }

    /** {@link #forAccount(List, String, String, String, Optional)} on sale on one date. */
    public List<Negotiated> forAccount(
            List<String> codes, String carrier, String origin, String destination, LocalDate on) {
        return forAccount(codes, carrier, origin, destination, Optional.of(on));
    }

    /**
     * Every private fare a caller presenting these codes may see, cheapest first, ties going to the
     * fare basis that sorts first.
     *
     * <p>Nothing at all when no code is presented. Not the cheapest published one, not a fallback: a
     * caller without a code has no business seeing another customer's terms and gets an empty list.
     * The codes are read in any case, each once; the ends fold to cities.
     *
     * @param on the ticketing date the fares have to be on sale on, when it matters
     * @throws io.github.rkaushikdev.gussetline.ValidationError when the carrier designator is
     *     malformed or an end is neither a city nor an airport, and a code was presented
     */
    public List<Negotiated> forAccount(
            List<String> codes, String carrier, String origin, String destination, Optional<LocalDate> on) {
        List<Negotiated> found = new ArrayList<>();
        for (String code : Negotiated.presented(codes)) {
            Key key = new Key(
                    code, Carriers.normaliseCode(carrier), Geography.cityOf(origin), Geography.cityOf(destination));
            for (Negotiated filed : byAccount.getOrDefault(key, List.of())) {
                if (on.isEmpty() || filed.fare().sellableOn(on.get())) {
                    found.add(filed);
                }
            }
        }
        found.sort(CHEAPEST_FIRST);
        return List.copyOf(found);
    }

    /** The codes of the accounts the store holds fares for, each once, sorted. */
    public List<String> accounts() {
        TreeSet<String> codes = new TreeSet<>();
        byAccount.keySet().forEach(key -> codes.add(key.code()));
        return List.copyOf(codes);
    }

    /** How many private fares are held. */
    public int size() {
        return byAccount.values().stream().mapToInt(List::size).sum();
    }

    private record Key(String code, String carrier, String origin, String destination) {}
}
