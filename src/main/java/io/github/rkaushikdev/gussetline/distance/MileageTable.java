package io.github.rkaushikdev.gussetline.distance;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MileageUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Published ticketed point mileages, keyed on city pair and global indicator.
 *
 * <p>Keys are city pairs and not airport pairs, because the tariff publishes LON to NYC and never
 * Gatwick to Newark. A lookup folds airports up to their metro before it goes anywhere near the
 * table, and a pair is the same pair whichever way round it is asked for.
 *
 * <p>A pair filed with no indicator answers for every indicator, which is how most short haul pairs
 * are published. A pair filed with one answers only for that one, and the two forms can both be
 * present, in which case the specific one wins.
 *
 * <p>Every method that takes two points refuses, with a {@link ValidationError}, a code that is
 * neither a city nor an airport.
 */
public final class MileageTable {

    private final Map<Filing, Integer> exact = new HashMap<>();
    private final Map<Pair, Integer> any = new HashMap<>();

    /** File a mileage that answers for every indicator. */
    public void add(String left, String right, int miles) {
        add(left, right, miles, Optional.empty());
    }

    /** File a mileage that answers for one indicator only. */
    public void add(String left, String right, int miles, GlobalIndicator indicator) {
        add(left, right, miles, Optional.of(indicator));
    }

    /**
     * File a mileage, for one indicator or for all of them. A later filing of the same key replaces
     * the earlier one.
     *
     * @throws MileageUnavailable when the miles are not positive
     */
    public void add(String left, String right, int miles, Optional<GlobalIndicator> indicator) {
        if (miles <= 0) {
            throw new MileageUnavailable("a mileage has to be positive", Details.of("miles", miles));
        }
        Pair pair = Pair.of(left, right);
        if (indicator.isPresent()) {
            exact.put(new Filing(pair, indicator.get()), miles);
        } else {
            any.put(pair, miles);
        }
    }

    /** File several mileages that answer for every indicator. */
    public void addMany(List<Row> rows) {
        for (Row row : rows) {
            add(row.left(), row.right(), row.miles());
        }
    }

    /** Whether a mileage that answers for every indicator is filed. */
    public boolean has(String left, String right) {
        return has(left, right, Optional.empty());
    }

    /** Whether a mileage that answers for the indicator is filed, specific or general. */
    public boolean has(String left, String right, GlobalIndicator indicator) {
        return has(left, right, Optional.of(indicator));
    }

    /** Whether a mileage that answers is filed, without refusing anything that is not. */
    public boolean has(String left, String right, Optional<GlobalIndicator> indicator) {
        return find(Pair.of(left, right), indicator).isPresent();
    }

    /** {@link #published(String, String, Optional)} with no indicator. */
    public int published(String left, String right) {
        return published(left, right, Optional.empty());
    }

    /** {@link #published(String, String, Optional)} for one indicator. */
    public int published(String left, String right, GlobalIndicator indicator) {
        return published(left, right, Optional.of(indicator));
    }

    /**
     * The published mileage: the filing for the indicator if there is one, else the filing for every
     * indicator.
     *
     * @throws MileageUnavailable naming the two cities (in sorted order, as {@code origin} and {@code
     *     destination}) and the indicator asked for, when neither is filed
     */
    public int published(String left, String right, Optional<GlobalIndicator> indicator) {
        Pair pair = Pair.of(left, right);
        return find(pair, indicator)
                .orElseThrow(() -> new MileageUnavailable(
                        "no ticketed point mileage published",
                        Details.of(
                                "origin",
                                pair.one(),
                                "destination",
                                pair.two(),
                                "indicator",
                                indicator.map(GlobalIndicator::value).orElse(null))));
    }

    /** {@link #tpm(String, String, Optional)} with no indicator. */
    public Mileage tpm(String left, String right) {
        return tpm(left, right, Optional.empty());
    }

    /** {@link #tpm(String, String, Optional)} for one indicator. */
    public Mileage tpm(String left, String right, GlobalIndicator indicator) {
        return tpm(left, right, Optional.of(indicator));
    }

    /**
     * The mileage to use: published if the table has it, and the great circle between the two points
     * if not, marked as a fallback. A city stands in by one of its airports for the great circle.
     */
    public Mileage tpm(String left, String right, Optional<GlobalIndicator> indicator) {
        Optional<Integer> found = find(Pair.of(left, right), indicator);
        if (found.isPresent()) {
            return new Mileage(found.get(), true, indicator);
        }
        int measured = Distance.greatCircle(Distance.pointOf(left), Distance.pointOf(right));
        return new Mileage(measured, false, indicator);
    }

    /** How many filings the table holds, general and specific together. */
    public int size() {
        return any.size() + exact.size();
    }

    private Optional<Integer> find(Pair pair, Optional<GlobalIndicator> indicator) {
        if (indicator.isPresent()) {
            Integer specific = exact.get(new Filing(pair, indicator.get()));
            if (specific != null) {
                return Optional.of(specific);
            }
        }
        return Optional.ofNullable(any.get(pair));
    }

    /**
     * One mileage to file for every indicator.
     *
     * @param left one end, an airport or a city
     * @param right the other end
     * @param miles the ticketed point mileage
     */
    public record Row(String left, String right, int miles) {}

    /** Two cities in sorted order, so a pair reads the same either way round. */
    private record Pair(String one, String two) {

        static Pair of(String left, String right) {
            String one = Geography.cityOf(left);
            String two = Geography.cityOf(right);
            return one.compareTo(two) <= 0 ? new Pair(one, two) : new Pair(two, one);
        }
    }

    /** A pair filed for one indicator. */
    private record Filing(Pair pair, GlobalIndicator indicator) {}
}
