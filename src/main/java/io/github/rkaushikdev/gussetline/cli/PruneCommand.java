package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.retention.Kept;
import io.github.rkaushikdev.gussetline.retention.Policy;
import io.github.rkaushikdev.gussetline.retention.Retention;
import io.github.rkaushikdev.gussetline.retention.Swept;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Throwing away what nobody will read again, and nothing else.
 *
 * <p>A year of collecting fills a disk. The rows that fill it are the ones nobody will ever ask about,
 * and the rows that matter are small, so this is a job with a lot of upside and one very expensive way
 * to be wrong.
 *
 * <p>It never touches a recommendation. There is no flag for it and no cutoff that reaches one,
 * because the savings report reads them and a report that changes when old rows are deleted cannot be
 * put in front of anybody.
 *
 * <p>It is dry by default. A retention job that deletes on its first run is one somebody points at
 * production to see what it would do, and then it has done it. Passing the flag is the whole ceremony
 * and it is enough.
 */
@Command(name = "prune", description = "Drop what has aged out. Says what it would do unless told to do it.")
final class PruneCommand implements DeskCommand {

    /** How many payloads that do not read back a dry scrub names one by one. */
    static final int SHOWN_BAD = 10;

    @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate today;

    @Option(
            names = "--observation-days",
            paramLabel = "INTEGER",
            converter = Values.WholeNumber.class,
            description = "Keep readings this long.")
    private Integer observationDays;

    @Option(
            names = "--run-days",
            paramLabel = "INTEGER",
            converter = Values.WholeNumber.class,
            description = "Keep runs this long.")
    private Integer runDays;

    @Option(
            names = "--archive-days",
            paramLabel = "INTEGER",
            converter = Values.WholeNumber.class,
            description = "Keep payloads this long.")
    private Integer archiveDays;

    @Option(names = "--apply", description = "Actually delete. Off by default.")
    private boolean applying;

    @Option(names = "--scrub", description = "Also check every stored payload.")
    private boolean scrubbing;

    private final Desk desk;

    PruneCommand(Desk desk) {
        this.desk = desk;
    }

    @Override
    public Integer call() {
        LocalDate when = today != null ? today : LocalDate.now();
        Policy policy = policy();
        desk.say("keeping " + policy);

        Swept swept = sweep(policy, when);
        desk.say(swept.toString());
        Logs.event(
                "prune.swept",
                Details.of(
                        "on", when,
                        "observations", swept.observations(),
                        "runs", swept.runs(),
                        "payloads", swept.blobs(),
                        "applied", applying));

        if (scrubbing) {
            scrub();
        }

        if (!applying) {
            desk.say("nothing was deleted, pass --apply to do it");
        }
        return Desk.OK;
    }

    /**
     * Read every payload back and say which ones are not what they claim.
     *
     * <p>Not part of the ordinary sweep. It reads and decompresses the whole store, which on a year of
     * collecting is minutes rather than seconds, and the answer is almost always none.
     *
     * <p>A corrupt blob is repaired by deleting it. That sounds backwards and is not: the digest goes
     * back to being one the desk does not hold, and the next poll of that market fetches the payload
     * again.
     */
    private void scrub() {
        Archive archive = new Archive(desk.settings().archiveRoot());
        if (!applying) {
            List<String> bad = archive.verify();
            desk.say(bad.size() + " payloads do not read back");
            for (String digest : bad.stream().limit(SHOWN_BAD).toList()) {
                desk.warn("  " + digest);
            }
            return;
        }

        Archive.Scrubbed scrubbed = archive.scrub();
        desk.say("removed " + scrubbed.corrupt() + " unreadable payloads and " + scrubbed.leftovers()
                + " unfinished writes");
        Logs.event("prune.scrubbed", Details.of("corrupt", scrubbed.corrupt(), "leftovers", scrubbed.leftovers()));
    }

    /** The shipped policy, with whatever the command line named in place of its numbers. Nought names nothing. */
    private Policy policy() {
        Policy base = Retention.defaultPolicy();
        return new Policy(
                chosen(observationDays, base.observationDays()),
                chosen(runDays, base.runDays()),
                chosen(archiveDays, base.archiveDays()));
    }

    private static int chosen(Integer named, int otherwise) {
        return named == null || named == 0 ? otherwise : named;
    }

    /**
     * Count what would go, and delete it only when told to.
     *
     * <p>The counting and the deleting are the same code path with one rollback between them. Two paths
     * would mean the dry run reports on something the real one does not do, which is the one thing a dry
     * run must never do.
     */
    private Swept sweep(Policy policy, LocalDate when) {
        int observations;
        int runs;
        try (UnitOfWork work = desk.database().unit()) {
            observations = work.observations().prune(policy.observationsBefore(when));
            runs = work.runs().prune(policy.runsBefore(when));
            if (applying) {
                work.commit();
            }
        }

        Archive archive = new Archive(desk.settings().archiveRoot());
        int rows;
        int blobs;
        int kept;
        if (applying) {
            Archive.Pruned pruned = archive.prune(policy.archiveBefore(when));
            rows = pruned.rows();
            blobs = pruned.blobs();
            kept = pruned.kept();
        } else {
            List<Map<String, Object>> manifest = archive.manifest();
            Kept<Map<String, Object>> surviving = Retention.keepable(manifest, policy.archiveBefore(when));
            Set<String> unnamed = new LinkedHashSet<>();
            for (Map<String, Object> row : manifest) {
                unnamed.add(digestOf(row));
            }
            unnamed.removeAll(surviving.named());
            rows = manifest.size() - surviving.rows().size();
            blobs = unnamed.size();
            kept = surviving.named().size();
        }
        return new Swept(observations, runs, rows, blobs, kept);
    }

    /**
     * The payload a manifest row names.
     *
     * @throws NoSuchElementException when the row names none
     */
    private static String digestOf(Map<String, Object> row) {
        if (!row.containsKey("digest")) {
            throw new NoSuchElementException("a manifest row without a digest: " + row);
        }
        return Text.plain(row.get("digest"));
    }
}
