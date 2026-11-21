package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Registry;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Series;
import io.github.rkaushikdev.gussetline.desk.Place;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.Worklist;
import io.github.rkaushikdev.gussetline.persistence.Book;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.reports.Coverage;
import io.github.rkaushikdev.gussetline.reports.Filing;
import io.github.rkaushikdev.gussetline.reports.Reliability;
import io.github.rkaushikdev.gussetline.reports.Savings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The report commands, which are the three numbers the desk gets asked for, and the two lists that
 * go with them.
 *
 * <p>Savings is what the desk is measured on. Reliability is what the operator gets paged about.
 * Coverage is the one nobody asks for and everybody needs, because it is the only report that catches
 * a market quietly falling off the schedule.
 *
 * <p>All of them read history and write nothing, so they are safe to run against production in the
 * middle of a night run, and none of them takes a window longer than it needs: the whole point of a
 * report over a month is that it is comparable to the one from last month, and a default window that
 * grows with the age of the database is not.
 *
 * <p>Savings reads recommendations rather than runs, which is the distinction that took two arguments
 * to settle. A run says the desk found a hundred euro drop. A recommendation says the desk told
 * somebody about it. The first is a reading of the collectors and the second is a reading of the desk,
 * and reporting the first as savings makes a quiet month look like a good one.
 *
 * <p>A report that finds something needing a person exits {@link Desk#OPERATOR_CAN_FIX}, so a cron job
 * can mail it.
 */
final class ReportCommands {

    /**
     * How far back the savings report looks when nobody says, in days. Different from the others on
     * purpose: a month of savings is a management number.
     */
    static final int SAVINGS_DAYS = 30;

    /** How far back the reliability report looks when nobody says, in days: enough to see a supplier going bad. */
    static final int RELIABILITY_DAYS = 14;

    /** How many runs that never finished the reliability report names one by one. */
    static final int SHOWN_STALLED = 5;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    private ReportCommands() {}

    /** Read history. None of these writes anything. */
    @Command(
            name = "report",
            description = "Read history. None of these writes anything.",
            subcommands = {
                ShowCoverage.class,
                ShowFiling.class,
                ShowQueue.class,
                ShowReliability.class,
                ShowSavings.class
            })
    static final class Report {}

    /** What the desk found, took and missed. */
    @Command(name = "savings", description = "What the desk found, took and missed.")
    static final class ShowSavings implements DeskCommand {

        @Option(names = "--since", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate since;

        @Option(names = "--until", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate until;

        private final Desk desk;

        ShowSavings(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate last = until != null ? until : LocalDate.now();
            LocalDate first = since != null ? since : last.minusDays(SAVINGS_DAYS);

            List<Recommendation> made;
            try (UnitOfWork work = desk.database().unit()) {
                made = Book.raisedBetween(work, first, last);
            }

            Savings built =
                    Savings.build(made, desk.settings().sellingCurrency(), Optional.of(first), Optional.of(last));
            desk.echo(Savings.render(built));
            return Desk.OK;
        }
    }

    /** Which suppliers are answering. */
    @Command(name = "reliability", description = "Which suppliers are answering.")
    static final class ShowReliability implements DeskCommand {

        @Option(names = "--since", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate since;

        @Option(
                names = "--runs",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "Read this many runs instead.")
        private Integer runs;

        private final Desk desk;

        ShowReliability(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            List<RunReport> history;
            List<LocalDateTime> stalled;
            try (UnitOfWork work = desk.database().unit()) {
                if (runs != null) {
                    history = work.runs().recent(runs);
                } else {
                    LocalDateTime first = since != null
                            ? since.atStartOfDay()
                            : LocalDateTime.now().minusDays(RELIABILITY_DAYS);
                    history = work.runs().since(first);
                }
                stalled = work.runs().unfinished().stream()
                        .map(RunRow::getStartedAt)
                        .toList();
            }

            Reliability built = Reliability.build(history);
            desk.echo(Reliability.render(built));

            // A run that started and never said it was done took its offers with it. Nothing else here
            // can see that: every supplier in the report above is graded on the calls that did come back.
            for (LocalDateTime started : stalled.stream().limit(SHOWN_STALLED).toList()) {
                desk.warn("run of " + MINUTE.format(started) + " never finished");
            }
            if (!stalled.isEmpty()) {
                desk.warn(stalled.size() + " runs started and never finished");
            }

            return !built.needingAHuman().isEmpty() || !stalled.isEmpty() ? Desk.OPERATOR_CAN_FIX : Desk.OK;
        }
    }

    /** What the desk is actually watching, against what it meant to. */
    @Command(name = "coverage", description = "What the desk is actually watching, against what it meant to.")
    static final class ShowCoverage implements DeskCommand {

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        @Option(
                names = "--runs",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "How many runs to read.")
        private int runs = 60;

        @Option(
                names = "--show",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "How many gaps to list.")
        private int show = 20;

        private final Desk desk;

        ShowCoverage(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();

            Schedule schedule;
            List<RunReport> history;
            try (UnitOfWork work = desk.database().unit()) {
                schedule = schedule(work);
                history = work.runs().recent(runs);
            }

            Coverage built = Coverage.build(schedule, history, Registry.defaultRegistry(), when);
            desk.echo(Coverage.render(built, show));
            return built.gaps().isEmpty() ? Desk.OK : Desk.OPERATOR_CAN_FIX;
        }
    }

    /** What moved between a carrier's last two filings. */
    @Command(
            name = "filing",
            description = {
                "What moved between a carrier's last two filings.",
                "",
                "Exits one when something needs doing before the desk opens, which is a fare that fell or a fare"
                        + " that stopped being filed. A tariff that only grew is not news and should not page"
                        + " anybody."
            })
    static final class ShowFiling implements DeskCommand {

        @Parameters(paramLabel = "CARRIER")
        private String carrier;

        @Option(
                names = "--against",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "Read against this load id.")
        private Integer against;

        @Option(
                names = "--show",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "How many of each list to print.")
        private int show = 15;

        private final Desk desk;

        ShowFiling(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            Filing built;
            try (UnitOfWork work = desk.database().unit()) {
                List<LoadRow> loads = work.tariff().loadsFor(carrier);
                if (loads.size() < 2) {
                    throw new NotFoundError(
                            "a carrier needs two filings before they can be compared",
                            Details.of("carrier", carrier.toUpperCase(Locale.ROOT), "filings", loads.size()));
                }
                LoadRow after = loads.getFirst();
                LoadRow before = pick(loads, Optional.ofNullable(against));
                desk.say(before.getFilename() + " to " + after.getFilename());
                built = Filing.build(
                        work.tariff().faresIn(before),
                        work.tariff().faresIn(after),
                        desk.settings().sellingCurrency());
            }

            desk.echo(Filing.render(built, show));
            return built.worthALook() ? Desk.OPERATOR_CAN_FIX : Desk.OK;
        }
    }

    /** What an agent should work through, in the order to work it. */
    @Command(
            name = "queue",
            description = {
                "What an agent should work through, in the order to work it.",
                "",
                "The same list the worklist screen shows, for the mornings when nobody wants to open a browser."
            })
    static final class ShowQueue implements DeskCommand {

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        @Option(
                names = "--show",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "How many places to print.")
        private int show = 20;

        @Option(names = "--urgent", description = "Only the ones for today.")
        private boolean onlyUrgent;

        private final Desk desk;

        ShowQueue(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();

            Book book;
            try (UnitOfWork work = desk.database().unit()) {
                book = Book.openBook(work);
            }

            List<Recommendation> made = book.recommendations();
            List<Place> places = Worklist.build(made, when, book.departures());
            if (onlyUrgent) {
                places = Worklist.urgent(places);
            }

            desk.echo(Worklist.render(places, show));
            if (!places.isEmpty()) {
                String currency = made.getFirst().currency();
                desk.echo("worth " + Worklist.worthOf(places, currency));
            }
            return Desk.OK;
        }
    }

    /**
     * Which filing to read the newest one against.
     *
     * <p>The one before it by default, because that is the question somebody has on the morning a file
     * lands. A load id when somebody is reading a week of filings back to one they trust.
     *
     * @throws NotFoundError naming the {@code load} when the carrier has no filing under that id
     */
    private static LoadRow pick(List<LoadRow> loads, Optional<Integer> against) {
        if (against.isEmpty()) {
            return loads.get(1);
        }
        for (LoadRow row : loads) {
            if (row.getId().equals(against.get())) {
                return row;
            }
        }
        throw new NotFoundError("no such filing for that carrier", Details.of("load", against.get()));
    }

    /**
     * The schedule as the live watches describe it, in the order they were added.
     *
     * <p>Retired watches are left out. A retired watch is not a gap, and counting one makes the coverage
     * figure fall every time the desk correctly stops watching something.
     */
    private static Schedule schedule(UnitOfWork work) {
        Schedule schedule = new Schedule();
        List<WatchRow> rows = work.session()
                .createQuery("select w from WatchRow w where w.active = true order by w.id", WatchRow.class)
                .getResultList();
        for (WatchRow row : rows) {
            Series series = schedule.follow(new Market(row.getOrigin(), row.getDestination()), row.getDeparture());
            row.getLastChecked().ifPresent(checked -> series.setLastPolled(checked.toLocalDate()));
        }
        return schedule;
    }
}
