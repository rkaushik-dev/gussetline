package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.collect.Adapter;
import io.github.rkaushikdev.gussetline.collect.Collector;
import io.github.rkaushikdev.gussetline.collect.Fetch;
import io.github.rkaushikdev.gussetline.collect.Pool;
import io.github.rkaushikdev.gussetline.collect.Rig;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Sink;
import io.github.rkaushikdev.gussetline.collect.Task;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Where the commands reach outside the process: the fetch a collection asks the suppliers through,
 * and the pool that works a collection's tasks.
 *
 * <p>The command line runs with {@link #standard()}. A test stands in for one part, so a collection
 * can answer from a stub instead of a supplier, or go down partway through the way a machine that
 * reboots does, while every other part of the command runs as it would.
 *
 * @param liveFetch the fetch a collection run asks the suppliers through, made for the run's rig and
 *     closed when the run is done
 * @param poolRun how a collection run's tasks are worked through its pool
 */
record Wiring(Function<Rig, Rig.LiveFetch> liveFetch, PoolRun poolRun) {

    Wiring {
        Objects.requireNonNull(liveFetch, "liveFetch");
        Objects.requireNonNull(poolRun, "poolRun");
    }

    /** A fetch that goes out to every supplier, and a pool that works the tasks across its workers. */
    static Wiring standard() {
        return new Wiring(Rig::liveFetch, Pool::run);
    }

    /** The same wiring with another fetch for a collection run. */
    Wiring withLiveFetch(Function<Rig, Rig.LiveFetch> value) {
        return new Wiring(value, poolRun);
    }

    /** The same wiring with another way of working a collection run's tasks. */
    Wiring withPoolRun(PoolRun value) {
        return new Wiring(liveFetch, value);
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
}
