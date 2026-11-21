package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.api.KeyRing;
import io.github.rkaushikdev.gussetline.collect.Adapter;
import io.github.rkaushikdev.gussetline.collect.Collector;
import io.github.rkaushikdev.gussetline.collect.Fetch;
import io.github.rkaushikdev.gussetline.collect.Pool;
import io.github.rkaushikdev.gussetline.collect.Rig;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Sink;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Where the commands reach outside the process: the fetch a collection asks the suppliers through,
 * the pool that works a collection's tasks, and the server the service runs on.
 *
 * <p>The command line runs with {@link #standard()}. A test stands in for one part, so a collection
 * can answer from a stub instead of a supplier, or go down partway through the way a machine that
 * reboots does, or the service can be served without a socket being opened, while every other part of
 * the command runs as it would.
 *
 * @param liveFetch the fetch a collection run asks the suppliers through, made for the run's rig and
 *     closed when the run is done
 * @param poolRun how a collection run's tasks are worked through its pool
 * @param server how {@code serve} runs the service
 */
record Wiring(Function<Rig, Rig.LiveFetch> liveFetch, PoolRun poolRun, Server server) {

    Wiring {
        Objects.requireNonNull(liveFetch, "liveFetch");
        Objects.requireNonNull(poolRun, "poolRun");
        Objects.requireNonNull(server, "server");
    }

    /**
     * A fetch that goes out to every supplier, a pool that works the tasks across its workers, and the
     * service started for real ({@link ServeCommands#untilStopped}).
     */
    static Wiring standard() {
        return new Wiring(Rig::liveFetch, Pool::run, ServeCommands::untilStopped);
    }

    /** The same wiring with another fetch for a collection run. */
    Wiring withLiveFetch(Function<Rig, Rig.LiveFetch> value) {
        return new Wiring(value, poolRun, server);
    }

    /** The same wiring with another way of working a collection run's tasks. */
    Wiring withPoolRun(PoolRun value) {
        return new Wiring(liveFetch, value, server);
    }

    /** The same wiring with another way of running the service. */
    Wiring withServer(Server value) {
        return new Wiring(liveFetch, poolRun, value);
    }

    /** How a collection run's tasks are worked through a pool, as {@link Pool#run} works them. */
    @FunctionalInterface
    interface PoolRun {

        RunReport run(
                Pool pool,
                Collector collector,
                List<Task> tasks,
                LocalDate today,
                Fetch fetch,
                Map<String, Adapter> parsers,
                Schedule schedule,
                Sink sink);
    }

    /** How {@code serve} runs the service, as {@link ServeCommands#untilStopped} runs it. */
    @FunctionalInterface
    interface Server {

        /**
         * Run the service on these settings, this database and these keys, bound to {@code host} and
         * {@code port}, until it stops. Once it listens, {@code listening} is told the port it listens
         * on, which is the one it was handed when {@code port} is nought.
         *
         * @param keys the keys it lets in; none lets everything through
         */
        void serve(
                Settings settings,
                Database database,
                Optional<KeyRing> keys,
                String host,
                int port,
                IntConsumer listening);
    }
}
