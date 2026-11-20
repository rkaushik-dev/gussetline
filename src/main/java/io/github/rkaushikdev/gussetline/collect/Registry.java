package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.BudgetExhausted;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierError;
import io.github.rkaushikdev.gussetline.SupplierThrottled;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The supplier register, and what each one is actually good for.
 *
 * <p>Three suppliers and none of them does the same job. The pricing gateway shops and costs money
 * per call. The offer feed shops and is free but covers a third of the markets. The calendar quotes
 * and cannot be audited at all.
 *
 * <p>A collector that treats them as interchangeable spends gateway calls on markets the feed
 * already covered and asks the calendar for things it cannot answer. So the register holds what each
 * one can do, and the scheduler reads that rather than hard coding the names.
 *
 * <p>The other thing here is the failure classification ({@link #classify}). Every exception the
 * collectors can raise maps onto one {@link Outcome}, and the outcome decides what happens next.
 * Doing that at the call site means three copies that drift, which is how a blocked supplier ended
 * up being retried for two days.
 *
 * <p>A register is filled once and then read. Adding a supplier while another thread reads it is
 * not safe.
 */
public final class Registry {

    private static final Comparator<Supplier> CHEAPEST_FIRST =
            Comparator.comparing(Supplier::costPerCall).thenComparing(Supplier::name);

    private final Map<String, Supplier> suppliers = new LinkedHashMap<>();

    /** An empty register. */
    public Registry() {}

    /**
     * Turn any failure into the one word the run report needs. {@code null}, nothing having gone
     * wrong, is {@link Outcome#OK}.
     *
     * <p>A throttle, a block, a malformed payload and a spent budget each have their own outcome. Any
     * other supplier failure is transient. Any other refusal of ours, a bad market or a fare that is
     * not published, is {@link Outcome#REFUSED}, and anything that is not one of ours at all is
     * treated as transient.
     *
     * <p>A malformed payload is deliberately not transient. Trying it again gets the same bytes and the
     * same parser, and a collector that retries a parse failure burns its whole budget on one broken
     * market.
     */
    public static Outcome classify(Throwable failure) {
        return switch (failure) {
            case null -> Outcome.OK;
            case SupplierThrottled throttled -> Outcome.THROTTLED;
            case SupplierBlocked blocked -> Outcome.BLOCKED;
            case MalformedPayload malformed -> Outcome.MALFORMED;
            case BudgetExhausted spent -> Outcome.BUDGET;
            case SupplierError transport -> Outcome.TRANSIENT;
            case GussetlineError refusal -> Outcome.REFUSED;
            default -> Outcome.TRANSIENT;
        };
    }

    /**
     * Put a market key in the terms everything else uses, which is cities: {@code FRA-JFK} becomes
     * {@code FRA-NYC}. Text that is not a pair of points comes back trimmed and in upper case.
     *
     * <p>A supplier publishes its coverage in airports and the schedule keys its markets by city, so a
     * feed that lists FRA to JFK never matches a task for FRA to NYC and every one of its markets
     * quietly falls through to the gateway. That cost a month of gateway calls for questions the feed
     * would have answered for nothing.
     *
     * @throws ValidationError when either end of a pair is neither a city nor an airport
     */
    public static String foldMarket(String pair) {
        String market = Text.code(pair);
        String[] parts = market.split("-", -1);
        if (parts.length != 2) {
            return market;
        }
        return Geography.cityOf(parts[0]) + "-" + Geography.cityOf(parts[1]);
    }

    /** The three suppliers the desk actually has: the gateway, the offer feed and the calendar. */
    public static Registry defaultRegistry() {
        Registry registry = new Registry();
        registry.add(Supplier.builder("gds", "gateway.example")
                .path("/pricing")
                .capabilities(
                        Set.of(Capability.SHOP, Capability.REPRICE, Capability.TAX_BREAKDOWN, Capability.SEAT_COUNTS))
                .costPerCall(new BigDecimal("0.04"))
                .build());
        registry.add(Supplier.builder("ndc", "offers.example")
                .path("/v2/offers")
                .capabilities(Set.of(Capability.SHOP, Capability.TAX_BREAKDOWN, Capability.SEAT_COUNTS))
                .markets(Set.of("FRA-JFK", "FRA-ORD", "MUC-JFK", "FRA-LHR", "FRA-SIN"))
                .build());
        registry.add(Supplier.builder("calendar", "fares.example")
                .path("/lowfare")
                .capabilities(Set.of(Capability.QUOTE))
                .build());
        return registry;
    }

    /** Hold a supplier under its name, in place of any supplier already under it. */
    public Supplier add(Supplier supplier) {
        suppliers.put(supplier.name(), supplier);
        return supplier;
    }

    /**
     * The supplier under a name, whatever its case and surrounding white space.
     *
     * @throws ValidationError naming the supplier when none is held under that name
     */
    public Supplier get(String name) {
        String key = Text.strip(name).toLowerCase(Locale.ROOT);
        Supplier found = suppliers.get(key);
        if (found == null) {
            throw new ValidationError("no such supplier", Details.of("name", key));
        }
        return found;
    }

    /** Every supplier's name, in alphabetical order. */
    public List<String> names() {
        return suppliers.keySet().stream().sorted().toList();
    }

    /** The suppliers that can be asked for a capability, in the order they were added. */
    public List<Supplier> ableTo(Capability capability) {
        return suppliers.values().stream()
                .filter(supplier -> supplier.can(capability))
                .toList();
    }

    /**
     * Who can answer for this market, free ones first, then by name.
     *
     * <p>Free first because a market both cover should be asked of the feed and not of the gateway,
     * and the only reason it ever went the other way round was that the gateway happened to be added
     * first.
     *
     * @throws ValidationError when the market names a point the desk does not know
     */
    public List<Supplier> forMarket(String market, Capability capability) {
        return ableTo(capability).stream()
                .filter(supplier -> supplier.covers(market))
                .sorted(CHEAPEST_FIRST)
                .toList();
    }

    /** The first supplier {@link #forMarket} names, or none when nobody can answer. */
    public Optional<Supplier> cheapestFor(String market, Capability capability) {
        return forMarket(market, capability).stream().findFirst();
    }

    /** How many suppliers are held. */
    public int size() {
        return suppliers.size();
    }
}
