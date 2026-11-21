package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliReportTest {

    private static final String TODAY = "2025-07-30";
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 8, 24);
    private static final String SERIES = "FRA-NYC-2025-08-24-Y";

    @TempDir
    Path tmp;

    private String env;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        env = Shell.fileUrl(tmp);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", tmp.resolve("raw").toString());
        Shell.createSchema(env);
    }

    private static WatchRow aWatch(UnitOfWork work) {
        return aWatch(work, SERIES, DEPARTURE, null, true);
    }

    private static WatchRow aWatch(UnitOfWork work, String series, LocalDate departure) {
        return aWatch(work, series, departure, null, true);
    }

    private static WatchRow aWatch(
            UnitOfWork work, String series, LocalDate departure, LocalDateTime last, boolean active) {
        TicketRow ticket = new TicketRow();
        ticket.setNumber("220-" + series.substring(series.length() - 8));
        ticket.setRecordLocator("ABC123");
        ticket.setPassengerName("MUELLER/ANNA");
        ticket.setOrigin("FRA");
        ticket.setDestination("JFK");
        ticket.setDeparture(departure);
        ticket.setIssuedOn(LocalDate.of(2025, 2, 14));
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
        coupon.setDeparture(departure.atTime(10, 25));
        coupon.setBookingClass("K");
        coupon.setFareBasis("KLWAP7RT");
        ticket.addCoupon(coupon);
        WatchRow watch = new WatchRow();
        watch.setSeriesKey(series);
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(departure);
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        watch.setLastChecked(last);
        watch.setActive(active);
        ticket.addWatch(watch);
        work.session().persist(ticket);
        work.flush();
        return watch;
    }

    private static void aRecommendation(UnitOfWork work, WatchRow watch, LocalDate raised, String state) {
        aRecommendation(work, watch, raised, state, "90.00");
    }

    private static void aRecommendation(
            UnitOfWork work, WatchRow watch, LocalDate raised, String state, String benefit) {
        RecommendationRow row = new RecommendationRow();
        row.setWatchId(watch.getId());
        row.setRaisedOn(raised);
        row.setState(state);
        row.setPaidAmount(new BigDecimal("840.00"));
        row.setNewFareAmount(new BigDecimal("700.00"));
        row.setPenaltyAmount(new BigDecimal("50.00"));
        row.setBenefitAmount(new BigDecimal(benefit));
        row.setCurrency("EUR");
        row.setResidual("refundable");
        row.setDecidedBy(state.equals("taken") || state.equals("killed") ? "anna" : "");
        row.setDecidedOn(state.equals("open") ? null : raised);
        work.session().persist(row);
    }

    private static void aRun(UnitOfWork work, LocalDateTime started, Outcome... outcomes) {
        List<Result> results = Arrays.stream(outcomes)
                .map(outcome -> Result.builder(new Task("gds", new Market("FRA", "JFK"), DEPARTURE, 30), outcome)
                        .attempts(outcome == Outcome.BLOCKED ? 0 : 1)
                        .offers(outcome == Outcome.OK ? 6 : 0)
                        .build())
                .toList();
        work.runs().record(new RunReport(started, results));
    }

    /** One tariff file with a fare per amount it was given. */
    private static LoadRow aFiling(
            UnitOfWork work, String filename, LocalDateTime loaded, Map<String, String> amounts) {
        return aFiling(work, filename, loaded, amounts, "0100");
    }

    private static LoadRow aFiling(
            UnitOfWork work, String filename, LocalDateTime loaded, Map<String, String> amounts, String ruleNumber) {
        LoadRow load = new LoadRow();
        load.setCarrier("LH");
        load.setFilename(filename);
        load.setDigest((filename + "0".repeat(32)).substring(0, 32));
        load.setLoadedAt(loaded);
        load.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        load.setRowCount(amounts.size());
        work.session().persist(load);
        work.flush();
        amounts.forEach((basis, amount) -> {
            FareRow fare = new FareRow();
            fare.setLoadId(load.getId());
            fare.setCarrier("LH");
            fare.setOrigin("FRA");
            fare.setDestination("NYC");
            fare.setFareBasis(basis);
            fare.setAmount(new BigDecimal(amount));
            fare.setCurrency("EUR");
            fare.setOwrt("2");
            fare.setGlobalIndicator("AT");
            fare.setRuleNumber(ruleNumber);
            fare.setRoutingNumber("0011");
            fare.setTariffNumber("1");
            fare.setCabin("Y");
            fare.setFareType("published");
            fare.setEffective(LocalDate.of(2025, 1, 1));
            fare.setDiscontinue(LocalDate.of(2025, 12, 31));
            fare.setFirstTravel(LocalDate.of(2025, 1, 1));
            fare.setLastTravel(LocalDate.of(2025, 12, 31));
            work.session().persist(fare);
        });
        return load;
    }

    private void twoFilings(Map<String, String> after) {
        twoFilings(after, "0100");
    }

    private void twoFilings(Map<String, String> after, String ruleNumber) {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aFiling(work, "week01.dat", LocalDateTime.of(2025, 7, 1, 6, 0), Map.of("KLWAP7RT", "420.00"));
            aFiling(work, "week02.dat", LocalDateTime.of(2025, 7, 8, 6, 0), after, ruleNumber);
            work.commit();
        }
    }

    /** The same amount under several fare bases, in the order given. */
    private static Map<String, String> each(String amount, String... bases) {
        Map<String, String> amounts = new LinkedHashMap<>();
        for (String basis : bases) {
            amounts.put(basis, amount);
        }
        return amounts;
    }

    @Test
    void theReportGroupListsItsThreeCommands() {
        Shell.Result reply = runner.run("report", "--help");
        assertThat(reply.output()).contains("savings");
        assertThat(reply.output()).contains("reliability");
        assertThat(reply.output()).contains("coverage");
    }

    @Test
    void savingsOnAnEmptyDeskIsAllZeroes() {
        Shell.Result reply = runner.run("report", "savings", "--until", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("found      0 EUR");
        assertThat(reply.output()).contains("raised     0");
    }

    @Test
    void savingsCountsWhatWasFoundTakenAndMissed() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 7, 20), "taken", "90.00");
            aRecommendation(work, watch, LocalDate.of(2025, 7, 21), "expired", "70.00");
            aRecommendation(work, watch, LocalDate.of(2025, 7, 22), "killed", "40.00");
            work.commit();
        }

        Shell.Result reply = runner.run("report", "savings", "--until", TODAY);
        assertThat(reply.output()).contains("found      200.00 EUR");
        assertThat(reply.output()).contains("taken      90.00 EUR");
        assertThat(reply.output()).contains("missed     70.00 EUR");
    }

    @Test
    void savingsIsKeyedOnTheDayItWasRaised() {
        // A recommendation belongs to the month the desk found it in. Keying on the settlement moves rows
        // between months when an agent clears a backlog.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 6, 1), "taken");
            aRecommendation(work, watch, LocalDate.of(2025, 7, 20), "taken");
            work.commit();
        }

        Shell.Result reply = runner.run("report", "savings", "--since", "2025-07-01", "--until", TODAY);
        assertThat(reply.output()).contains("raised    1");
    }

    @Test
    void savingsDefaultsToTheLastMonth() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.now(), "taken");
            work.commit();
        }

        Shell.Result reply = runner.run("report", "savings");
        assertThat(reply.output()).contains("raised    1");
    }

    @Test
    void savingsGroupsByMonthAndByAgent() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 7, 20), "taken");
            work.commit();
        }

        Shell.Result reply = runner.run("report", "savings", "--until", TODAY);
        assertThat(reply.output()).contains("by month");
        assertThat(reply.output()).contains("2025-07");
        assertThat(reply.output()).contains("anna");
    }

    @Test
    void reliabilityOnAnEmptyDeskReadsAsNothingRan() {
        Shell.Result reply = runner.run("report", "reliability");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("nothing ran");
    }

    @Test
    void reliabilityReadsTheStoredRuns() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aRun(work, LocalDateTime.now(), Outcome.OK, Outcome.OK, Outcome.OK);
            work.commit();
        }

        Shell.Result reply = runner.run("report", "reliability");
        assertThat(reply.output()).contains("gds        sound");
        assertThat(reply.output()).contains("planned 3 called 3 answered 3");
    }

    @Test
    void aSupplierThatNeedsALookMakesTheReportExitOne() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aRun(work, LocalDateTime.now(), Outcome.OK, Outcome.BLOCKED);
            work.commit();
        }

        Shell.Result reply = runner.run("report", "reliability");
        assertThat(reply.code()).isEqualTo(1);
        assertThat(reply.output()).contains("unfit");
    }

    @Test
    void reliabilityCanBeAskedForANumberOfRunsInstead() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            for (int day = 1; day < 6; day++) {
                aRun(work, LocalDateTime.of(2025, 7, day, 6, 0), Outcome.OK);
            }
            work.commit();
        }

        Shell.Result reply = runner.run("report", "reliability", "--runs", "2");
        assertThat(reply.output()).contains("reliability over 2 runs");
    }

    @Test
    void coverageOnAnEmptyDeskHasNothingToCover() {
        Shell.Result reply = runner.run("report", "coverage", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("followed  0");
    }

    @Test
    void aWatchNobodyPolledIsADarkGap() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aWatch(work);
            work.commit();
        }

        Shell.Result reply = runner.run("report", "coverage", "--on", TODAY);
        assertThat(reply.code()).isEqualTo(1);
        assertThat(reply.output()).contains("dark      1");
        assertThat(reply.output()).contains("gaps, worst first");
    }

    @Test
    void aRetiredWatchIsNotAGap() {
        // Counting one makes the coverage figure fall every time the desk correctly stops watching
        // something.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aWatch(work, SERIES, DEPARTURE, null, false);
            work.commit();
        }

        Shell.Result reply = runner.run("report", "coverage", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("followed  0");
    }

    @Test
    void aPolledWatchIsCovered() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aWatch(work, SERIES, DEPARTURE, LocalDateTime.of(2025, 7, 29, 6, 0), true);
            aRun(work, LocalDateTime.of(2025, 7, 29, 6, 0), Outcome.OK);
            work.commit();
        }

        Shell.Result reply = runner.run("report", "coverage", "--on", TODAY);
        assertThat(reply.output()).contains("priced    1");
        assertThat(reply.code()).isZero();
    }

    @Test
    void theNumberOfGapsListedCanBeCapped() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            for (int day = 1; day < 6; day++) {
                aWatch(work, String.format(Locale.ROOT, "FRA-NYC-2025-09-0%d-Y", day), LocalDate.of(2025, 9, day));
            }
            work.commit();
        }

        Shell.Result reply = runner.run("report", "coverage", "--on", TODAY, "--show", "2");
        assertThat(reply.output()).contains("showing 2 of 5");
    }

    @Test
    void noneOfTheReportsWritesAnything() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 7, 20), "open");
            aRun(work, LocalDateTime.of(2025, 7, 29, 6, 0), Outcome.OK);
            work.commit();
        }

        runner.run("report", "savings", "--until", TODAY);
        runner.run("report", "reliability");
        runner.run("report", "coverage", "--on", TODAY);

        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            assertThat(work.runs().count()).isEqualTo(1);
            List<String> states = work
                    .session()
                    .createQuery("select r from RecommendationRow r", RecommendationRow.class)
                    .getResultList()
                    .stream()
                    .map(RecommendationRow::getState)
                    .toList();
            assertThat(states).containsExactly("open");
        }
    }

    @Test
    void theQueueCommandShowsUpInTheGroup() {
        assertThat(runner.run("report", "--help").output()).contains("queue");
    }

    @Test
    void anEmptyDeskHasNothingOpen() {
        Shell.Result reply = runner.run("report", "queue", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("nothing open");
    }

    @Test
    void theQueueListsTheOpenRecommendationsWithWhatTheyAreWorth() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 7, 29), "open", "90.00");
            work.commit();
        }

        Shell.Result reply = runner.run("report", "queue", "--on", TODAY);
        assertThat(reply.output()).contains("1 open");
        assertThat(reply.output()).contains("worth 90.00 EUR");
    }

    @Test
    void theQueueLeavesOutWhatIsAlreadySettled() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 7, 29), "taken");
            work.commit();
        }

        assertThat(runner.run("report", "queue", "--on", TODAY).output()).contains("nothing open");
    }

    @Test
    void aSmallerOneAboutToExpireComesFirst() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow near = aWatch(work, "FRA-NYC-2025-08-02-Y", LocalDate.of(2025, 8, 2));
            WatchRow far = aWatch(work, "FRA-NYC-2026-03-01-Y", LocalDate.of(2026, 3, 1));
            aRecommendation(work, near, LocalDate.of(2025, 7, 28), "open", "90.00");
            aRecommendation(work, far, LocalDate.of(2025, 7, 30), "open", "200.00");
            work.commit();
        }

        String out = runner.run("report", "queue", "--on", TODAY).output();
        assertThat(out).contains("90.00 EUR", "200.00 EUR");
        assertThat(out.indexOf("90.00 EUR")).isLessThan(out.indexOf("200.00 EUR"));
    }

    @Test
    void theUrgentFlagNarrowsTheList() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            WatchRow far = aWatch(work, "FRA-NYC-2026-03-01-Y", LocalDate.of(2026, 3, 1));
            aRecommendation(work, far, LocalDate.of(2025, 7, 30), "open", "200.00");
            work.commit();
        }

        assertThat(runner.run("report", "queue", "--on", TODAY, "--urgent").output())
                .contains("nothing open");
    }

    @Test
    void aCarrierWithOneFilingCannotBeCompared() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aFiling(work, "week01.dat", LocalDateTime.of(2025, 7, 1, 6, 0), Map.of("KLWAP7RT", "420.00"));
            work.commit();
        }
        Shell.Result reply = runner.run("report", "filing", "LH");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(NotFoundError.class);
    }

    @Test
    void aCarrierNobodyHasFiledForCannotBeCompared() {
        assertThat(runner.run("report", "filing", "UA").code()).isNotZero();
    }

    @Test
    void aFilingThatMovedAPriceReportsTheFall() {
        twoFilings(Map.of("KLWAP7RT", "380.00"));
        Shell.Result reply = runner.run("report", "filing", "LH");
        assertThat(reply.output()).contains("reprice these");
        assertThat(reply.output()).contains("420.00 EUR to 380.00 EUR");
    }

    @Test
    void aFilingWithAFallInItExitsOne() {
        twoFilings(Map.of("KLWAP7RT", "380.00"));
        assertThat(runner.run("report", "filing", "LH").code()).isEqualTo(1);
    }

    @Test
    void aFilingThatOnlyGrewExitsZero() {
        Map<String, String> grown = new LinkedHashMap<>();
        grown.put("KLWAP7RT", "420.00");
        grown.put("MLWAP7RT", "500.00");
        twoFilings(grown);
        Shell.Result reply = runner.run("report", "filing", "LH");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("added      1");
    }

    @Test
    void aWithdrawnFareExitsOneAndSaysItStillPrices() {
        twoFilings(Map.of("MLWAP7RT", "500.00"));
        Shell.Result reply = runner.run("report", "filing", "LH");
        assertThat(reply.code()).isEqualTo(1);
        assertThat(reply.output()).contains("still price and should not");
    }

    @Test
    void theReportSaysWhichTwoFilesItRead() {
        twoFilings(Map.of("KLWAP7RT", "420.00"));
        assertThat(runner.run("report", "filing", "LH").output()).contains("week01.dat to week02.dat");
    }

    @Test
    void theNewestFilingIsTheOneReadAgainst() {
        // Three files in, and the question somebody has on the morning the third lands is about the third
        // and the second.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aFiling(work, "week01.dat", LocalDateTime.of(2025, 7, 1, 6, 0), Map.of("KLWAP7RT", "300.00"));
            aFiling(work, "week02.dat", LocalDateTime.of(2025, 7, 8, 6, 0), Map.of("KLWAP7RT", "420.00"));
            aFiling(work, "week03.dat", LocalDateTime.of(2025, 7, 15, 6, 0), Map.of("KLWAP7RT", "410.00"));
            work.commit();
        }
        assertThat(runner.run("report", "filing", "LH").output()).contains("week02.dat to week03.dat");
    }

    @Test
    void aNamedFilingIsReadAgainstInstead() {
        int against;
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            LoadRow first =
                    aFiling(work, "week01.dat", LocalDateTime.of(2025, 7, 1, 6, 0), Map.of("KLWAP7RT", "300.00"));
            aFiling(work, "week02.dat", LocalDateTime.of(2025, 7, 8, 6, 0), Map.of("KLWAP7RT", "420.00"));
            aFiling(work, "week03.dat", LocalDateTime.of(2025, 7, 15, 6, 0), Map.of("KLWAP7RT", "410.00"));
            work.commit();
            against = first.getId();
        }
        Shell.Result reply = runner.run("report", "filing", "LH", "--against", String.valueOf(against));
        assertThat(reply.output()).contains("week01.dat to week03.dat");
        assertThat(reply.output()).contains("300.00 EUR to 410.00 EUR");
    }

    @Test
    void aFilingIdThatIsNotThisCarrierIsANotFound() {
        twoFilings(Map.of("KLWAP7RT", "420.00"));
        Shell.Result reply = runner.run("report", "filing", "LH", "--against", "999");
        assertThat(reply.failure()).containsInstanceOf(NotFoundError.class);
    }

    @Test
    void aRuleChangeAtTheSamePriceIsReportedAsAnAmendment() {
        twoFilings(Map.of("KLWAP7RT", "420.00"), "0200");
        Shell.Result reply = runner.run("report", "filing", "LH");
        assertThat(reply.output()).contains("same price, different terms");
        assertThat(reply.output()).contains("rule number 0100 to 0200");
        assertThat(reply.code()).isZero();
    }

    @Test
    void theListsStopWhereTheyAreToldTo() {
        String[] bases = {"K000", "K001", "K002", "K003", "K004", "K005"};
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aFiling(work, "week01.dat", LocalDateTime.of(2025, 7, 1, 6, 0), each("100.00", bases));
            aFiling(work, "week02.dat", LocalDateTime.of(2025, 7, 8, 6, 0), each("90.00", bases));
            work.commit();
        }
        Shell.Result reply = runner.run("report", "filing", "LH", "--show", "2");
        assertThat(reply.output()).contains("showing 2 of 6");
    }

    @Test
    void reliabilitySaysWhenARunNeverFinished() {
        // Nothing else in the report can see it. Every supplier above is graded on the calls that did
        // come back.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            work.runs().open(LocalDateTime.of(2025, 7, 29, 22, 0));
            work.commit();
        }

        Shell.Result reply = runner.run("report", "reliability");
        assertThat(reply.output()).contains("never finished");
        assertThat(reply.code()).isEqualTo(1);
    }

    @Test
    void reliabilityCountsTheRunsThatNeverFinished() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            for (int hour = 0; hour < 3; hour++) {
                work.runs().open(LocalDateTime.of(2025, 7, 29, 20 + hour, 0));
            }
            work.commit();
        }

        assertThat(runner.run("report", "reliability").output()).contains("3 runs started and never finished");
    }

    @Test
    void reliabilitySaysNothingAboutFinishedRuns() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            RunRow row = work.runs().open(LocalDateTime.of(2025, 7, 29, 22, 0));
            work.runs()
                    .close(
                            row,
                            new RunReport(LocalDateTime.of(2025, 7, 29, 22, 0)),
                            LocalDateTime.of(2025, 7, 29, 23, 0));
            work.commit();
        }

        assertThat(runner.run("report", "reliability").output()).doesNotContain("never finished");
    }
}
