package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.gussetline.collect.Archive;
import io.github.rkaushikdev.gussetline.collect.Response;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.desk.Coupon;
import io.github.rkaushikdev.gussetline.desk.Ticket;
import io.github.rkaushikdev.gussetline.load.FareFeed;
import io.github.rkaushikdev.gussetline.load.RuleFeed;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.DeskMap;
import io.github.rkaushikdev.gussetline.persistence.Rows;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.ParameterException;

/**
 * A week on the desk, driven entirely through the command line.
 *
 * <p>Every other test in this suite reaches into a package. This one does not: it loads a tariff file,
 * adds a watch, replays five days of collection, runs the night, and reads the reports, all through the
 * same commands the cron job calls, against one database on disk.
 *
 * <p>That is the point. Every part below works and is tested, and the ways a desk actually breaks are
 * still all in the joins between them: a market key folded one way in the schedule and another in the
 * registry, a fare looked up by airport where the tariff files cities, a mileage measured from a point
 * with no coordinates. Three of those got through the unit tests and were caught here or by the command
 * that exercises the same join.
 *
 * <p>The week is deliberately dull. Nothing exotic happens: one ticket, one market, a fare that drops
 * on the Wednesday and holds. If this test ever needs an unusual itinerary to be interesting, the
 * interesting thing belongs in a unit test and not here.
 */
class AWeekOnTheDeskTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 9, 20);
    private static final String SERIES = "FRA-NYC-2025-09-20-Y";
    private static final String TICKET = "220-1234567890";

    /** What the market did over the week. Steady, then a drop that holds. */
    private static final Map<LocalDate, String> PRICES = prices();

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

    private static Map<LocalDate, String> prices() {
        Map<LocalDate, String> prices = new LinkedHashMap<>();
        prices.put(LocalDate.of(2025, 8, 4), "840.00");
        prices.put(LocalDate.of(2025, 8, 5), "840.00");
        prices.put(LocalDate.of(2025, 8, 6), "690.00");
        prices.put(LocalDate.of(2025, 8, 7), "690.00");
        prices.put(LocalDate.of(2025, 8, 8), "690.00");
        return prices;
    }

    /**
     * Run one command line, the way cron does. A refusal of the desk's own, or anything else that is not
     * a usage error or a command declining to go on, fails the test right here rather than three
     * assertions later.
     */
    private static Shell.Result run(Shell shell, String... args) {
        Shell.Result reply = shell.run(args);
        reply.failure().ifPresent(failure -> {
            if (!(failure instanceof CommandError) && !(failure instanceof ParameterException)) {
                throw failure;
            }
        });
        return reply;
    }

    private Shell.Result run(String... args) {
        return run(runner, args);
    }

    private String fareFile() {
        Map<String, Object> fare = new LinkedHashMap<>();
        fare.put("carrier", "LH");
        fare.put("origin", "FRA");
        fare.put("destination", "JFK");
        fare.put("fare_basis", "KLWAP7RT");
        fare.put("currency", "EUR");
        fare.put("amount", new BigDecimal("420.00"));
        fare.put("owrt", "2");
        fare.put("global_indicator", "AT");
        fare.put("rule_number", "0100");
        fare.put("routing_number", "0011");
        fare.put("tariff_number", "001");
        fare.put("footnote", null);
        fare.put("cabin", "Y");
        fare.put("fare_type", "P");
        fare.put("effective", LocalDate.of(2025, 1, 1));
        fare.put("discontinue", LocalDate.of(2025, 12, 31));
        fare.put("first_travel", LocalDate.of(2025, 1, 1));
        fare.put("last_travel", LocalDate.of(2025, 12, 31));
        fare.put("action", "A");
        List<String> rows = List.of(
                FareFeed.HEADER_RECORD.write(Map.of("carrier", "LH", "filed_on", MONDAY, "sequence", 1)),
                FareFeed.FARE_RECORD.write(fare));
        return write("lh-fares.dat", rows);
    }

    private String ruleFile() {
        Map<String, Object> penalty = new LinkedHashMap<>();
        penalty.put("action", "C");
        penalty.put("permitted", "Y");
        penalty.put("currency", "EUR");
        penalty.put("before", new BigDecimal("50.00"));
        penalty.put("after", new BigDecimal("100.00"));
        penalty.put("applies", "T");
        penalty.put("no_show_forfeits", null);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("permitted", "Y");
        changes.put("basis", "C");
        changes.put("residual", "R");
        changes.put("keep_breaks", null);
        changes.put("same_carrier", null);
        changes.put("once_only", null);
        List<String> rows = List.of(
                RuleFeed.RULE_HEADER.write(Map.of("carrier", "LH", "number", "0100", "tariff", "001", "action", "A")),
                RuleFeed.PENALTY.write(penalty),
                RuleFeed.CHANGES.write(changes));
        return write("lh-rules.dat", rows);
    }

    private String write(String name, List<String> rows) {
        Path path = home.resolve(name);
        try {
            Files.writeString(path, String.join("\n", rows), StandardCharsets.US_ASCII);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
        return path.toString();
    }

    /** One ticket, put in the way the ticketing feed would put it. */
    private void aTicket() {
        Ticket ticket = new Ticket(
                TICKET,
                "ABC123",
                "MUELLER/ANNA",
                List.of(new Coupon(
                        0, "LH", "400", "FRA", "JFK", LocalDateTime.of(2025, 9, 20, 10, 25), "K", "KLWAP7RT")),
                LocalDate.of(2025, 5, 2),
                "LH",
                Money.of("840.00", "EUR"),
                Money.of("1090.83", "EUR"));
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            work.session().persist(DeskMap.ticketToRow(ticket));
            work.combinability()
                    .save(Combinability.builder("LH", "KLWAP7RT")
                            .roundTrip(true)
                            .build());
            work.commit();
        }
    }

    /** What the offer feed sends, in the shape its own parser reads. */
    private static byte[] payloadFor(String amount) {
        String price = "{\"amount\": \"" + amount + "\", \"currency\": \"EUR\"}";
        return ("{\"offers\": [{\"id\": \"1\", \"price\": {\"baseAmount\": " + price + ", \"totalAmount\": " + price
                        + "}, \"fareBasis\": [\"KLWAP7RT\"], \"validatingCarrier\": \"LH\", \"journeys\": [{\"segments\":"
                        + " [{\"marketingCarrier\": \"LH\", \"flightNumber\": \"400\", \"origin\": \"FRA\","
                        + " \"destination\": \"JFK\", \"departureTime\": \"2025-09-20T10:25:00\", \"arrivalTime\":"
                        + " \"2025-09-20T13:05:00\", \"bookingClass\": \"K\", \"cabin\": \"Y\"}]}]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Five days of payloads, so the week can be replayed without a network. */
    private void stockTheArchive() {
        Archive archive = new Archive(home.resolve("raw"));
        PRICES.forEach((day, amount) -> archive.put(
                payloadFor(amount),
                "ndc",
                "/v2/offers",
                day.atTime(6, 0),
                Map.of("market", "FRA-NYC", "departure", DEPARTURE.toString())));
    }

    private List<Money> openRecommendations() {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            return work
                    .session()
                    .createQuery("select r from RecommendationRow r where r.state = 'open'", RecommendationRow.class)
                    .getResultList()
                    .stream()
                    .map(row -> new Money(row.getBenefitAmount(), row.getCurrency()))
                    .toList();
        }
    }

    private List<WatchRow> watches() {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            return work.session()
                    .createQuery("select w from WatchRow w order by w.id", WatchRow.class)
                    .getResultList();
        }
    }

    /** Everything up to the Friday night, the collection replayed for each day of the week. */
    private void theWeekUpToFriday() {
        run("create-schema");
        run("load", "fares", fareFile());
        run("load", "rules", ruleFile());
        aTicket();
        run("watch", "add", TICKET, "--on", MONDAY.toString());
        stockTheArchive();
        for (int offset = 0; offset < 5; offset++) {
            run("collect", "replay", "--on", MONDAY.plusDays(offset).toString());
        }
    }

    @Test
    void aWeekOnTheDesk() {
        // Monday morning. Nothing is set up.
        assertThat(run("create-schema").output()).contains("schema built");

        // The tariff arrives and is loaded.
        assertThat(run("load", "fares", fareFile()).output()).contains("loaded 1 fares");
        assertThat(run("load", "rules", ruleFile()).output()).contains("kept 1 rules");

        // The ticketing feed puts a ticket in, and the desk starts watching it.
        aTicket();
        Shell.Result added = run("watch", "add", TICKET, "--on", MONDAY.toString());
        assertThat(added.output()).contains("watching " + SERIES);
        // The threshold came off the filed change fee and not off a default.
        assertThat(added.output()).contains("a drop has to beat 75.00 EUR");

        // Five mornings of collection, replayed out of the archive so the test never touches a supplier.
        stockTheArchive();
        for (int offset = 0; offset < 5; offset++) {
            String day = MONDAY.plusDays(offset).toString();
            assertThat(run("collect", "replay", "--on", day).output()).contains("1 tasks");
        }

        // The desk polls for real on the Friday and keeps the run.
        collectForReal();

        // Friday night. The drop has held since Wednesday, so it is worth telling somebody about.
        Shell.Result night = run("night", "--on", "2025-08-08");
        assertThat(night.output()).contains("raised 1 worth 100.00 EUR");
        assertThat(openRecommendations()).containsExactly(Money.of("100.00", "EUR"));

        // Saturday morning, the three reports.
        Shell.Result savings = run("report", "savings", "--since", "2025-08-01", "--until", "2025-08-09");
        assertThat(savings.output()).contains("found      100.00 EUR");
        assertThat(savings.output()).contains("taken      0 EUR");
        assertThat(savings.output()).contains("open       1");

        Shell.Result reliability = run("report", "reliability");
        assertThat(reliability.output()).contains("ndc        sound");

        Shell.Result coverage = run("report", "coverage", "--on", "2025-08-09");
        assertThat(coverage.output()).contains("priced    1");
        assertThat(coverage.output()).contains("rate      1.00");

        // An agent takes it, and the savings report moves.
        takeIt();
        Shell.Result settled = run("report", "savings", "--since", "2025-08-01", "--until", "2025-08-09");
        assertThat(settled.output()).contains("taken      100.00 EUR");
        assertThat(settled.output()).contains("take rate  1.00 over 1 decided");
    }

    /**
     * One real run, so the reliability report has something to read.
     *
     * <p>Replays deliberately do not write a run: nothing was asked of anybody, and a reliability report
     * built over them would say a supplier answered on a day it was never called. So the week has to
     * make one call that counts, and what is stubbed is the socket rather than the run: the command line
     * is wired with a fetch that answers every supplier with the Friday payload, and every other part of
     * the run is what cron would run.
     */
    private void collectForReal() {
        Wiring stubbed = Wiring.standard()
                .withLiveFetch(rig -> rig.liveFetch(supplier ->
                        (path, params) -> new Response(200, payloadFor("690.00"), Map.of(), 1, 0, "/v2/offers")));
        Shell.Result reply = runner.wiredWith(stubbed).run("collect", "run", "--on", "2025-08-08");
        assertThat(reply.code()).as(reply.output()).isZero();
        assertThat(reply.output()).contains("kept as run");
    }

    /** An agent settles the open recommendation, the way the service does. */
    private void takeIt() {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            RecommendationRow row = Rows.oneOrNone(work.session()
                            .createQuery(
                                    "select r from RecommendationRow r where r.state = 'open'",
                                    RecommendationRow.class))
                    .orElseThrow();
            row.setState("taken");
            row.setDecidedOn(LocalDate.of(2025, 8, 9));
            row.setDecidedBy("anna");
            work.commit();
        }
    }

    @Test
    void theWeekLeavesTheWatchLiveBecauseNobodyReissuedYet() {
        theWeekUpToFriday();
        run("night", "--on", "2025-08-08");

        assertThat(watches())
                .extracting(WatchRow::isActive, WatchRow::getRetiredReason)
                .containsExactly(tuple(true, ""));
    }

    @Test
    void theSameNightRunTwiceDoesNotRaiseTheNumberTwice() {
        theWeekUpToFriday();

        run("night", "--on", "2025-08-08");
        Shell.Result second = run("night", "--on", "2025-08-08");
        assertThat(second.output()).contains("wrote 0 recommendations");
        assertThat(openRecommendations()).hasSize(1);
    }

    @Test
    void theDeskReportsWhatItHoldsAfterTheWeek() {
        run("create-schema");
        run("load", "fares", fareFile());
        run("load", "rules", ruleFile());
        aTicket();
        run("watch", "add", TICKET, "--on", MONDAY.toString());
        stockTheArchive();
        run("collect", "replay", "--on", MONDAY.toString());

        Shell.Result check = run("check");
        assertThat(check.output()).contains("fares         1");
        assertThat(check.output()).contains("combinability 1");
        assertThat(check.output()).contains("observations  1");
    }

    @Test
    void aRuleThatForbidsAChangeEndsTheWeekWithARetiredWatch() {
        run("create-schema");
        run("load", "fares", fareFile());
        aTicket();

        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            work.rules()
                    .save(new RuleSet(
                            "LH",
                            "0100",
                            List.of(
                                    VoluntaryChange.builder()
                                            .permitted(false)
                                            .basis(RepricingBasis.CURRENT)
                                            .residual(Residual.REFUNDABLE)
                                            .build(),
                                    new Penalty(Action.CHANGE, Money.of("50.00", "EUR"))),
                            "1"));
            work.commit();
        }

        run("watch", "add", TICKET, "--on", MONDAY.toString());
        stockTheArchive();
        for (int offset = 0; offset < 5; offset++) {
            run("collect", "replay", "--on", MONDAY.plusDays(offset).toString());
        }

        Shell.Result night = run("night", "--on", "2025-08-08");
        assertThat(night.output()).contains("does not permit a change");
        assertThat(openRecommendations()).isEmpty();

        assertThat(watches()).extracting(WatchRow::isActive).containsExactly(false);
    }
}
