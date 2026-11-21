package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCollectTest {

    private static final String TODAY = "2025-07-21";
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 24);
    private static final Map<String, Object> FRANKFURT_NEW_YORK_IN_AUGUST =
            Map.of("market", "FRA-NYC", "departure", AUGUST.toString());

    /** A run that goes down partway through, the way a reboot does. */
    private static final Wiring DIES = Wiring.standard()
            .withPoolRun((pool, collector, tasks, today, fetch, parsers, schedule, sink) -> {
                throw new UncheckedIOException(new IOException("the machine went down"));
            });

    @TempDir
    Path home;

    private String url;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        url = Shell.fileUrl(home);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", url)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", home.resolve("raw").toString());
    }

    private void makeWatch() {
        makeWatch("FRA", "JFK", AUGUST, null);
    }

    private void makeWatch(String origin, String destination) {
        makeWatch(origin, destination, AUGUST, null);
    }

    private void makeWatch(String destination, LocalDate departure) {
        makeWatch("FRA", destination, departure, null);
    }

    private void makeWatch(LocalDateTime last) {
        makeWatch("FRA", "JFK", AUGUST, last);
    }

    private void makeWatch(String origin, String destination, LocalDate departure, LocalDateTime last) {
        try (Database database = Shell.database(url)) {
            database.createSchema();
            try (UnitOfWork work = database.unit()) {
                TicketRow ticket = new TicketRow();
                ticket.setNumber("220-" + origin + destination);
                ticket.setRecordLocator("ABC123");
                ticket.setPassengerName("MUELLER/ANNA");
                ticket.setOrigin(origin);
                ticket.setDestination(destination);
                ticket.setDeparture(departure);
                ticket.setIssuedOn(LocalDate.of(2025, 2, 14));
                ticket.setValidatingCarrier("LH");
                ticket.setBaseAmount(new BigDecimal("840.00"));
                ticket.setBaseCurrency("EUR");
                ticket.setTotalAmount(new BigDecimal("1090.83"));
                WatchRow watch = new WatchRow();
                watch.setSeriesKey(origin + "-" + destination + "-" + departure + "-Y");
                watch.setOrigin(origin);
                watch.setDestination(destination);
                watch.setDeparture(departure);
                watch.setThresholdAmount(new BigDecimal("50.00"));
                watch.setThresholdCurrency("EUR");
                watch.setLastChecked(last);
                ticket.addWatch(watch);
                work.session().persist(ticket);
                work.commit();
            }
        }
    }

    private Archive archive() {
        return new Archive(home.resolve("raw"));
    }

    private long runsKept() {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            return work.runs().count();
        }
    }

    @Test
    void theCollectGroupListsItsThreeCommands() {
        Shell.Result reply = runner.run("collect", "--help");
        assertThat(reply.output()).contains("plan", "run", "replay");
    }

    @Test
    void aPlanAgainstAnEmptyDeskPlansNothing() {
        Shell.createSchema(url);
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("0 series followed", "0 tasks over 0 markets");
    }

    @Test
    void aWatchBecomesASeriesAndATask() {
        makeWatch();
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY);
        assertThat(reply.output()).contains("1 series followed, 1 due", "1 tasks over 1 markets", "FRA-NYC 2025-08-24");
    }

    @Test
    void theLastCheckIsCarriedOverSoTheCadenceSurvivesARestart() {
        // Without this every run after a restart thinks nothing was ever polled and asks for the whole
        // book on the first morning.
        makeWatch(LocalDateTime.of(2025, 7, 20, 6, 0));
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY);
        assertThat(reply.output()).contains("1 series followed, 0 due");
    }

    @Test
    void aMarketTheFeedCoversGoesToTheFeedAndNotTheGateway() {
        // Both cover FRA to New York. The feed is free, so it gets the question.
        makeWatch();
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY);
        assertThat(reply.output()).contains("ndc FRA-NYC");
    }

    @Test
    void aMarketOnlyTheGatewayCoversGoesToTheGateway() {
        makeWatch("LHR", "DXB");
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY);
        assertThat(reply.output()).contains("gds LON-DXB");
    }

    @Test
    void theSupplierCanBePinned() {
        makeWatch();
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY, "--supplier", "gds");
        assertThat(reply.output()).contains("gds FRA-NYC");
    }

    @Test
    void pinningASupplierThatDoesNotCoverTheMarketPlansNothing() {
        makeWatch("LHR", "DXB");
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY, "--supplier", "ndc");
        assertThat(reply.output()).contains("0 tasks");
    }

    @Test
    void theBudgetCapsThePlan() {
        makeWatch();
        makeWatch("FRA", "ORD");
        makeWatch("FRA", "SIN");
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY, "--budget", "2");
        assertThat(reply.output()).contains("2 tasks");
    }

    @Test
    void theClosestDeparturesArePlannedFirst() {
        makeWatch("ORD", LocalDate.of(2025, 12, 1));
        makeWatch("JFK", LocalDate.of(2025, 7, 30));
        Shell.Result reply = runner.run("collect", "plan", "--on", TODAY, "--budget", "1");
        assertThat(reply.output()).contains("2025-07-30");
        assertThat(reply.output()).doesNotContain("2025-12-01");
    }

    @Test
    void aDryRunOfTheRealCommandGoesNowhere() {
        makeWatch();
        Shell.Result reply = runner.exporting("GUSSETLINE_DRY_RUN", "1").run("collect", "run", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("dry run, nothing went out");
    }

    @Test
    void aDryRunKeepsNoRun() {
        makeWatch();
        runner.exporting("GUSSETLINE_DRY_RUN", "1").run("collect", "run", "--on", TODAY);
        assertThat(runsKept()).isZero();
    }

    @Test
    void aReplayOfAnEmptyArchiveReportsThatNothingWasStored() {
        makeWatch();
        Shell.Result reply = runner.run("collect", "replay", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("nothing stored for that market");
    }

    @Test
    void aReplayKeepsNothingAndSaysWhy() {
        makeWatch();
        archive()
                .put(
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "ndc",
                        "/v2/offers",
                        LocalDateTime.of(2025, 7, 20, 6, 0),
                        FRANKFURT_NEW_YORK_IN_AUGUST);
        Shell.Result reply = runner.run("collect", "replay", "--on", TODAY);
        assertThat(reply.output()).contains("no run kept, because nothing was asked of anybody");
        assertThat(runsKept()).isZero();
    }

    @Test
    void aReplayReparsesTheStoredPayload() {
        makeWatch();
        String payload = "{\"offers\": [{\"id\": \"1\", \"total\": {\"amount\": \"700.00\", \"currency\": \"EUR\"},"
                + " \"fareBasis\": \"KLWAP7RT\", \"segments\": [{\"carrier\": \"LH\", \"number\": \"400\","
                + " \"origin\": \"FRA\", \"destination\": \"JFK\", \"departure\": \"2025-08-24T10:25:00\","
                + " \"arrival\": \"2025-08-24T13:05:00\", \"bookingClass\": \"K\"}]}]}";
        archive()
                .put(
                        payload.getBytes(StandardCharsets.UTF_8),
                        "ndc",
                        "/v2/offers",
                        LocalDateTime.of(2025, 7, 20, 6, 0),
                        FRANKFURT_NEW_YORK_IN_AUGUST);
        Shell.Result reply = runner.run("collect", "replay", "--on", TODAY);
        assertThat(reply.output()).contains("ok", "1 offers");
    }

    @Test
    void aReplayLooksAtEverySeriesAndNotOnlyTheDueOnes() {
        // The point of a replay is to reparse history, so a cadence that says a market is not due
        // today has nothing to do with it.
        makeWatch(LocalDateTime.of(2025, 7, 20, 6, 0));
        archive()
                .put(
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "ndc",
                        "/v2/offers",
                        LocalDateTime.of(2025, 7, 20, 6, 0),
                        FRANKFURT_NEW_YORK_IN_AUGUST);
        Shell.Result reply = runner.run("collect", "replay", "--on", TODAY);
        assertThat(reply.output()).contains("1 tasks");
    }

    @Test
    void theCostOfARunIsPrinted() {
        makeWatch();
        archive()
                .put(
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "ndc",
                        "/v2/offers",
                        LocalDateTime.of(2025, 7, 20, 6, 0),
                        FRANKFURT_NEW_YORK_IN_AUGUST);
        Shell.Result reply = runner.run("collect", "replay", "--on", TODAY);
        assertThat(reply.output()).contains("cost 0");
    }

    @Test
    void aRunIsWrittenDownBeforeAnyCallGoesOut() {
        // A night run is hours long. A row written only at the end means a machine that reboots at
        // three leaves no trace that the desk polled anything at all, and the morning check cannot
        // tell that from a desk that never started.
        makeWatch();

        Shell.Result reply = runner.wiredWith(DIES).run("collect", "run", "--on", TODAY);
        assertThat(reply.code()).isNotZero();

        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().count()).isEqualTo(1);
            assertThat(work.runs().unfinished()).hasSize(1);
        }
    }

    @Test
    void aRunThatDiedIsLeftOpenRatherThanLookingHealthy() {
        makeWatch();
        runner.wiredWith(DIES).run("collect", "run", "--on", TODAY);

        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished().getFirst().getFinishedAt()).isEmpty();
        }
    }

    @Test
    void aRunThatFinishedIsClosedAndCarriesItsResults() {
        makeWatch();
        Shell.Result reply = runner.run("collect", "run", "--on", TODAY);
        assertThat(reply.code()).isZero();

        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().unfinished()).isEmpty();
            assertThat(work.runs().count()).isEqualTo(1);
            assertThat(work.runs().recent(1).getFirst().results()).isNotEmpty();
        }
    }

    @Test
    void aRunThatFinishedKeepsTheIdItWasGivenAtTheStart() {
        makeWatch();
        Shell.Result reply = runner.run("collect", "run", "--on", TODAY);
        List<String> lines = reply.output().strip().lines().toList();
        String[] last = lines.getLast().split(" ");
        int identifier = Integer.parseInt(last[last.length - 1]);

        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().row(identifier).getFinishedAt()).isPresent();
        }
    }
}
