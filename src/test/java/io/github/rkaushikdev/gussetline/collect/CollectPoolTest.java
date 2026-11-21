package io.github.rkaushikdev.gussetline.collect;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.config.Settings;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectPoolTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 10, 6);
    private static final LocalDateTime SIX = LocalDateTime.of(2025, 10, 6, 6, 0);
    private static final Market MUC_ORD = new Market("MUC", "ORD");

    @TempDir
    Path tmp;

    private Rig rig;

    @BeforeEach
    void assemble() {
        // A burst wide enough that the token bucket is never what stops a run. The clock is frozen,
        // so the bucket never refills and a narrow burst would make every test here a test of the
        // burst.
        rig = rigWith(settings().build());
    }

    private Settings.Builder settings() {
        return Settings.builder()
                .archiveRoot(tmp.resolve("raw"))
                .dailyQuota(1000)
                .ratePerSecond(1000.0)
                .burst(200);
    }

    private static Rig rigWith(Settings settings) {
        return Rig.builder(settings).clock(new ManualClock()).now(() -> SIX).build();
    }

    private static Task task() {
        return task("gds", new Market("FRA", "JFK"), 1);
    }

    private static Task task(int day) {
        return task("gds", new Market("FRA", "JFK"), day);
    }

    private static Task task(String supplier, Market market) {
        return task(supplier, market, 1);
    }

    private static Task task(String supplier, Market market, int day) {
        return new Task(supplier, market, LocalDate.of(2025, 11, day), 35);
    }

    private static List<Task> days(int first, int last) {
        return IntStream.rangeClosed(first, last)
                .mapToObj(CollectPoolTest::task)
                .toList();
    }

    private static Harvest parses(byte[] body, LocalDateTime seen) {
        return Harvest.nothing("read", "test");
    }

    private static final Map<String, Adapter> GDS = Map.of("gds", CollectPoolTest::parses);

    private static Fetch answering() {
        return answering(0.0);
    }

    private static Fetch answering(double delay) {
        return (supplier, task) -> {
            pause(delay);
            return answer(supplier);
        };
    }

    private static Response answer(Supplier supplier) {
        return new Response(200, "{}".getBytes(UTF_8), Map.of(), 1, 0, supplier.path());
    }

    private static Fetch failing() {
        return (supplier, task) -> {
            throw new SupplierUnavailable("the far end is down", Details.of("supplier", supplier.name()));
        };
    }

    /** Wait on the real clock, as a supplier at the far end of a network would make a worker wait. */
    private static void pause(double seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofNanos((long) (seconds * 1e9)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aRunWithNothingInItDoesNothing() {
        RunReport report = new Pool().run(rig.collector(), List.of(), TODAY, answering(), GDS);
        assertThat(report.size()).isZero();
    }

    @Test
    void everyTaskComesBack() {
        RunReport report = new Pool().run(rig.collector(), days(1, 7), TODAY, answering(), GDS);
        assertThat(report.size()).isEqualTo(7);
        assertThat(report.byOutcome()).as("failures %s", report.failures()).isEqualTo(Map.of("ok", 7));
    }

    @Test
    void theResultsComeBackInTheOrderThePlanAskedFor() {
        // A report read next to yesterday's cannot shuffle its own rows.
        RunReport report = new Pool().run(rig.collector(), days(1, 7), TODAY, answering(0.005), GDS);
        assertThat(report.results())
                .extracting(result -> result.task().departure().getDayOfMonth())
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void theSamePlanGivesTheSameReportTwice() {
        List<Task> tasks = days(1, 5);
        RunReport once = new Pool().run(rig.collector(), tasks, TODAY, answering(), GDS);
        RunReport again = new Pool().run(rig.collector(), tasks, TODAY, answering(), GDS);
        assertThat(once.results())
                .extracting(result -> result.task().departure())
                .isEqualTo(again.results().stream()
                        .map(result -> result.task().departure())
                        .toList());
    }

    @Test
    void oneHostIsNeverAskedMoreThanTheCapAtOnce() {
        // What a supplier means when they say they blocked us for aggressive polling.
        Object lock = new Object();
        int[] live = {0};
        int[] most = {0};
        Fetch fetch = (supplier, task) -> {
            synchronized (lock) {
                live[0]++;
                most[0] = Math.max(most[0], live[0]);
            }
            pause(0.01);
            synchronized (lock) {
                live[0]--;
            }
            return answer(supplier);
        };
        new Pool(8, 2).run(rig.collector(), days(1, 9), TODAY, fetch, GDS);
        synchronized (lock) {
            assertThat(most[0]).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void twoHostsAreAskedAtTheSameTime() {
        // Two companies, neither of which knows about the other.
        Map<String, Integer> live = new ConcurrentHashMap<>();
        AtomicBoolean together = new AtomicBoolean();
        Object lock = new Object();
        Fetch fetch = (supplier, task) -> {
            synchronized (lock) {
                live.merge(supplier.host(), 1, Integer::sum);
                if (live.size() > 1) {
                    together.set(true);
                }
            }
            pause(0.02);
            return answer(supplier);
        };
        List<Task> tasks = List.of(task(), task("calendar", MUC_ORD));
        new Pool(4, 1)
                .run(
                        rig.collector(),
                        tasks,
                        TODAY,
                        fetch,
                        Map.of("gds", CollectPoolTest::parses, "calendar", CollectPoolTest::parses));
        assertThat(together).isTrue();
    }

    @Test
    void theBudgetIsSpentOncePerCallAndNotMore() {
        // A read, a subtraction and a write. Six workers doing that at once spend a budget of a
        // thousand about eleven hundred times.
        Rig small = rigWith(settings().dailyQuota(5).build());
        RunReport report = new Pool().run(small.collector(), days(1, 20), TODAY, answering(0.002), GDS);
        assertThat(report.results()).filteredOn(Result::ok).hasSize(5);
        assertThat(report.skippedBudget()).isEqualTo(15);
    }

    @Test
    void aBreakerThatOpensStopsTheWorkersThatHaveNotGoneYet() {
        // The whole point of a breaker is that the fifth failure stops the sixth call, and six workers
        // each holding their own count never reach five.
        Rig made = rigWith(settings().breakerThreshold(3).build());
        RunReport report = new Pool(1, 1).run(made.collector(), days(1, 20), TODAY, failing(), GDS);
        assertThat(report.skippedBreaker()).isPositive();
        assertThat(report.byOutcome().get(Outcome.BLOCKED.value())).isEqualTo(report.skippedBreaker());
    }

    @Test
    void aPoolWithNoWorkersIsRefused() {
        assertThatThrownBy(
                        () -> new Pool(0, Pool.PER_HOST).run(rig.collector(), List.of(task()), TODAY, answering(), GDS))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least one worker");
    }

    @Test
    void aPoolWithNoRoomPerHostIsRefused() {
        assertThatThrownBy(
                        () -> new Pool(Pool.WORKERS, 0).run(rig.collector(), List.of(task()), TODAY, answering(), GDS))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least one worker");
    }

    @Test
    void aPlanIsGroupedByTheHostEachTaskGoesTo() {
        Pool.Plan plan = Pool.planByHost(rig.registry(), List.of(task(), task("calendar", MUC_ORD)));
        assertThat(plan.hosts()).containsExactly("fares.example", "gateway.example");
        assertThat(plan.size()).isEqualTo(2);
    }

    @Test
    void aPlanKnowsHowManyWorkersItCouldKeepBusy() {
        // More than this and the extra threads sit on a semaphore doing nothing.
        Pool.Plan plan = Pool.planByHost(rig.registry(), days(1, 9));
        assertThat(Pool.widest(plan, 2)).isEqualTo(2);

        Pool.Plan both = Pool.planByHost(rig.registry(), List.of(task(), task("calendar", MUC_ORD)));
        assertThat(Pool.widest(both, 2)).isEqualTo(2);
    }

    @Test
    void aPlanOfOneTaskKeepsOneWorkerBusy() {
        Pool.Plan plan = Pool.planByHost(rig.registry(), List.of(task()));
        assertThat(Pool.widest(plan, 4)).isEqualTo(1);
    }

    @Test
    void theOffersStillReachTheSink() {
        List<Task> kept = new ArrayList<>();
        new Pool().run(rig.collector(), days(1, 4), TODAY, answering(), GDS, (task, harvest) -> kept.add(task));
        assertThat(kept).hasSize(4);
    }

    @Test
    void aSupplierNobodyRegisteredIsRefusedBeforeAnyThreadStarts() {
        assertThatThrownBy(() -> new Pool()
                        .run(
                                rig.collector(),
                                List.of(task("nobody", new Market("FRA", "JFK"))),
                                TODAY,
                                answering(),
                                Map.of()))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aGuardedLimiterAnswersTheSameWayThePlainOneDoes() {
        Guarded guarded = new Guarded(rig.limiter(), rig.breakers());
        assertThat(guarded.permits("gds")).isTrue();
        assertThat(guarded.allow("gateway.example", TODAY)).isTrue();
        guarded.failed("gds");
        guarded.succeeded("gds");
        assertThat(guarded.permits("gds")).isTrue();
    }

    @Test
    void aRegistryOfOneSupplierStillRuns() {
        Registry registry = new Registry();
        registry.add(Supplier.builder("only", "one.example")
                .capabilities(Set.of(Capability.SHOP))
                .build());
        Settings settings = Settings.builder()
                .archiveRoot(tmp.resolve("raw"))
                .ratePerSecond(1000.0)
                .burst(200)
                .build();
        Rig made = Rig.builder(settings)
                .registry(registry)
                .clock(new ManualClock())
                .build();
        RunReport report = new Pool()
                .run(
                        made.collector(),
                        List.of(task("only", new Market("FRA", "JFK"))),
                        TODAY,
                        answering(),
                        Map.of("only", CollectPoolTest::parses));
        assertThat(report.size()).isEqualTo(1);
    }
}
