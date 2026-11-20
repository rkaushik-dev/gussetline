package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Rate limiting, per host and per day, on a clock we control: a {@link HostBudget} for every host
 * the collectors talk to.
 *
 * <p>Two different budgets, and they run out for different reasons. The {@link TokenBucket} is
 * politeness, the {@link DailyQuota} is commercial. A host the limiter has not seen gets the default
 * budget the first time it is asked about.
 *
 * <p>A limiter is not safe to share between threads on its own; whoever shares one guards it.
 */
public final class Limiter {

    /** Tokens per second a host refills at unless told otherwise. */
    public static final double RATE = 1.0;

    /** Tokens a host's bucket holds unless told otherwise. */
    public static final int BURST = 5;

    /** Requests a day a host is sold unless told otherwise. */
    public static final int QUOTA = 1000;

    private final DoubleSupplier clock;
    private final double defaultRate;
    private final int defaultBurst;
    private final int defaultQuota;
    private final Map<String, HostBudget> hosts = new LinkedHashMap<>();

    /** A limiter whose hosts get the usual rate, burst and quota. */
    public Limiter(DoubleSupplier clock) {
        this(clock, RATE, BURST, QUOTA);
    }

    /** A limiter whose hosts get the given rate, burst and quota unless configured otherwise. */
    public Limiter(DoubleSupplier clock, double defaultRate, int defaultBurst, int defaultQuota) {
        this.clock = clock;
        this.defaultRate = defaultRate;
        this.defaultBurst = defaultBurst;
        this.defaultQuota = defaultQuota;
    }

    public double defaultRate() {
        return defaultRate;
    }

    public int defaultBurst() {
        return defaultBurst;
    }

    public int defaultQuota() {
        return defaultQuota;
    }

    /** Give a host a fresh default budget, replacing any it had. */
    public HostBudget configure(String host) {
        return configure(host, defaultRate, defaultBurst, defaultQuota);
    }

    /**
     * Give a host a fresh budget of its own, replacing any it had.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when the rate, burst or quota is not
     *     one a budget can hold
     */
    public HostBudget configure(String host, double rate, int burst, int quota) {
        HostBudget budget = new HostBudget(host, new TokenBucket(rate, burst, clock), new DailyQuota(quota));
        hosts.put(host, budget);
        return budget;
    }

    /** The budget for a host, made with the defaults the first time it is asked for. */
    public HostBudget budgetFor(String host) {
        HostBudget budget = hosts.get(host);
        return budget != null ? budget : configure(host);
    }

    /** Whether one request may go out to a host right now. */
    public boolean allow(String host, LocalDate today) {
        return budgetFor(host).allow(today);
    }

    /** How many requests each host has refused, for the hosts that have refused any. */
    public Map<String, Integer> refused() {
        Map<String, Integer> refused = new LinkedHashMap<>();
        hosts.forEach((host, budget) -> {
            if (budget.refusals() > 0) {
                refused.put(host, budget.refusals());
            }
        });
        return Collections.unmodifiableMap(refused);
    }

    /** Every host with a budget, by name. */
    public List<String> knownHosts() {
        return hosts.keySet().stream().sorted().toList();
    }
}
