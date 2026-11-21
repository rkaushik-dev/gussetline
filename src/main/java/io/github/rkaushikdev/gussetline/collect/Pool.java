package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Running a collection across several workers without being rude about it.
 *
 * <p>A night of collection is nine hundred tasks, each of them a second of waiting on somebody
 * else's server. Doing them one at a time takes fifteen minutes of almost nothing happening. Doing
 * them all at once gets the desk blocked.
 *
 * <p>So the pool is capped per host and not only overall. Two suppliers can be asked at the same
 * time, because they are two companies and neither knows about the other. One supplier cannot be
 * asked six times at once, because that is what a supplier means when they say they blocked us for
 * aggressive polling.
 *
 * <p>The budgets and the breakers the workers share are locked ({@link Guarded}), and so is what an
 * answered task keeps: the schedule learns its prices and the sink takes its harvests one task at a
 * time.
 *
 * <p>The results come back in the order the plan asked for them and not the order they finished. A
 * run report is read next to yesterday's, and one that shuffles its own rows on every run cannot be
 * compared with anything.
 *
 * <p>The work is blocking network calls behind a blocking client, and threads are what that wants.
 * Every thread a run starts has finished when the run returns.
 */
public final class Pool {

    /**
     * How many calls one host takes at a time. Two is polite and it is most of the speed: the
     * waiting is the supplier's and one extra in flight covers it.
     */
    public static final int PER_HOST = 2;

    /** How many workers in total, whatever the hosts say. */
    public static final int WORKERS = 8;

    private final int workers;
    private final int perHost;

    /** A pool of {@link #WORKERS} workers, {@link #PER_HOST} to a host. */
    public Pool() {
        this(WORKERS, PER_HOST);
    }

    /**
     * A pool of {@code workers} workers, at most {@code perHost} of them asking one host at a time.
     *
     * @throws ValidationError naming the {@code workers} when either is below one
     */
    public Pool(int workers, int perHost) {
        if (workers < 1 || perHost < 1) {
            throw new ValidationError("a pool needs at least one worker", Details.of("workers", workers));
        }
        this.workers = workers;
        this.perHost = perHost;
    }

    public int workers() {
        return workers;
    }

    public int perHost() {
        return perHost;
    }

    /** Group a run's tasks under the host each will be asked of, each keeping its place in the plan. */
    public static Plan planByHost(Registry registry, List<Task> tasks) {
        Plan plan = new Plan();
        for (int position = 0; position < tasks.size(); position++) {
            Task task = tasks.get(position);
            plan.add(position, registry.get(task.supplier()).host(), task);
        }
        return plan;
    }

    /** {@link #widest(Plan, int)} at {@link #PER_HOST} to a host. */
    public static int widest(Plan plan) {
        return widest(plan, PER_HOST);
    }

    /**
     * How many workers a plan could possibly keep busy: so many to each host, and never more than
     * there are tasks.
     *
     * <p>More than this and the extra threads sit on a semaphore doing nothing, which is a thread
     * pool that looks busy in a stack dump and is not.
     */
    public static int widest(Plan plan, int perHost) {
        return Math.min(plan.hosts().size() * perHost, plan.size());
    }

    /** Work through a plan with no schedule to teach and nowhere to keep the offers. */
    public RunReport run(
            Collector collector, List<Task> tasks, LocalDate today, Fetch fetch, Map<String, Adapter> parsers) {
        return work(collector, tasks, today, fetch, parsers, null, null);
    }

    /** Work through a plan, teaching the schedule the cheapest price each answer gave. */
    public RunReport run(
            Collector collector,
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule) {
        return work(collector, tasks, today, fetch, parsers, Objects.requireNonNull(schedule, "schedule"), null);
    }

    /** Work through a plan, handing every harvest to the sink. */
    public RunReport run(
            Collector collector,
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Sink sink) {
        return work(collector, tasks, today, fetch, parsers, null, Objects.requireNonNull(sink, "sink"));
    }

    /**
     * Work through a plan across the workers, politely and once, as {@link Collector#run} would on
     * one thread.
     *
     * <p>Takes a collector rather than building one, because everything it holds is already assembled
     * by the rig and a second copy here would be a second archive root and a second breaker board.
     *
     * @throws ValidationError when a task names a supplier the registry does not hold, before any
     *     worker starts
     * @throws CancellationException when the thread is interrupted while it waits for the workers
     */
    public RunReport run(
            Collector collector,
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule,
            Sink sink) {
        return work(
                collector,
                tasks,
                today,
                fetch,
                parsers,
                Objects.requireNonNull(schedule, "schedule"),
                Objects.requireNonNull(sink, "sink"));
    }

    private RunReport work(
            Collector collector,
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule,
            Sink sink) {
        if (tasks.isEmpty()) {
            return new RunReport(collector.now());
        }

        Plan plan = planByHost(collector.registry(), tasks);
        Workload workload = new Workload(collector, plan, today, fetch, parsers, schedule, sink);
        LocalDateTime started = collector.now();

        try (ExecutorService threads = Executors.newFixedThreadPool(
                workers, Thread.ofPlatform().name("collect-", 1).factory())) {
            Map<String, List<Plan.Placed>> grouped = plan.byHost();
            List<Future<?>> waiting = new ArrayList<>();
            for (String host : plan.hosts()) {
                for (Plan.Placed placed : grouped.get(host)) {
                    waiting.add(threads.submit(() -> workload.work(placed)));
                }
            }
            for (Future<?> future : waiting) {
                await(future);
            }
        }

        // Back in the order the plan asked for. A report read next to yesterday's cannot shuffle its
        // own rows.
        return new RunReport(
                started, List.of(workload.results), workload.skippedBreaker.get(), workload.skippedBudget.get());
    }

    /** Wait for one worker, and throw what it threw. */
    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (ExecutionException failed) {
            Throwable thrown = failed.getCause();
            if (thrown instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (thrown instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException stopped = new CancellationException("interrupted while the workers ran");
            stopped.initCause(interrupted);
            throw stopped;
        }
    }

    /** One run across the workers: what they share, and where each one's result goes. */
    private final class Workload {

        private final Collector collector;
        private final LocalDate today;
        private final Fetch fetch;
        private final Map<String, Adapter> parsers;
        private final Schedule schedule;
        private final Sink sink;
        private final Guarded guarded;
        private final Map<String, Semaphore> gates = new LinkedHashMap<>();
        private final Result[] results;
        private final AtomicInteger skippedBreaker = new AtomicInteger();
        private final AtomicInteger skippedBudget = new AtomicInteger();

        Workload(
                Collector collector,
                Plan plan,
                LocalDate today,
                Fetch fetch,
                Map<String, Adapter> parsers,
                Schedule schedule,
                Sink sink) {
            this.collector = collector;
            this.today = today;
            this.fetch = fetch;
            this.parsers = parsers;
            this.schedule = schedule;
            this.sink = sink;
            this.guarded = new Guarded(collector.limiter(), collector.breakers());
            this.results = new Result[plan.size()];
            for (String host : plan.hosts()) {
                gates.put(host, new Semaphore(perHost));
            }
        }

        /** One task: past the breaker and the budget, then through its host's gate. */
        void work(Plan.Placed placed) {
            Task task = placed.task();
            Supplier supplier = collector.registry().get(task.supplier());

            if (!guarded.permits(supplier.name())) {
                skippedBreaker.incrementAndGet();
                results[placed.position()] = new Result(task, Outcome.BLOCKED, "breaker is open");
                return;
            }

            Optional<String> refused = guarded.refusal(supplier.host(), today);
            if (refused.isPresent()) {
                skippedBudget.incrementAndGet();
                results[placed.position()] = new Result(task, Outcome.BUDGET, refused.get());
                return;
            }

            Semaphore gate = gates.get(supplier.host());
            gate.acquireUninterruptibly();
            try {
                results[placed.position()] =
                        collector.runOne(supplier, task, today, fetch, parsers, schedule, sink, guarded);
            } finally {
                gate.release();
            }
        }
    }

    /** A run's tasks, grouped by the host they will be asked of. */
    public static final class Plan {

        /**
         * One task and where it came in the run's plan.
         *
         * @param position its place in the plan, nought first
         * @param task the task
         */
        public record Placed(int position, Task task) {}

        private final Map<String, List<Placed>> byHost = new LinkedHashMap<>();

        /** A plan with nothing in it yet. */
        public Plan() {}

        /** Put a task under its host, keeping its place in the run's plan. */
        public void add(int position, String host, Task task) {
            byHost.computeIfAbsent(host, key -> new ArrayList<>()).add(new Placed(position, task));
        }

        /** The tasks under each host, in the order they were added, the hosts in the order first seen. */
        public Map<String, List<Placed>> byHost() {
            Map<String, List<Placed>> copy = new LinkedHashMap<>();
            byHost.forEach((host, placed) -> copy.put(host, List.copyOf(placed)));
            return Collections.unmodifiableMap(copy);
        }

        /** Every host the plan asks, in alphabetical order. */
        public List<String> hosts() {
            return byHost.keySet().stream().sorted().toList();
        }

        /** How many tasks the plan holds, across every host. */
        public int size() {
            return byHost.values().stream().mapToInt(List::size).sum();
        }
    }
}
