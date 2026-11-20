package io.github.rkaushikdev.gussetline.collect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;

/**
 * A breaker per supplier, made on first sight, all on one clock.
 *
 * <p>Like the breakers it holds, a board is not safe to share between threads on its own.
 */
public final class BreakerBoard {

    private final DoubleSupplier clock;
    private final int threshold;
    private final double cooldown;
    private final Map<String, Breaker> breakers = new LinkedHashMap<>();

    /** A board whose breakers take the usual threshold and cooldown. */
    public BreakerBoard(DoubleSupplier clock) {
        this(clock, Breaker.THRESHOLD, Breaker.COOLDOWN);
    }

    /**
     * A board whose breakers trip after {@code threshold} failures and cool for {@code cooldown}
     * seconds. Both are checked when the first breaker is made.
     */
    public BreakerBoard(DoubleSupplier clock, int threshold, double cooldown) {
        this.clock = clock;
        this.threshold = threshold;
        this.cooldown = cooldown;
    }

    public int threshold() {
        return threshold;
    }

    /** The cooling period for every breaker, in seconds. */
    public double cooldown() {
        return cooldown;
    }

    /** The breaker for a supplier, made the first time it is asked for. */
    public Breaker forSupplier(String name) {
        return breakers.computeIfAbsent(name, key -> new Breaker(key, threshold, cooldown, clock));
    }

    /** Whether a call to the supplier may go out. */
    public boolean allow(String name) {
        return forSupplier(name).allow();
    }

    public void succeeded(String name) {
        forSupplier(name).succeeded();
    }

    public void failed(String name) {
        forSupplier(name).failed();
    }

    /** The suppliers whose breakers are open, by name. */
    public List<String> openSuppliers() {
        return breakers.entrySet().stream()
                .filter(entry -> entry.getValue().isOpen())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Every supplier the board has seen and the state of its breaker, by name. */
    public Map<String, String> summary() {
        Map<String, String> summary = new LinkedHashMap<>();
        new TreeMap<>(breakers)
                .forEach((name, breaker) -> summary.put(name, breaker.state().value()));
        return Collections.unmodifiableMap(summary);
    }

    /** How many suppliers the board holds a breaker for. */
    public int size() {
        return breakers.size();
    }
}
