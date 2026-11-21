package io.github.rkaushikdev.gussetline.retention;

import static io.github.rkaushikdev.gussetline.retention.Retention.ARCHIVE_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.OBSERVATION_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.RUN_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.defaultPolicy;
import static io.github.rkaushikdev.gussetline.retention.Retention.keepable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.cli.Shell;
import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

    @TempDir
    Path home;

    private String env;
    private Shell runner;

    @Test
    void theShippedPolicyKeepsEachKindForADifferentWhile() {
        Policy policy = defaultPolicy();
        assertThat(policy.observationDays()).isGreaterThan(policy.runDays());
        assertThat(policy.runDays()).isGreaterThan(policy.archiveDays());
    }

    @Test
    void aRetentionOfNothingIsRefused() {
        assertThatThrownBy(() -> new Policy(0, RUN_DAYS, ARCHIVE_DAYS))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least a day");
    }

    @Test
    void keepingPayloadsLongerThanTheRunsThatExplainThemIsRefused() {
        // Disk with nothing to say about it.
        assertThatThrownBy(() -> new Policy(OBSERVATION_DAYS, 30, 90))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least as long");
    }

    @Test
    void aPolicyReadsBackInOneLine() {
        assertThat(new Policy(400, 120, 90)).hasToString("observations 400d, runs 120d, payloads 90d");
    }

    @Test
    void theCutoffsAreWorkedOutFromToday() {
        Policy policy = new Policy(400, 120, 90);
        assertThat(policy.observationsBefore(TODAY)).isEqualTo(LocalDate.of(2024, 12, 11));
        assertThat(policy.runsBefore(TODAY)).isEqualTo(LocalDateTime.of(2025, 9, 17, 0, 0));
        assertThat(policy.archiveBefore(TODAY)).isEqualTo(LocalDateTime.of(2025, 10, 17, 0, 0));
    }

    @Test
    void aManifestRowOlderThanTheCutoffDoesNotSurvive() {
        List<Map<String, String>> rows = List.of(
                Map.of("digest", "a".repeat(32), "seen_at", "2025-01-01T06:00:00"),
                Map.of("digest", "b".repeat(32), "seen_at", "2026-01-01T06:00:00"));
        Kept<Map<String, String>> kept = keepable(rows, LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
        assertThat(kept.named()).isEqualTo(Set.of("b".repeat(32)));
    }

    @Test
    void aPayloadASurvivingRowStillNamesIsKept() {
        // The same payload seen on thirty mornings is one blob and thirty rows.
        List<Map<String, String>> rows = List.of(
                Map.of("digest", "a".repeat(32), "seen_at", "2025-01-01T06:00:00"),
                Map.of("digest", "a".repeat(32), "seen_at", "2026-01-01T06:00:00"));
        Kept<Map<String, String>> kept = keepable(rows, LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
        assertThat(kept.named()).isEqualTo(Set.of("a".repeat(32)));
    }

    @Test
    void aRowWithNoStampOnItIsKeptRatherThanGuessedAt() {
        Kept<Map<String, String>> kept =
                keepable(List.of(Map.of("digest", "a".repeat(32), "seen_at", "")), LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
    }

    /** A desk on disk with its schema built, and a shell pointed at it and at an archive beside it. */
    private void aDesk() {
        env = Shell.fileUrl(home);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", home.resolve("raw").toString());
        Shell.createSchema(env);
    }

    private Archive archive() {
        return new Archive(home.resolve("raw"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void anObservation(UnitOfWork work, LocalDate departure) {
        ObservationRow row = new ObservationRow();
        row.setSeriesKey("FRA-NYC-" + departure + "-Y");
        row.setSource("gds");
        row.setFingerprint("a".repeat(32));
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(departure);
        row.setCabin("Y");
        row.setBaseAmount(new BigDecimal("700.00"));
        row.setBaseCurrency("EUR");
        row.setTaxAmount(new BigDecimal("0.00"));
        row.setSurchargeAmount(new BigDecimal("0.00"));
        row.setTotalAmount(new BigDecimal("700.00"));
        row.setFirstSeen(LocalDateTime.of(2024, 1, 1, 6, 0));
        row.setLastSeen(LocalDateTime.of(2024, 1, 1, 6, 0));
        work.session().persist(row);
    }

    private static void aRun(UnitOfWork work, LocalDateTime started) {
        Task task = new Task("gds", new Market("FRA", "JFK"), LocalDate.of(2025, 1, 1), 1);
        work.runs()
                .record(new RunReport(
                        started,
                        List.of(Result.builder(task, Outcome.OK).attempts(1).build())));
    }

    private static void aRecommendation(UnitOfWork work, LocalDate raised) {
        TicketRow ticket = new TicketRow();
        ticket.setNumber("220-1234567890");
        ticket.setRecordLocator("ABC123");
        ticket.setPassengerName("MUELLER/ANNA");
        ticket.setOrigin("FRA");
        ticket.setDestination("JFK");
        ticket.setDeparture(LocalDate.of(2024, 6, 1));
        ticket.setIssuedOn(LocalDate.of(2024, 2, 14));
        ticket.setValidatingCarrier("LH");
        ticket.setBaseAmount(new BigDecimal("840.00"));
        ticket.setBaseCurrency("EUR");
        ticket.setTotalAmount(new BigDecimal("1090.83"));
        CouponRow coupon = new CouponRow();
        coupon.setPosition(0);
        coupon.setCarrier("LH");
        coupon.setFlightNumber("400");
        coupon.setOrigin("FRA");
        coupon.setDestination("JFK");
        coupon.setDeparture(LocalDateTime.of(2024, 6, 1, 10, 25));
        coupon.setBookingClass("K");
        coupon.setFareBasis("KLWAP7RT");
        ticket.addCoupon(coupon);
        WatchRow watch = new WatchRow();
        watch.setSeriesKey("FRA-NYC-2024-06-01-Y");
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(LocalDate.of(2024, 6, 1));
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        ticket.addWatch(watch);
        work.session().persist(ticket);
        work.flush();
        RecommendationRow made = new RecommendationRow();
        made.setWatchId(watch.getId());
        made.setRaisedOn(raised);
        made.setState("taken");
        made.setPaidAmount(new BigDecimal("840.00"));
        made.setNewFareAmount(new BigDecimal("700.00"));
        made.setPenaltyAmount(new BigDecimal("50.00"));
        made.setBenefitAmount(new BigDecimal("90.00"));
        made.setCurrency("EUR");
        made.setResidual("refundable");
        work.session().persist(made);
    }

    private long observations() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            return work.observations().count();
        }
    }

    @Test
    void aDryRunSaysWhatWouldGoAndDeletesNothing() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, LocalDate.of(2024, 6, 1));
            work.commit();
        }

        Shell.Result reply = runner.run("prune", "--on", TODAY.toString());
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("1 observations");
        assertThat(reply.output()).contains("nothing was deleted");

        assertThat(observations()).isEqualTo(1);
    }

    @Test
    void applyingItActuallyDeletes() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, LocalDate.of(2024, 6, 1));
            work.commit();
        }

        Shell.Result reply = runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(reply.output()).doesNotContain("nothing was deleted");

        assertThat(observations()).isZero();
    }

    @Test
    void aReadingForADepartureStillAheadIsKept() {
        // Keyed on the departure and not on when the row was written.
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            anObservation(work, LocalDate.of(2026, 6, 1));
            work.commit();
        }

        runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(observations()).isEqualTo(1);
    }

    @Test
    void aRecommendationIsNeverTouchedHoweverOld() {
        // The savings report reads them, and a report that changes when old rows are deleted cannot be
        // put in front of anybody.
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aRecommendation(work, LocalDate.of(2023, 1, 1));
            work.commit();
        }

        runner.run("prune", "--on", TODAY.toString(), "--apply");
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            assertThat(work.session()
                            .createQuery("select r from RecommendationRow r", RecommendationRow.class)
                            .getResultList())
                    .hasSize(1);
        }
    }

    @Test
    void anOldRunGoesAndARecentOneStays() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aRun(work, LocalDateTime.of(2024, 1, 1, 6, 0));
            aRun(work, LocalDateTime.of(2026, 1, 1, 6, 0));
            work.commit();
        }

        runner.run("prune", "--on", TODAY.toString(), "--apply");
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().count()).isEqualTo(1);
        }
    }

    @Test
    void anOldPayloadGoes() {
        aDesk();
        Archive archive = archive();
        archive.put(bytes("old"), "gds", "/x", LocalDateTime.of(2024, 1, 1, 6, 0));
        assertThat(archive.storedCount()).isEqualTo(1);

        runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(archive().storedCount()).isZero();
    }

    @Test
    void aPayloadARecentSightingStillNamesIsKept() {
        aDesk();
        Archive archive = archive();
        archive.put(bytes("same"), "gds", "/x", LocalDateTime.of(2024, 1, 1, 6, 0));
        archive.put(bytes("same"), "gds", "/x", LocalDateTime.of(2026, 1, 1, 6, 0));

        Shell.Result reply = runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(reply.output()).contains("1 payloads still referenced");
        assertThat(archive().storedCount()).isEqualTo(1);
    }

    @Test
    void theOldSightingLeavesTheManifestEvenWhenThePayloadStays() {
        aDesk();
        Archive archive = archive();
        archive.put(bytes("same"), "gds", "/x", LocalDateTime.of(2024, 1, 1, 6, 0));
        archive.put(bytes("same"), "gds", "/x", LocalDateTime.of(2026, 1, 1, 6, 0));

        runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(archive().manifest()).hasSize(1);
    }

    @Test
    void aRecentPayloadStays() {
        aDesk();
        archive().put(bytes("new"), "gds", "/x", LocalDateTime.of(2026, 1, 1, 6, 0));
        runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(archive().storedCount()).isEqualTo(1);
    }

    @Test
    void theRetentionsCanBeNamed() {
        aDesk();
        Shell.Result reply = runner.run(
                "prune",
                "--on",
                TODAY.toString(),
                "--observation-days",
                "10",
                "--run-days",
                "5",
                "--archive-days",
                "5");
        assertThat(reply.output()).contains("observations 10d, runs 5d, payloads 5d");
    }

    @Test
    void aRetentionThatMakesNoSenseIsRefused() {
        aDesk();
        Shell.Result reply = runner.run("prune", "--on", TODAY.toString(), "--run-days", "5");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(ValidationError.class);
    }

    @Test
    void anEmptyDeskPrunesNothingAndSaysSo() {
        aDesk();
        Shell.Result reply = runner.run("prune", "--on", TODAY.toString(), "--apply");
        assertThat(reply.output()).contains("0 observations, 0 runs, 0 payloads");
    }
}
