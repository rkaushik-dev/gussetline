package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.collect.Adapter;
import io.github.rkaushikdev.gussetline.collect.Calendar;
import io.github.rkaushikdev.gussetline.collect.Capability;
import io.github.rkaushikdev.gussetline.collect.Fetch;
import io.github.rkaushikdev.gussetline.collect.Gds;
import io.github.rkaushikdev.gussetline.collect.Harvest;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Ndc;
import io.github.rkaushikdev.gussetline.collect.Pool;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.Rig;
import io.github.rkaushikdev.gussetline.collect.RunPlan;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Series;
import io.github.rkaushikdev.gussetline.collect.Sink;
import io.github.rkaushikdev.gussetline.collect.Supplier;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.persistence.ObservationRepository;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The collect commands, which are what the cron job actually calls.
 *
 * <p>Three of them and the split is on purpose.
 *
 * <p>Planning says what would be polled today and stops. It reads the watches, works out the
 * cadence, and prints the list. It is the command somebody runs when the question is why a market was
 * not looked at, and it costs nothing, so it can be run against production at any time.
 *
 * <p>Running does the same thing and then goes out, across several workers, capped per host so no
 * supplier is asked six times at once. It writes the run to the database, because every reading
 * anybody has about the collectors is built out of those rows and out of nothing else.
 *
 * <p>Replaying does the same thing against the archive instead of the suppliers, and it writes the
 * offers without writing the run. A replay stays on one thread: there is no far end to wait on, so a
 * pool would buy nothing and cost the reproducibility that makes a replay worth having.
 *
 * <p>An offer is a reading, and reparsing a stored payload with a fixed parser produces a better
 * reading of the same moment, which is worth keeping. The observation table is keyed on content, so
 * writing it again is a stamp move rather than a second row.
 *
 * <p>A run is a record that somebody was asked something. Nothing was asked during a replay, and a
 * reliability report that counted them would say a supplier answered on a day it was never called.
 * That is the sort of number that gets believed for a month, so the flag to save one does not exist.
 */
final class CollectCommands {

    /** The reader for each supplier the desk collects from, by name. */
    static final Map<String, Adapter> PARSERS = parsers();

    /** How many task lines to print before saying how many more there were. */
    static final int SHOWN_TASKS = 20;

    private CollectCommands() {}

    /** Poll the suppliers, or work out what polling would do. */
    @Command(
            name = "collect",
            description = "Poll the suppliers, or work out what polling would do.",
            subcommands = {PlanOnly.class, RunIt.class, ReplayIt.class})
    static final class Collect {}

    /** Say what would be polled and stop. */
    @Command(name = "plan", description = "Say what would be polled and stop.")
    static final class PlanOnly implements DeskCommand {

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        @Option(names = "--supplier", paramLabel = "TEXT", description = "Only this one.")
        private String supplier;

        @Option(
                names = "--budget",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "Cap the tasks per supplier.")
        private Integer budget;

        private final Desk desk;

        PlanOnly(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();
            Rig rig = rig(desk);
            Schedule schedule = scheduleFrom(desk, when);
            RunPlan plan = plan(rig, schedule, when, Optional.ofNullable(supplier), Optional.ofNullable(budget), false);

            desk.say(schedule.size() + " series followed, " + schedule.due(when).size() + " due on " + when);
            desk.say(plan.toString());
            show(desk, plan);
            return Desk.OK;
        }
    }

    /** Poll what is due and keep the run. */
    @Command(name = "run", description = "Poll what is due and keep the run.")
    static final class RunIt implements DeskCommand {

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        @Option(names = "--supplier", paramLabel = "TEXT", description = "Only this one.")
        private String supplier;

        @Option(
                names = "--budget",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "Cap the tasks per supplier.")
        private Integer budget;

        @Option(
                names = "--workers",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "How many calls at once.")
        private int workers = Pool.WORKERS;

        private final Desk desk;

        RunIt(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();
            Rig rig = rig(desk);
            Schedule schedule = scheduleFrom(desk, when);
            RunPlan plan = plan(rig, schedule, when, Optional.ofNullable(supplier), Optional.ofNullable(budget), false);

            if (desk.settings().dryRun()) {
                desk.say("dry run, nothing went out");
                show(desk, plan);
                return Desk.OK;
            }

            int identifier;
            try (UnitOfWork work = desk.database().unit()) {
                // Written and committed before a single call goes out. A night run is hours long, and
                // a row written only at the end means a machine that reboots at three leaves no trace
                // that the desk polled anything.
                identifier = work.runs().open(rig.now()).getId();
                work.commit();
            }

            Keeper kept;
            RunReport report;
            try (UnitOfWork work = desk.database().unit();
                    Rig.LiveFetch fetch = desk.wiring().liveFetch().apply(rig)) {
                kept = new Keeper(work);
                report = desk.wiring()
                        .poolRun()
                        .run(
                                new Pool(workers, Pool.PER_HOST),
                                rig.collector(),
                                plan.tasks(),
                                when,
                                fetch,
                                rig.parsers(),
                                schedule,
                                kept);
                work.runs().close(work.runs().row(identifier), report, rig.now());
                work.commit();
            }

            report(desk, report, rig);
            Map<String, Object> ran = new LinkedHashMap<>();
            ran.put("on", when);
            ran.put("tasks", report.results().size());
            ran.put("calls", report.calls());
            ran.put("offers", report.offers());
            ran.put("kept", kept.written);
            ran.put("seen_before", kept.moved);
            ran.put("cost", report.cost(rig.registry()));
            ran.put("run", identifier);
            Logs.event("collect.ran", ran);
            for (Result failure : report.failures()) {
                Logs.problem(
                        "collect.failed",
                        Details.of(
                                "supplier", failure.task().supplier(),
                                "market", failure.task().market().key(),
                                "outcome", failure.outcome().value(),
                                "why", failure.reason()));
            }
            desk.say("kept " + kept.written + " offers, " + kept.moved + " already seen");
            desk.say("kept as run " + identifier);
            return Desk.OK;
        }
    }

    /** Reparse what is already in the archive. Never saved, never a run. */
    @Command(name = "replay", description = "Reparse what is already in the archive. Never saved, never a run.")
    static final class ReplayIt implements DeskCommand {

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        @Option(names = "--supplier", paramLabel = "TEXT", description = "Only this one.")
        private String supplier;

        private final Desk desk;

        ReplayIt(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();
            Rig rig = rig(desk);
            Schedule schedule = scheduleFrom(desk, when);
            Optional<String> only = Optional.ofNullable(supplier);
            RunPlan plan = plan(rig, schedule, when, only, Optional.empty(), true);

            Keeper kept;
            RunReport report;
            try (UnitOfWork work = desk.database().unit()) {
                kept = new Keeper(work);
                Fetch fetch = only.map(rig::replayFetch).orElseGet(rig::replayFetch);
                report = rig.collector().run(plan.tasks(), when, fetch, rig.parsers(), kept);
                work.commit();
            }

            report(desk, report, rig);
            desk.say("kept " + kept.written + " offers, " + kept.moved + " already seen");
            desk.say("no run kept, because nothing was asked of anybody");
            return Desk.OK;
        }
    }

    private static Map<String, Adapter> parsers() {
        Map<String, Adapter> parsers = new LinkedHashMap<>();
        parsers.put("gds", Gds::parse);
        parsers.put("ndc", Ndc::parse);
        parsers.put("calendar", Calendar::parse);
        return Collections.unmodifiableMap(parsers);
    }

    private static Rig rig(Desk desk) {
        return Rig.builder(desk.settings()).parsers(PARSERS).build();
    }

    /**
     * Build the schedule out of the live watches.
     *
     * <p>The last check is carried over so the cadence picks up where it left off. Without it every
     * run after a restart thinks nothing has ever been polled and asks for the whole book on the first
     * morning.
     */
    private static Schedule scheduleFrom(Desk desk, LocalDate today) {
        Schedule schedule = new Schedule();
        try (UnitOfWork work = desk.database().unit()) {
            for (WatchRow row : work.watches().live(today)) {
                Series series = schedule.follow(new Market(row.getOrigin(), row.getDestination()), row.getDeparture());
                row.getLastChecked().ifPresent(checked -> series.setLastPolled(checked.toLocalDate()));
            }
        }
        return schedule;
    }

    /**
     * One plan across every supplier that can shop, closest departures first.
     *
     * <p>A market both the feed and the gateway cover is planned once, against whichever is cheaper,
     * rather than twice. Two suppliers answering the same question is how the gateway bill doubled the
     * month the feed was added.
     */
    private static RunPlan plan(
            Rig rig,
            Schedule schedule,
            LocalDate today,
            Optional<String> supplier,
            Optional<Integer> budget,
            boolean everything) {
        RunPlan plan = new RunPlan();
        List<Series> wanted = everything ? schedule.series() : schedule.due(today);
        int cap = budget.orElse(wanted.size());

        List<Series> closestFirst = wanted.stream()
                .sorted(Comparator.comparingInt((Series series) -> series.daysOut(today))
                        .thenComparing(series -> series.market().key()))
                .toList();
        for (Series series : closestFirst) {
            if (plan.size() >= cap) {
                break;
            }
            Optional<String> chosen = supplierFor(rig, series.market().key(), supplier);
            if (chosen.isEmpty()) {
                continue;
            }
            plan.add(new Task(chosen.get(), series.market(), series.departure(), series.daysOut(today), plan.size()));
        }
        return plan;
    }

    private static Optional<String> supplierFor(Rig rig, String market, Optional<String> only) {
        if (only.isPresent()) {
            Supplier found = rig.registry().get(only.get());
            return found.covers(market) ? Optional.of(found.name()) : Optional.empty();
        }
        return rig.registry().cheapestFor(market, Capability.SHOP).map(Supplier::name);
    }

    private static void show(Desk desk, RunPlan plan) {
        plan.tasks().stream().limit(SHOWN_TASKS).forEach(task -> desk.say("  " + task));
        if (plan.size() > SHOWN_TASKS) {
            desk.say("  and " + (plan.size() - SHOWN_TASKS) + " more");
        }
    }

    private static void report(Desk desk, RunReport report, Rig rig) {
        desk.say(report.toString());
        new TreeMap<>(report.byOutcome())
                .forEach((outcome, count) -> desk.say(String.format(Locale.ROOT, "  %-10s %d", outcome, count)));
        if (report.problems() > 0) {
            desk.warn(report.problems() + " offers inside the payloads did not parse");
        }
        report.failures().stream().limit(SHOWN_TASKS).forEach(failure -> desk.warn("  " + failure));
        desk.say("cost " + report.cost(rig.registry()));
    }

    /**
     * Writes the offers a run parsed, and counts what it did.
     *
     * <p>One instance per run, so the counts belong to that run and not to the process. The collector
     * hands it each task's harvest one at a time, from whichever worker answered.
     */
    private static final class Keeper implements Sink {

        private final UnitOfWork work;
        private int written;
        private int moved;

        Keeper(UnitOfWork work) {
            this.work = work;
        }

        @Override
        public void keep(Task task, Harvest harvest) {
            if (harvest.offers().isEmpty()) {
                return;
            }
            ObservationRepository.Saved saved = work.observations().saveAll(List.copyOf(harvest.offers()));
            written += saved.fresh();
            moved += saved.repeats();
        }
    }
}
