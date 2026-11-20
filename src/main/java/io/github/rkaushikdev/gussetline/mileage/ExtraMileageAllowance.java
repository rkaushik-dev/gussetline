package io.github.rkaushikdev.gussetline.mileage;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filed mileage deductions, by city pair and by intermediate point.
 *
 * <p>A pair allowance is filed for a market and comes off whichever way round the market is flown. A
 * point deduction is filed for a market and a point on it, which is how a carrier makes its own hub
 * free, and comes off only when the point is flown. Codes are read in any case.
 */
public final class ExtraMileageAllowance {

    private final Map<Pair, Integer> pairs = new HashMap<>();
    private final Map<Point, Integer> points = new HashMap<>();

    /** File an allowance for a market, both ways round. A later filing replaces an earlier one. */
    public void addPair(String origin, String destination, int miles) {
        Pair pair = Pair.of(origin, destination);
        pairs.put(pair, miles);
        pairs.put(new Pair(pair.destination(), pair.origin()), miles);
    }

    /** File a deduction for a market and a point on it, in that direction only. */
    public void addPoint(String origin, String destination, String via, int miles) {
        points.put(new Point(upper(origin), upper(destination), upper(via)), miles);
    }

    /**
     * The total allowance, the pair first and then every named point.
     *
     * <p>Both are subtractions and they stack. A carrier that files a pair allowance and a hub
     * deduction on the same market means both, which is the only reading that reproduces the fares
     * it actually sells.
     */
    public int deductionFor(String origin, String destination, List<String> via) {
        int total = pairs.getOrDefault(Pair.of(origin, destination), 0);
        for (String point : via) {
            total += points.getOrDefault(new Point(upper(origin), upper(destination), upper(point)), 0);
        }
        return total;
    }

    private static String upper(String code) {
        return code.toUpperCase(Locale.ROOT);
    }

    /** A market in the direction it was named. */
    private record Pair(String origin, String destination) {

        static Pair of(String origin, String destination) {
            return new Pair(upper(origin), upper(destination));
        }
    }

    /** A market and an intermediate point on it. */
    private record Point(String origin, String destination, String via) {}
}
