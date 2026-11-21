package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.collect.Sighting;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The retention command, which is the one with an expensive way to be wrong.
 *
 * <p>Everything else here can be run again. This deletes, so the tests that matter are the ones about
 * what it does not touch and about the dry run reporting the same numbers the real run acts on.
 */
class CliPruneTest {

    private static final String TODAY = "2025-11-03";
    private static final LocalDateTime OLD = LocalDateTime.of(2024, 1, 15, 6, 0);
    private static final LocalDateTime RECENT = LocalDateTime.of(2025, 11, 1, 6, 0);
    private static final LocalDate GONE = LocalDate.of(2024, 2, 1);
    private static final LocalDate AHEAD = LocalDate.of(2026, 3, 1);
    private static final String URL = "/search";
    private static final DateTimeFormatter TO_THE_MINUTE = DateTimeFormatter.ofPattern("uuuuMMddHHmm", Locale.ROOT);

    @TempDir
    Path tmp;

    private Path raw;
    private String env;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        raw = tmp.resolve("raw");
        env = Shell.fileUrl(tmp);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", raw.toString());
        Shell.createSchema(env);
    }

    private static void anObservation(UnitOfWork work, LocalDateTime seen, LocalDate departure) {
        anObservation(work, seen, departure, "FRA-NYC");
    }

    /**
     * One reading. What decides whether it is pruned is the departure.
     *
     * <p>Not when it was written. A row written in March about a December departure is still worth
     * reading in November.
     */
    private static void anObservation(UnitOfWork work, LocalDateTime seen, LocalDate departure, String market) {
        String fingerprint = TO_THE_MINUTE.format(seen) + market;
        ObservationRow row = new ObservationRow();
        row.setSeriesKey(market + "-" + departure + "-Y");
        row.setSource("gds");
        row.setFingerprint((fingerprint + "0".repeat(32)).substring(0, 32));
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(departure);
        row.setCabin("Y");
        row.setBaseAmount(new BigDecimal("700.00"));
        row.setBaseCurrency("EUR");
        row.setTaxAmount(new BigDecimal("0.00"));
        row.setSurchargeAmount(new BigDecimal("0.00"));
        row.setTotalAmount(new BigDecimal("700.00"));
        row.setFirstSeen(seen);
        row.setLastSeen(seen);
        work.session().persist(row);
    }

    private static void aRun(UnitOfWork work, LocalDateTime started) {
        RunRow row = new RunRow();
        row.setStartedAt(started);
        row.setFinishedAt(started);
        row.setTasks(1);
        row.setCalls(1);
        work.session().persist(row);
    }

    private long observations() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            return work.observations().count();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] payload) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(packed)) {
            out.write(payload);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
        return packed.toByteArray();
    }

    private static void overwrite(Path blob, byte[] content) {
        try {
            Files.write(blob, content);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    @Test
    void pruningAnEmptyDeskSaysNothingWent() {
        Shell.Result reply = runner.run("prune", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("0 observations, 0 runs");
    }

    @Test
    void aDryRunSaysWhatItWouldDoAndDoesNotDoIt() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, OLD, GONE);
            work.commit();
        }

        Shell.Result reply = runner.run("prune", "--on", TODAY, "--observation-days", "30");
        assertThat(reply.output()).contains("1 observations");
        assertThat(reply.output()).contains("nothing was deleted");

        assertThat(observations()).isEqualTo(1);
    }

    @Test
    void applyingItActuallyDeletes() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, OLD, GONE);
            work.commit();
        }

        Shell.Result reply = runner.run("prune", "--on", TODAY, "--observation-days", "30", "--apply");
        assertThat(reply.output()).doesNotContain("nothing was deleted");

        assertThat(observations()).isZero();
    }

    @Test
    void aReadingForADepartureStillAheadIsLeftAlone() {
        // Keyed on the departure and not on when the row was written. Somebody can still be asked what
        // that fare was.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, OLD, AHEAD);
            work.commit();
        }

        runner.run("prune", "--on", TODAY, "--observation-days", "30", "--apply");
        assertThat(observations()).isEqualTo(1);
    }

    @Test
    void theDryRunCountsWhatTheRealOneDeletes() {
        // Two code paths would mean the dry run reports on something the real one does not do, which is
        // the one thing a dry run must never do.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, OLD, GONE);
            anObservation(work, OLD, GONE, "FRA-LON");
            aRun(work, OLD);
            work.commit();
        }

        String[] window = {"--observation-days", "30", "--run-days", "30", "--archive-days", "30"};
        Shell.Result dry = runner.run(line(window));
        Shell.Result wet = runner.run(line(window, "--apply"));
        assertThat(dry.output()).contains("2 observations, 1 runs");
        assertThat(wet.output()).contains("2 observations, 1 runs");
    }

    /** {@code prune --on TODAY}, then the window, then anything else. */
    private static String[] line(String[] window, String... more) {
        String[] args = new String[3 + window.length + more.length];
        args[0] = "prune";
        args[1] = "--on";
        args[2] = TODAY;
        System.arraycopy(window, 0, args, 3, window.length);
        System.arraycopy(more, 0, args, 3 + window.length, more.length);
        return args;
    }

    @Test
    void keepingRunsForLessTimeThanThePayloadsTheyExplainIsRefused() {
        // A result row with no run to hang it off is unreadable, and a payload whose run has gone is a
        // sighting nobody can date.
        Shell.Result reply = runner.run("prune", "--on", TODAY, "--run-days", "30");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure())
                .hasValueSatisfying(refused -> assertThat(refused).hasMessageContaining("at least as long"));
    }

    @Test
    void thePolicyItIsUsingIsPrintedBeforeItActs() {
        assertThat(runner.run("prune", "--on", TODAY).output()).contains("keeping");
    }

    @Test
    void itSaysHowManyPayloadsAreStillReferenced() {
        Archive archive = new Archive(raw);
        archive.put(bytes("a payload"), "gds", URL, RECENT);
        Shell.Result reply = runner.run("prune", "--on", TODAY, "--archive-days", "30");
        assertThat(reply.output()).contains("1 payloads still referenced");
    }

    @Test
    void anOldPayloadGoesWhenItIsApplied() {
        Archive archive = new Archive(raw);
        Sighting sighting = archive.put(bytes("a payload"), "gds", URL, OLD);
        runner.run("prune", "--on", TODAY, "--archive-days", "30", "--apply");
        assertThat(archive.holds(sighting.digest())).isFalse();
    }

    @Test
    void scrubbingIsOffUnlessItIsAskedFor() {
        // It reads and decompresses the whole store, which on a year of collecting is minutes rather
        // than seconds.
        Archive archive = new Archive(raw);
        archive.put(bytes("a payload"), "gds", URL, RECENT);
        assertThat(runner.run("prune", "--on", TODAY).output()).doesNotContain("read back");
    }

    @Test
    void scrubbingDrySaysHowManyPayloadsDoNotReadBack() {
        Archive archive = new Archive(raw);
        Sighting bad = archive.put(bytes("a payload"), "gds", URL, RECENT);
        overwrite(archive.pathFor(bad.digest()), bytes("not gzip at all"));

        Shell.Result reply = runner.run("prune", "--on", TODAY, "--scrub");
        assertThat(reply.output()).contains("1 payloads do not read back");
        assertThat(reply.output()).contains(bad.digest());
        assertThat(archive.holds(bad.digest())).isTrue();
    }

    @Test
    void scrubbingDryOnAHealthyStoreSaysNone() {
        new Archive(raw).put(bytes("a payload"), "gds", URL, RECENT);
        assertThat(runner.run("prune", "--on", TODAY, "--scrub").output()).contains("0 payloads do not read back");
    }

    @Test
    void scrubbingAppliedRemovesWhatCannotBeRead() {
        Archive archive = new Archive(raw);
        Sighting bad = archive.put(bytes("a payload"), "gds", URL, RECENT);
        overwrite(archive.pathFor(bad.digest()), bytes("not gzip at all"));

        Shell.Result reply = runner.run("prune", "--on", TODAY, "--scrub", "--apply");
        assertThat(reply.output()).contains("removed 1 unreadable payloads");
        assertThat(archive.holds(bad.digest())).isFalse();
    }

    @Test
    void scrubbingAppliedRemovesAnInterruptedWrite() {
        Archive archive = new Archive(raw);
        Sighting sighting = archive.put(bytes("a payload"), "gds", URL, RECENT);
        Path blob = archive.pathFor(sighting.digest());
        overwrite(blob.resolveSibling(blob.getFileName() + Archive.PARTIAL), bytes("half a payload"));

        Shell.Result reply = runner.run("prune", "--on", TODAY, "--scrub", "--apply");
        assertThat(reply.output()).contains("1 unfinished writes");
        assertThat(archive.get(sighting.digest())).isEqualTo(bytes("a payload"));
    }

    @Test
    void aGoodPayloadSurvivesAScrubThatTakesOutABadOne() {
        Archive archive = new Archive(raw);
        Sighting good = archive.put(bytes("a payload"), "gds", URL, RECENT);
        Sighting bad = archive.put(bytes("another payload"), "ndc", URL, RECENT);
        overwrite(archive.pathFor(bad.digest()), gzip(bytes("something else")));

        runner.run("prune", "--on", TODAY, "--scrub", "--apply");
        assertThat(archive.get(good.digest())).isEqualTo(bytes("a payload"));
        assertThat(archive.holds(bad.digest())).isFalse();
    }
}
