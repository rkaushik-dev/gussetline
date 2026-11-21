package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectRunTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 5, 28);
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 5, 28, 9, 0);

    @TempDir
    Path tmp;

    private final ManualClock clock = new ManualClock();
    private Collector collector;

    @BeforeEach
    void assemble() {
        collector = new Collector(
                Registry.defaultRegistry(),
                new Limiter(clock, Limiter.RATE, 10, 100),
                new BreakerBoard(clock, 2, Breaker.COOLDOWN),
                new Archive(tmp.resolve("raw")),
                () -> NOW);
    }

    /** A collector of its own, with the usual breaker and a burst and quota of its own. */
    private Collector collectorWith(int burst, int quota) {
        return new Collector(
                Registry.defaultRegistry(),
                new Limiter(clock, Limiter.RATE, burst, quota),
                new BreakerBoard(clock),
                new Archive(tmp.resolve("raw")),
                () -> NOW);
    }

    private static Offer anOffer() {
        return anOffer("490.83");
    }

    private static Offer anOffer(String amount) {
        return new Offer(
                "gds",
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-06-04 10:25", "2025-06-04 13:05"))),
                new PriceBreakdown(Money.of(amount, "EUR")),
                List.of("KLWAP7RT"),
                NOW);
    }

    private static Map<String, Adapter> parsers() {
        return parsers(List.of(anOffer()));
    }

    private static Map<String, Adapter> parsers(List<Offer> offers) {
        Adapter parse = (payload, seen) -> new Harvest(offers, List.of(), List.of(), "gds");
        return Map.of("gds", parse, "ndc", parse);
    }

    private static Map<String, Adapter> raising(GussetlineError failure) {
        Adapter parse = (payload, seen) -> {
            throw failure;
        };
        return Map.of("gds", parse, "ndc", parse);
    }

    /** A schedule following FRA to JFK on consecutive days from 4 June, and today's plan for it. */
    private record Planned(Schedule schedule, List<Task> tasks) {}

    private static Planned plan(int count) {
        Schedule schedule = new Schedule();
        for (int day = 0; day < count; day++) {
            schedule.follow(new Market("FRA", "JFK"), LocalDate.of(2025, 6, 4 + day));
        }
        return new Planned(schedule, schedule.plan(TODAY, "gds", count));
    }

    private static Fetch answering() {
        return answering(bytes("payload"));
    }

    private static Fetch answering(byte[] body) {
        return answering(body, 200);
    }

    private static Fetch answering(byte[] body, int status) {
        return (supplier, task) -> new Response(status, body, Map.of(), 1, 0, supplier.path());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    private static Series fraJfkOnTheFourth(Schedule schedule) {
        Optional<Series> series = schedule.get(new Market("FRA", "JFK"), LocalDate.of(2025, 6, 4));
        assertThat(series).isPresent();
        return series.get();
    }

    @Test
    void aCleanRunRecordsEveryTask() {
        List<Task> tasks = plan(3).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), parsers());
        assertThat(report.size()).isEqualTo(3);
        assertThat(report.byOutcome()).isEqualTo(Map.of("ok", 3));
        assertThat(report.offers()).isEqualTo(3);
        assertThat(report.calls()).isEqualTo(3);
    }

    @Test
    void thePayloadIsArchivedBeforeItIsParsed() {
        List<Task> tasks = plan(1).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(bytes("the bytes")), parsers());
        String digest = report.results().get(0).digest();
        assertThat(collector.archive().get(digest)).isEqualTo(bytes("the bytes"));
    }

    @Test
    void theMarketAndDateRideAlongOnTheArchiveRow() {
        List<Task> tasks = plan(1).tasks();
        collector.run(tasks, TODAY, answering(), parsers());
        Map<String, Object> row = collector.archive().manifest().get(0);
        assertThat(row.get("context")).isEqualTo(Map.of("market", "FRA-NYC", "departure", "2025-06-04"));
    }

    @Test
    void aPayloadWeAlreadyHoldIsMarkedNotFresh() {
        List<Task> tasks = plan(2).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(bytes("same")), parsers());
        assertThat(report.results()).extracting(Result::fresh).containsExactly(true, false);
    }

    @Test
    void aTransportFailureIsRecordedAndCountsAgainstTheBreaker() {
        Fetch failing = (supplier, task) -> {
            throw new SupplierUnavailable("read timeout");
        };
        List<Task> tasks = plan(3).tasks();
        RunReport report = collector.run(tasks, TODAY, failing, parsers());
        // Two failures trip the breaker, so the third task never gets a call.
        assertThat(report.byOutcome()).isEqualTo(Map.of("transient", 2, "blocked", 1));
        assertThat(report.skippedBreaker()).isEqualTo(1);
    }

    @Test
    void aBlockedSupplierIsRecordedAsBlocked() {
        Fetch refused = (supplier, task) -> {
            throw new SupplierBlocked("403 forever");
        };
        List<Task> tasks = plan(1).tasks();
        RunReport report = collector.run(tasks, TODAY, refused, parsers());
        assertThat(report.results().get(0).outcome()).isSameAs(Outcome.BLOCKED);
    }

    @Test
    void aNonTwoHundredAnswerIsArchivedAndCountedAsTransient() {
        List<Task> tasks = plan(1).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(bytes("gateway timeout"), 504), parsers());
        Result result = report.results().get(0);
        assertThat(result.outcome()).isSameAs(Outcome.TRANSIENT);
        assertThat(result.reason()).contains("status 504");
        assertThat(collector.archive().get(result.digest())).isEqualTo(bytes("gateway timeout"));
    }

    @Test
    void aParseFailureDoesNotCountAgainstTheBreaker() {
        // The supplier answered. It is our reader that could not cope, and tripping the breaker on
        // that would stop a working supplier.
        List<Task> tasks = plan(3).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), raising(new MalformedPayload("not json")));
        assertThat(report.byOutcome()).isEqualTo(Map.of("malformed", 3));
        assertThat(report.skippedBreaker()).isZero();
    }

    @Test
    void aSupplierWithNoParserIsRefusedRatherThanCrashing() {
        List<Task> tasks = plan(1).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), Map.of());
        assertThat(report.results().get(0).outcome()).isSameAs(Outcome.REFUSED);
        assertThat(report.results().get(0).reason()).isEqualTo("no parser registered");
    }

    @Test
    void theQuotaStopsTheRunAndSaysSo() {
        Collector small = collectorWith(10, 2);
        List<Task> tasks = plan(4).tasks();
        RunReport report = small.run(tasks, TODAY, answering(), parsers());
        assertThat(report.byOutcome()).isEqualTo(Map.of("ok", 2, "budget", 2));
        assertThat(report.skippedBudget()).isEqualTo(2);
        assertThat(report.results().get(2).reason()).isEqualTo("quota");
    }

    @Test
    void theRateLimitStopsABurstAndSaysSo() {
        Collector narrow = collectorWith(1, 100);
        List<Task> tasks = plan(3).tasks();
        RunReport report = narrow.run(tasks, TODAY, answering(), parsers());
        assertThat(report.byOutcome()).isEqualTo(Map.of("ok", 1, "budget", 2));
        assertThat(report.results().get(1).reason()).isEqualTo("rate");
    }

    @Test
    void aSkippedTaskNeverReachesTheSupplier() {
        List<Task> seen = new ArrayList<>();
        Fetch counting = (supplier, task) -> {
            seen.add(task);
            return new Response(200, bytes("payload"), Map.of(), 1, 0, supplier.path());
        };
        collector.breakers().forSupplier("gds").failed();
        collector.breakers().forSupplier("gds").failed();
        List<Task> tasks = plan(2).tasks();
        collector.run(tasks, TODAY, counting, parsers());
        assertThat(seen).isEmpty();
    }

    @Test
    void theScheduleLearnsTheCheapestPriceFromTheRun() {
        Planned planned = plan(1);
        collector.run(
                planned.tasks(),
                TODAY,
                answering(),
                parsers(List.of(anOffer("490.83"), anOffer("420.00"))),
                planned.schedule());
        Series series = fraJfkOnTheFourth(planned.schedule());
        assertThat(series.lastAmount()).isEqualTo("420.00 EUR");
        assertThat(series.lastPolled()).contains(TODAY);
    }

    @Test
    void aSecondRunAtTheSamePriceMakesTheSeriesQuiet() {
        Planned planned = plan(1);
        collector.run(planned.tasks(), TODAY, answering(), parsers(), planned.schedule());
        collector.run(planned.tasks(), LocalDate.of(2025, 5, 30), answering(), parsers(), planned.schedule());
        assertThat(fraJfkOnTheFourth(planned.schedule()).quietPolls()).isEqualTo(1);
    }

    @Test
    void aRunWithNoOffersLeavesTheSeriesPriceAlone() {
        Planned planned = plan(1);
        collector.run(planned.tasks(), TODAY, answering(), parsers(), planned.schedule());
        collector.run(planned.tasks(), LocalDate.of(2025, 5, 30), answering(), parsers(List.of()), planned.schedule());
        assertThat(fraJfkOnTheFourth(planned.schedule()).lastAmount()).isEqualTo("490.83 EUR");
    }

    @Test
    void theRunCostsWhatTheCallsThatWentOutCost() {
        List<Task> tasks = plan(2).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), parsers());
        assertThat(report.cost(collector.registry())).isEqualByComparingTo("0.08");
    }

    @Test
    void aSkippedTaskCostsNothing() {
        Collector small = collectorWith(10, 1);
        List<Task> tasks = plan(3).tasks();
        RunReport report = small.run(tasks, TODAY, answering(), parsers());
        assertThat(report.cost(small.registry())).isEqualByComparingTo("0.04");
    }

    @Test
    void failuresCanBeListedOnTheirOwn() {
        List<Task> tasks = plan(2).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), raising(new MalformedPayload("not json")));
        assertThat(report.failures()).hasSize(2);
    }

    @Test
    void aResultReadsBackInOneLine() {
        List<Task> tasks = plan(1).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), parsers());
        assertThat(report.results().get(0)).hasToString("gds FRA-NYC 2025-06-04 1 offers");
    }

    @Test
    void aFailedResultReadsBackWithItsReason() {
        Task task = new Task("gds", new Market("FRA", "JFK"), LocalDate.of(2025, 6, 4), 7);
        assertThat(new Result(task, Outcome.BUDGET, "quota").toString()).endsWith("budget quota");
    }

    @Test
    void aReportReadsBackWithItsCounts() {
        List<Task> tasks = plan(2).tasks();
        RunReport report = collector.run(tasks, TODAY, answering(), parsers());
        assertThat(report).hasToString("2 tasks, 2 offers, 0 skipped on the breaker, 0 on the budget");
    }

    @Test
    void anEmptyRunIsAReportWithNothingInIt() {
        RunReport report = collector.run(List.of(), TODAY, answering(), parsers());
        assertThat(report.size()).isZero();
        assertThat(report.byOutcome()).isEmpty();
        assertThat(report.cost(collector.registry())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aReportKnowsWhenItStarted() {
        RunReport report = new RunReport(NOW);
        assertThat(report.started()).isEqualTo(NOW);
    }
}
