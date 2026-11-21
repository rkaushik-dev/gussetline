package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Everything a collection run needs, built once from the settings: assembling a collector out of the
 * parts, which nothing else should do.
 *
 * <p>Every piece below this is independent and testable on its own, and that is worth keeping. The
 * cost is that something has to know how they fit together, and when that something is the command
 * line it gets copied into the second command and then the two drift. So the assembly is here, once
 * ({@link #build}).
 *
 * <p>The part worth explaining is the replay fetch ({@link #replayFetch()}). Every payload a
 * collector has ever taken is in the archive, keyed by content, with the market and the departure
 * written next to it in the manifest. That makes a second fetch possible that never touches the
 * network: given a task, it looks up the newest stored payload for that market and departure and
 * hands it back as though the supplier had just answered. A whole day of collection then runs
 * against a new reader in a second.
 *
 * <p>That is not a test fixture. It is how a reader bug gets fixed: the payload that broke it is
 * already stored, so the fix can be proved against the real bytes rather than against a reduction of
 * them somebody typed out afterwards. It is also the only way to reread a month of history when a
 * supplier's date format turns out to have changed three weeks ago.
 *
 * <p>A replayed task with nothing stored for it is not an error either. It means that market was
 * never collected, and the run records it the same way it would record a supplier that had nothing
 * to say.
 */
public final class Rig {

    /** How a live fetch asks one supplier: a GET of a path with query parameters. */
    @FunctionalInterface
    public interface Client {

        Response get(String path, Map<String, String> params);
    }

    private final Settings settings;
    private final Registry registry;
    private final Limiter limiter;
    private final BreakerBoard breakers;
    private final Archive archive;
    private final Map<String, Adapter> parsers;
    private final DoubleSupplier clock;
    private final java.util.function.Supplier<LocalDateTime> now;

    private Rig(Builder parts, Registry registry) {
        this.settings = parts.settings;
        this.registry = registry;
        this.clock = parts.clock;
        this.now = parts.now;
        this.limiter = new Limiter(clock, settings.ratePerSecond(), settings.burst(), settings.dailyQuota());
        this.breakers = new BreakerBoard(clock, settings.breakerThreshold(), settings.breakerCooldown());
        this.archive = new Archive(settings.archiveRoot());
        this.parsers = new LinkedHashMap<>(parts.parsers);
    }

    /** {@link #builder} with the default register, no readers, the system's clocks, and nothing else. */
    public static Rig build(Settings settings) {
        return builder(settings).build();
    }

    /**
     * Wire the parts together the one way they go together, once the builder has been told whatever
     * differs from the defaults.
     *
     * <p>Every supplier's host is given its budget here rather than on first use, so a run against a
     * supplier that is completely down still respects its budget, and so the settings command can
     * print what a run would allow before anything runs.
     */
    public static Builder builder(Settings settings) {
        return new Builder(settings);
    }

    public Settings settings() {
        return settings;
    }

    public Registry registry() {
        return registry;
    }

    public Limiter limiter() {
        return limiter;
    }

    public BreakerBoard breakers() {
        return breakers;
    }

    public Archive archive() {
        return archive;
    }

    /** The reader for each supplier, by name, as registered so far. The map follows later registrations. */
    public Map<String, Adapter> parsers() {
        return Collections.unmodifiableMap(parsers);
    }

    /** The clock in seconds the budgets and the breakers run on. */
    public DoubleSupplier clock() {
        return clock;
    }

    /** What the rig calls the current moment. */
    public LocalDateTime now() {
        return now.get();
    }

    /** A collector over this rig's parts. Every collector from one rig shares its budgets, breakers and archive. */
    public Collector collector() {
        return new Collector(registry, limiter, breakers, archive, now);
    }

    /** Read a supplier's payloads with this reader from now on, in place of any it had. */
    public void register(String supplier, Adapter parser) {
        parsers.put(Objects.requireNonNull(supplier, "supplier"), Objects.requireNonNull(parser, "parser"));
    }

    /**
     * A fetch that actually goes out, over one HTTP client per supplier: {@code https://} the
     * supplier's host, the settings' timeout and attempts, and the supplier's user agent. Closing it
     * lets go of the clients it opened.
     */
    public LiveFetch liveFetch() {
        return new LiveFetch(this, null);
    }

    /**
     * A fetch that goes out over whatever client {@code clientFor} gives for each supplier, asked once
     * per supplier. Those clients are the giver's to close.
     */
    public LiveFetch liveFetch(Function<Supplier, ? extends Client> clientFor) {
        return new LiveFetch(this, Objects.requireNonNull(clientFor, "clientFor"));
    }

    /**
     * A fetch that answers out of the archive and never leaves the machine.
     *
     * <p>The archive is indexed when the fetch is made: for every supplier, market and departure, the
     * newest stored payload. Newest rather than first, because a market polled every day has thirty
     * payloads and the only one worth replaying is the one that broke. A payload stored with no market
     * or departure beside it is not replayable.
     *
     * <p>The fetch answers a task with status 200, one attempt and the address {@code
     * replay://<supplier><path>}, and throws {@link SupplierUnavailable} naming the supplier and the
     * market when nothing is stored for it.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a stored payload does not read back
     *     as what it is filed as
     * @throws NoSuchElementException when a manifest row lacks its supplier, stamp or digest
     */
    public Fetch replayFetch() {
        return replay(Optional.empty());
    }

    /** {@link #replayFetch()} over only the payloads one supplier sent. */
    public Fetch replayFetch(String supplierOnly) {
        return replay(Optional.of(supplierOnly));
    }

    /** {@code 3 suppliers, 0 parsers, quota 1000 a day}. */
    @Override
    public String toString() {
        return registry.size() + " suppliers, " + parsers.size() + " parsers, quota " + settings.dailyQuota()
                + " a day";
    }

    private Fetch replay(Optional<String> supplierOnly) {
        Map<Stored, byte[]> stored = index(supplierOnly);
        return (supplier, task) -> {
            byte[] found = stored.get(new Stored(
                    supplier.name(), task.market().key(), task.departure().toString()));
            if (found == null) {
                throw new SupplierUnavailable(
                        "nothing stored for that market",
                        Details.of(
                                "supplier",
                                supplier.name(),
                                "market",
                                task.market().key()));
            }
            return new Response(200, found, Map.of(), 1, 0, "replay://" + supplier.name() + supplier.path());
        };
    }

    /** The newest stored payload for every supplier, market and departure. */
    private Map<Stored, byte[]> index(Optional<String> supplierOnly) {
        Map<Stored, Newest> newest = new LinkedHashMap<>();
        for (Map<String, Object> row : archive.manifest()) {
            String supplier = Text.plain(field(row, "supplier"));
            if (supplierOnly.isPresent() && !supplier.equals(supplierOnly.get())) {
                continue;
            }
            Map<?, ?> context = row.get("context") instanceof Map<?, ?> given ? given : Map.of();
            Object market = context.get("market");
            Object departure = context.get("departure");
            if (!present(market) || !present(departure)) {
                continue;
            }
            Stored key = new Stored(supplier, Text.plain(market), Text.plain(departure));
            String seen = Text.plain(field(row, "seen_at"));
            Newest before = newest.get(key);
            if (before == null || seen.compareTo(before.seen()) > 0) {
                newest.put(key, new Newest(seen, Text.plain(field(row, "digest"))));
            }
        }

        Map<Stored, byte[]> payloads = new LinkedHashMap<>();
        newest.forEach((key, found) -> payloads.put(key, archive.get(found.digest())));
        return payloads;
    }

    /** Whether a manifest value says something: there, and not empty text. */
    private static boolean present(Object value) {
        return value != null && !(value instanceof String text && text.isEmpty());
    }

    private static Object field(Map<String, Object> row, String name) {
        if (!row.containsKey(name)) {
            throw new NoSuchElementException("a manifest row without a " + name + ": " + row);
        }
        return row.get(name);
    }

    /** What a replay looks a payload up by. */
    private record Stored(String supplier, String market, String departure) {}

    /** The newest sighting of a market so far: when it was seen, and the payload's digest. */
    private record Newest(String seen, String digest) {}

    /**
     * A fetch that goes out, one client per supplier, made the first time that supplier is asked. It
     * may be shared between threads, and closing it lets go of the clients it opened itself.
     */
    public static final class LiveFetch implements Fetch, AutoCloseable {

        private final Rig rig;
        /** Where each supplier's client comes from; null when this fetch opens its own. */
        private final Function<Supplier, ? extends Client> clientFor;

        private final Map<String, Client> clients = new ConcurrentHashMap<>();
        private final List<HttpClient> opened = new CopyOnWriteArrayList<>();

        private LiveFetch(Rig rig, Function<Supplier, ? extends Client> clientFor) {
            this.rig = rig;
            this.clientFor = clientFor;
        }

        /** A GET of the supplier's path asking for the task's origin, destination and departure date. */
        @Override
        public Response fetch(Supplier supplier, Task task) {
            Client client = clients.computeIfAbsent(
                    supplier.name(), name -> clientFor != null ? clientFor.apply(supplier) : open(supplier));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("origin", task.market().origin());
            params.put("destination", task.market().destination());
            params.put("departure", task.departure().toString());
            return client.get(supplier.path(), Collections.unmodifiableMap(params));
        }

        /** Let go of the clients this fetch opened. */
        @Override
        public void close() {
            opened.forEach(HttpClient::close);
        }

        private Client open(Supplier supplier) {
            HttpClient made = HttpClient.builder()
                    .baseUrl("https://" + supplier.host())
                    .timeout(rig.settings.requestTimeout())
                    .policy(new RetryPolicy(rig.settings.retryAttempts()))
                    .headers(Map.of("User-Agent", supplier.userAgent()))
                    .build();
            opened.add(made);
            return made::get;
        }
    }

    /** The parts of a rig that may differ from the defaults, named one at a time. */
    public static final class Builder {

        private final Settings settings;
        private Registry registry;
        private Map<String, Adapter> parsers = Map.of();
        private DoubleSupplier clock = () -> System.nanoTime() / 1e9;
        private java.util.function.Supplier<LocalDateTime> now = LocalDateTime::now;

        private Builder(Settings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        /** The suppliers to collect from, in place of the three the desk has. */
        public Builder registry(Registry value) {
            registry = Objects.requireNonNull(value, "registry");
            return this;
        }

        /** The reader for each supplier, by name. The rig keeps a copy, in the same order. */
        public Builder parsers(Map<String, Adapter> value) {
            parsers = new LinkedHashMap<>(value);
            return this;
        }

        /** A clock in seconds that only moves forward, for the budgets and the breakers. */
        public Builder clock(DoubleSupplier value) {
            clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /** What the rig and its collectors call the current moment. */
        public Builder now(java.util.function.Supplier<LocalDateTime> value) {
            now = Objects.requireNonNull(value, "now");
            return this;
        }

        /**
         * The rig, every supplier's host budgeted.
         *
         * @throws java.io.UncheckedIOException when the archive's directories cannot be made
         */
        public Rig build() {
            Rig rig = new Rig(this, registry != null ? registry : Registry.defaultRegistry());
            for (String name : rig.registry.names()) {
                rig.limiter.configure(rig.registry.get(name).host());
            }
            return rig;
        }
    }
}
