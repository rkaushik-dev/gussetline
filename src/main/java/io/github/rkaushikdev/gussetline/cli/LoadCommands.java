package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.collect.Problem;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.load.CombinabilityApplied;
import io.github.rkaushikdev.gussetline.load.CombinabilityFeed;
import io.github.rkaushikdev.gussetline.load.CombinabilityLoad;
import io.github.rkaushikdev.gussetline.load.FareFeed;
import io.github.rkaushikdev.gussetline.load.FareLoad;
import io.github.rkaushikdev.gussetline.load.Filing;
import io.github.rkaushikdev.gussetline.load.RuleFeed;
import io.github.rkaushikdev.gussetline.load.RuleLoad;
import io.github.rkaushikdev.gussetline.persistence.RuleRepository;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The load commands, which are the ones somebody runs at four in the morning.
 *
 * <p>Two things they do that a plain read and write would not.
 *
 * <p>A fare file already taken in is refused. Tariff files arrive by transfer and the transfer
 * retries, so the same bytes turn up twice more often than not, and loading them twice doubles a
 * market's fares and makes the cheapest one win a selection it should not. The digest of the payload
 * is the check and it is a column on the load, not a file on disk, because the check has to survive a
 * machine being rebuilt.
 *
 * <p>And a file can be read without being applied. A dry run reads the whole file, counts everything,
 * lists every bad record, and touches nothing. That is what runs against a new carrier's first file,
 * and it is the difference between finding out the offsets are wrong now and finding out when the
 * desk quotes a fare that does not exist.
 *
 * <p>A file that parses with bad records in it exits {@link Desk#OPERATOR_CAN_FIX}, because the
 * operator can decide whether to take the rest. A clean load exits {@link Desk#OK}.
 */
final class LoadCommands {

    /** How many bad records to print before saying how many more there were. */
    static final int SHOWN_PROBLEMS = 10;

    private LoadCommands() {}

    /** Take a filing file in. */
    @Command(
            name = "load",
            description = "Take a filing file in.",
            subcommands = {LoadFares.class, LoadRules.class, LoadCombinability.class})
    static final class Load {}

    /** Read a fare filing file into the tariff. */
    @Command(name = "fares", description = "Read a fare filing file into the tariff.")
    static final class LoadFares implements DeskCommand {

        @Parameters(paramLabel = "PATH", converter = Values.ExistingFile.class)
        private Path path;

        @Option(names = "--dry-run", description = "Read it and say what would happen.")
        private boolean dryRun;

        @Option(names = "--again", description = "Load it even though we have these bytes.")
        private boolean again;

        private final Desk desk;

        LoadFares(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            byte[] payload = read(path);
            String digest = Archive.digestOf(payload);
            FareLoad reading = FareFeed.read(payload);

            desk.say(reading.toString());
            problems(desk, reading.problems());
            if (!reading.counted()) {
                desk.warn("the trailer says " + reading.declared().orElseThrow() + " records and we read "
                        + reading.size());
            }

            if (dryRun) {
                desk.say("would be " + FareFeed.apply(new TariffStore(), reading));
                return finish(reading.problems(), true);
            }

            if (!again && alreadyLoaded(desk, digest)) {
                throw new CommandError("these bytes are already loaded, digest " + digest.substring(0, 12));
            }

            int written;
            try (UnitOfWork work = desk.database().unit()) {
                LoadRow row = work.tariff()
                        .openLoad(
                                reading.carrier().isEmpty() ? "??" : reading.carrier(),
                                path.getFileName().toString(),
                                digest,
                                LocalDateTime.now(),
                                reading.filedOn().orElseGet(LocalDate::now));
                written = work.tariff()
                        .addFares(
                                row,
                                reading.filings().stream().map(Filing::fare).toList());
                work.commit();
            }

            desk.say("loaded " + written + " fares as load " + digest.substring(0, 12));
            return finish(reading.problems(), false);
        }
    }

    /** Read a rule file into the tariff. */
    @Command(name = "rules", description = "Read a rule file into the tariff.")
    static final class LoadRules implements DeskCommand {

        @Parameters(paramLabel = "PATH", converter = Values.ExistingFile.class)
        private Path path;

        @Option(names = "--dry-run", description = "Read it and say what would happen.")
        private boolean dryRun;

        private final Desk desk;

        LoadRules(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            RuleLoad reading = RuleFeed.read(read(path));

            desk.say(reading.toString());
            if (reading.orphans() > 0) {
                desk.warn(reading.orphans() + " category records had no rule above them");
            }
            problems(desk, reading.problems());

            Map<Integer, Integer> counts = new TreeMap<>(RuleFeed.categoriesOf(reading));
            counts.forEach(
                    (category, count) -> desk.say(String.format(Locale.ROOT, "  category %2d  %d", category, count)));

            desk.say((dryRun ? "would be" : "applied") + ": " + RuleFeed.apply(new RuleStore(), reading));

            if (!dryRun) {
                RuleRepository.Filed written;
                int gone;
                try (UnitOfWork work = desk.database().unit()) {
                    written = work.rules().saveAll(reading.rules());
                    gone = 0;
                    for (RuleLoad.Withdrawn withdrawn : reading.deleted()) {
                        if (work.rules().delete(withdrawn.carrier(), withdrawn.number(), withdrawn.tariff())) {
                            gone++;
                        }
                    }
                    work.commit();
                }
                desk.say("kept " + written.rules() + " rules, dropped " + gone);
                if (written.dropped() > 0) {
                    // A rule stored without one of its categories permits something the carrier
                    // restricted, so this is a warning and not a note.
                    desk.warn(written.dropped() + " provisions have no codec and were not stored");
                }
            }

            return finish(reading.problems(), dryRun);
        }
    }

    /** Read a category 10 file into the tariff. */
    @Command(name = "combinability", description = "Read a category 10 file into the tariff.")
    static final class LoadCombinability implements DeskCommand {

        @Parameters(paramLabel = "PATH", converter = Values.ExistingFile.class)
        private Path path;

        @Option(names = "--dry-run", description = "Read it and say what would happen.")
        private boolean dryRun;

        private final Desk desk;

        LoadCombinability(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            CombinabilityLoad reading = CombinabilityFeed.read(read(path));

            desk.say(reading.toString());
            problems(desk, reading.problems());

            CombinabilityApplied applied = CombinabilityFeed.apply(new CombinabilityStore(), reading);
            desk.say((dryRun ? "would be" : "applied") + ": " + applied);
            if (applied.combineWithNothing() > 0) {
                desk.warn(applied.combineWithNothing()
                        + " fares combine with nothing, which refuses every round trip they are in");
            }

            if (!dryRun) {
                int written;
                int dropped;
                try (UnitOfWork work = desk.database().unit()) {
                    written = work.combinability().saveAll(reading.records());
                    dropped = 0;
                    for (CombinabilityLoad.Withdrawn withdrawn : reading.deleted()) {
                        if (work.combinability().delete(withdrawn.carrier(), withdrawn.fareBasis())) {
                            dropped++;
                        }
                    }
                    work.commit();
                }
                desk.say("kept " + written + " records, dropped " + dropped);
            }

            return finish(reading.problems(), dryRun);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static boolean alreadyLoaded(Desk desk, String digest) {
        try (UnitOfWork work = desk.database().unit()) {
            return !work.session()
                    .createQuery("select l from LoadRow l where l.digest = :digest", LoadRow.class)
                    .setParameter("digest", digest)
                    .setMaxResults(1)
                    .getResultList()
                    .isEmpty();
        }
    }

    /** Print the bad records, capped, and say how many were not shown. */
    private static void problems(Desk desk, List<Problem> problems) {
        problems.stream().limit(SHOWN_PROBLEMS).forEach(problem -> desk.warn(problem.toString()));
        if (problems.size() > SHOWN_PROBLEMS) {
            desk.warn("and " + (problems.size() - SHOWN_PROBLEMS) + " more");
        }
    }

    /** A file with bad records in it is something the operator decides about. */
    private static int finish(List<Problem> problems, boolean dryRun) {
        return !problems.isEmpty() && !dryRun ? Desk.OPERATOR_CAN_FIX : Desk.OK;
    }
}
