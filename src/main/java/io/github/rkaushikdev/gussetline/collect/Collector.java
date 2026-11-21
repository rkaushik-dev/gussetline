package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.GussetlineError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One collection run, which is where all the parts meet: it runs a plan against the suppliers,
 * politely and once.
 *
 * <p>The order of the checks matters and it is cheapest first. The breaker is a field read, the
 * budget is arithmetic, and the call is a second of network, so they go in that order and a dead
 * supplier costs nothing to skip. Getting that backwards is how a run against an outage still takes
 * forty minutes.
 *
 * <p>Everything that happens is recorded in the {@link RunReport}, including the nothings.
 *
 * <p>Every payload is archived before it is read, with the market and the departure it answered
 * beside it in the manifest. A failure to get an answer, or an answer that is not a success, counts
 * against the supplier's breaker. A payload the reader could not cope with does not: the supplier
 * answered, and tripping the breaker on our own reader would stop a working supplier.
 *
 * <p>The fetch is passed in ({@link Fetch}), and so is what happens to the offers ({@link Sink}),
 * which is optional because a plan or a dry run genuinely wants the counting and not the writing.
 *
 * <p>A collector works through a plan on the thread that asks it to.
 */
public final class Collector {

    /**
     * What a task changes that the other tasks of a run also read: the breakers, and what is kept of
     * an answer. A run on one thread changes them directly; the pool changes them under its locks.
     */
    interface Shared {

        void failed(String supplier);

        void succeeded(String supplier);

        /** Let the schedule learn the price, when there is one, then hand the harvest to the sink. */
        void keep(Task task, LocalDate today, Harvest harvest, Schedule schedule, Sink sink);
    }

    private final Registry registry;
    private final Limiter limiter;
    private final BreakerBoard breakers;
    private final Archive archive;
    private final java.util.function.Supplier<LocalDateTime> clock;
    private final Shared direct = new Direct();

    /**
     * A collector over these parts.
     *
     * @param now what the collector calls the current moment, which every result and sighting carries
     */
    public Collector(
            Registry registry,
            Limiter limiter,
            BreakerBoard breakers,
            Archive archive,
            java.util.function.Supplier<LocalDateTime> now) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.breakers = Objects.requireNonNull(breakers, "breakers");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.clock = Objects.requireNonNull(now, "now");
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

    /**
     * What this collector calls the current moment. The pool asks it too, because a run whose start
     * and whose results disagree about the clock cannot be lined up against the archive.
     */
    public LocalDateTime now() {
        return clock.get();
    }

    /** Work through a plan with no schedule to teach and nowhere to keep the offers. */
    public RunReport run(List<Task> tasks, LocalDate today, Fetch fetch, Map<String, Adapter> parsers) {
        return work(tasks, today, fetch, parsers, null, null);
    }

    /** Work through a plan, teaching the schedule the cheapest price each answer gave. */
    public RunReport run(
            List<Task> tasks, LocalDate today, Fetch fetch, Map<String, Adapter> parsers, Schedule schedule) {
        return work(tasks, today, fetch, parsers, Objects.requireNonNull(schedule, "schedule"), null);
    }

    /** Work through a plan, handing every harvest to the sink. */
    public RunReport run(List<Task> tasks, LocalDate today, Fetch fetch, Map<String, Adapter> parsers, Sink sink) {
        return work(tasks, today, fetch, parsers, null, Objects.requireNonNull(sink, "sink"));
    }

    /**
     * Work through a plan, recording what every task did.
     *
     * <p>A task whose supplier's breaker is open is recorded as blocked and one whose host has no
     * budget left as budget, each with its reason, and neither reaches the supplier.
     *
     * @param tasks the plan, in the order to work it
     * @param today the day the quotas are counted against and the schedule records
     * @param parsers the reader for each supplier, by name; a supplier with none is refused
     * @param schedule the schedule to teach the cheapest price each answer gave
     * @param sink where every harvest goes
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a task names a supplier the
     *     registry does not hold
     */
    public RunReport run(
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule,
            Sink sink) {
        return work(
                tasks,
                today,
                fetch,
                parsers,
                Objects.requireNonNull(schedule, "schedule"),
                Objects.requireNonNull(sink, "sink"));
    }

    private RunReport work(
            List<Task> tasks,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule,
            Sink sink) {
        RunReport report = new RunReport(now());

        for (Task task : tasks) {
            Supplier supplier = registry.get(task.supplier());

            if (!breakers.allow(supplier.name())) {
                report.skipOnBreaker(new Result(task, Outcome.BLOCKED, "breaker is open"));
                continue;
            }

            if (!limiter.allow(supplier.host(), today)) {
                HostBudget budget = limiter.budgetFor(supplier.host());
                report.skipOnBudget(new Result(task, Outcome.BUDGET, budget.lastReason()));
                continue;
            }

            report.add(runOne(supplier, task, today, fetch, parsers, schedule, sink, direct));
        }

        return report;
    }

    /**
     * One task, start to finish, once its breaker and budget have let it through.
     *
     * @param schedule the schedule to teach, or null for none
     * @param sink where the harvest goes, or null for nowhere
     * @param shared how to change what other tasks also read
     */
    Result runOne(
            Supplier supplier,
            Task task,
            LocalDate today,
            Fetch fetch,
            Map<String, Adapter> parsers,
            Schedule schedule,
            Sink sink,
            Shared shared) {
        LocalDateTime seen = now();
        Response response;
        try {
            response = fetch.fetch(supplier, task);
        } catch (GussetlineError failure) {
            shared.failed(supplier.name());
            return Result.builder(task, Registry.classify(failure))
                    .reason(failure.message())
                    .attempts(1)
                    .build();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("market", task.market().key());
        context.put("departure", task.departure().toString());
        Sighting sighting = archive.put(
                response.body(),
                supplier.name(),
                response.url().isEmpty() ? supplier.path() : response.url(),
                seen,
                response.status(),
                context);

        if (!response.ok()) {
            shared.failed(supplier.name());
            return answered(task, Outcome.TRANSIENT, sighting, response)
                    .reason("status " + response.status())
                    .build();
        }

        Adapter parser = parsers.get(supplier.name());
        if (parser == null) {
            shared.succeeded(supplier.name());
            return answered(task, Outcome.REFUSED, sighting, response)
                    .reason("no parser registered")
                    .build();
        }

        Harvest harvest;
        try {
            harvest = parser.parse(response.body(), seen);
        } catch (GussetlineError unreadable) {
            shared.succeeded(supplier.name());
            return answered(task, Registry.classify(unreadable), sighting, response)
                    .reason(unreadable.message())
                    .build();
        }

        shared.succeeded(supplier.name());
        shared.keep(task, today, harvest, schedule, sink);

        return answered(task, Outcome.OK, sighting, response)
                .offers(harvest.size())
                .problems(harvest.problems().size())
                .build();
    }

    /** Let the schedule learn the price, when there is one, then hand the harvest to the sink. */
    static void keep(Task task, LocalDate today, Harvest harvest, Schedule schedule, Sink sink) {
        if (schedule != null) {
            schedule.get(task.market(), task.departure())
                    .ifPresent(series -> series.record(today, harvest.cheapestAmount()));
        }
        if (sink != null) {
            sink.keep(task, harvest);
        }
    }

    private static Result.Builder answered(Task task, Outcome outcome, Sighting sighting, Response response) {
        return Result.builder(task, outcome)
                .digest(sighting.digest())
                .fresh(sighting.fresh())
                .attempts(response.attempts());
    }

    /** The breakers and the keeping as they stand, for a run on one thread. */
    private final class Direct implements Shared {

        @Override
        public void failed(String supplier) {
            breakers.failed(supplier);
        }

        @Override
        public void succeeded(String supplier) {
            breakers.succeeded(supplier);
        }

        @Override
        public void keep(Task task, LocalDate today, Harvest harvest, Schedule schedule, Sink sink) {
            Collector.keep(task, today, harvest, schedule, sink);
        }
    }
}
