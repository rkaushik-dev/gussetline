package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One source, what it can do, and what it costs to ask.
 *
 * <p>The name is kept trimmed and in lower case, and the markets are kept folded to cities ({@link
 * Registry#foldMarket}), because a supplier publishes its coverage in airports while the schedule
 * keys its markets by city. An unfolded list matches nothing, and every market quietly falls through
 * to whichever supplier publishes no list at all.
 *
 * <p>Two suppliers are equal when every part is, the cost compared by value whatever its scale.
 *
 * @param name what the desk calls it, trimmed and in lower case
 * @param host the host its calls go to
 * @param path the path its calls go to on that host
 * @param capabilities what it can be asked for
 * @param costPerCall what one call costs; never negative, and nothing for a free supplier
 * @param markets the markets it answers for, as city pairs; empty means every market
 * @param userAgent what the collector calls itself to this supplier
 */
public record Supplier(
        String name,
        String host,
        String path,
        Set<Capability> capabilities,
        BigDecimal costPerCall,
        Set<String> markets,
        String userAgent) {

    /** The path a supplier is asked on when nobody says otherwise. */
    public static final String DEFAULT_PATH = "/";

    /** What the collector calls itself when nobody says otherwise. */
    public static final String DEFAULT_USER_AGENT = "gussetline/1.0";

    /**
     * Trims and lower cases the name and folds the markets to cities.
     *
     * @throws ValidationError when the name is blank, a market names a point the desk does not know,
     *     or the cost is negative, which would be a call that pays us
     */
    public Supplier {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(costPerCall, "costPerCall");
        Objects.requireNonNull(userAgent, "userAgent");
        if (Text.isBlank(name)) {
            throw new ValidationError("a supplier has a name");
        }
        name = Text.strip(name).toLowerCase(Locale.ROOT);
        EnumSet<Capability> able = EnumSet.noneOf(Capability.class);
        able.addAll(capabilities);
        capabilities = Collections.unmodifiableSet(able);
        markets = markets.stream().map(Registry::foldMarket).collect(Collectors.toUnmodifiableSet());
        if (costPerCall.signum() < 0) {
            throw new ValidationError("a call does not pay us", Details.of("cost", costPerCall.toString()));
        }
    }

    /** A free supplier on the default path that can do nothing yet and answers for every market. */
    public Supplier(String name, String host) {
        this(name, host, DEFAULT_PATH, Set.of(), BigDecimal.ZERO, Set.of(), DEFAULT_USER_AGENT);
    }

    /** A supplier built a part at a time, starting from a free one that can do nothing yet. */
    public static Builder builder(String name, String host) {
        return new Builder(name, host);
    }

    /** Whether this supplier can be asked for the capability at all. */
    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * Whether this supplier answers for a market, given as airports or as cities.
     *
     * <p>An empty market set means everything, because two of the three suppliers do not publish a
     * list and refusing them would leave one.
     *
     * @throws ValidationError when the market names a point the desk does not know
     */
    public boolean covers(String market) {
        if (markets.isEmpty()) {
            return true;
        }
        return markets.contains(Registry.foldMarket(market));
    }

    /** Whether a call to this supplier costs nothing. */
    public boolean isFree() {
        return costPerCall.signum() == 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Supplier that
                && name.equals(that.name)
                && host.equals(that.host)
                && path.equals(that.path)
                && capabilities.equals(that.capabilities)
                && costPerCall.compareTo(that.costPerCall) == 0
                && markets.equals(that.markets)
                && userAgent.equals(that.userAgent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, host, path, capabilities, costPerCall.stripTrailingZeros(), markets, userAgent);
    }

    /**
     * The supplier, its host and what it does, capabilities in alphabetical order: {@code gds at
     * gateway.example does quote/shop}, or {@code does nothing}.
     */
    @Override
    public String toString() {
        String able = capabilities.stream().map(Capability::value).sorted().collect(Collectors.joining("/"));
        return name + " at " + host + " does " + (able.isEmpty() ? "nothing" : able);
    }

    /**
     * The parts of a supplier named one at a time, each starting at its default. {@link #build()}
     * checks them together.
     */
    public static final class Builder {

        private final String name;
        private final String host;
        private String path = DEFAULT_PATH;
        private Set<Capability> capabilities = Set.of();
        private BigDecimal costPerCall = BigDecimal.ZERO;
        private Set<String> markets = Set.of();
        private String userAgent = DEFAULT_USER_AGENT;

        private Builder(String name, String host) {
            this.name = name;
            this.host = host;
        }

        public Builder path(String value) {
            path = value;
            return this;
        }

        public Builder capabilities(Collection<Capability> value) {
            capabilities = Set.copyOf(value);
            return this;
        }

        public Builder costPerCall(BigDecimal value) {
            costPerCall = value;
            return this;
        }

        /** The markets it answers for, as airports or as cities. */
        public Builder markets(Collection<String> value) {
            markets = Set.copyOf(value);
            return this;
        }

        public Builder userAgent(String value) {
            userAgent = value;
            return this;
        }

        /**
         * The supplier named so far, the rest at their defaults.
         *
         * @throws ValidationError when the parts cannot describe a supplier
         */
        public Supplier build() {
            return new Supplier(name, host, path, capabilities, costPerCall, markets, userAgent);
        }
    }
}
