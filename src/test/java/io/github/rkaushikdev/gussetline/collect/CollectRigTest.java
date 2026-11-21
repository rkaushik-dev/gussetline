package io.github.rkaushikdev.gussetline.collect;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.config.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectRigTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 7, 20);
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 24);
    private static final Market FRA_JFK = new Market("FRA", "JFK");

    @TempDir
    Path tmp;

    private Settings settings;
    private Rig rig;

    @BeforeEach
    void assemble() {
        settings = Settings.builder()
                .archiveRoot(tmp.resolve("raw"))
                .dailyQuota(4)
                .ratePerSecond(100.0)
                .build();
        rig = Rig.builder(settings)
                .clock(new ManualClock())
                .now(() -> LocalDateTime.of(2025, 7, 20, 6, 0))
                .build();
    }

    private static Task task() {
        return task("gds", FRA_JFK, AUGUST);
    }

    private static Task task(String supplier) {
        return task(supplier, FRA_JFK, AUGUST);
    }

    private static Task task(String supplier, Market market, LocalDate departure) {
        return new Task(supplier, market, departure, 35);
    }

    private static Harvest harvestOf(byte[] body, LocalDateTime seen) {
        return Harvest.nothing("read " + body.length + " bytes", "test");
    }

    /** What a collector writes beside a payload it took for FRA to NYC in August. */
    private static Map<String, Object> augustContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("market", "FRA-NYC");
        context.put("departure", AUGUST.toString());
        return context;
    }

    private void store(String body, String supplier, String path, int day) {
        rig.archive().put(body.getBytes(UTF_8), supplier, path, LocalDateTime.of(2025, 7, day, 6, 0), augustContext());
    }

    @Test
    void aRigCarriesEveryPartARunNeeds() {
        assertThat(rig.registry().size()).isEqualTo(3);
        assertThat(Files.exists(rig.archive().root())).isTrue();
        assertThat(rig.collector()).isNotNull();
    }

    @Test
    void everySupplierHostIsBudgetedBeforeAnythingRuns() {
        // A supplier that is completely down still has to respect its budget, and the settings command
        // has to be able to print what a run would allow.
        assertThat(rig.limiter().knownHosts()).containsExactly("fares.example", "gateway.example", "offers.example");
    }

    @Test
    void theBudgetsComeOffTheSettings() {
        HostBudget budget = rig.limiter().budgetFor("gateway.example");
        assertThat(budget.quota().limit()).isEqualTo(settings.dailyQuota());
    }

    @Test
    void aRigReadsBackWithWhatItHolds() {
        assertThat(rig).hasToString("3 suppliers, 0 parsers, quota 4 a day");
    }

    @Test
    void aParserCanBeRegisteredAfterTheFact() {
        rig.register("gds", CollectRigTest::harvestOf);
        assertThat(rig.parsers()).containsKey("gds");
        assertThat(rig.toString()).endsWith("1 parsers, quota 4 a day");
    }

    @Test
    void aReplayOfAnEmptyArchiveFindsNothing() {
        Fetch fetch = rig.replayFetch();
        assertThatThrownBy(() -> fetch.fetch(rig.registry().get("gds"), task()))
                .isInstanceOf(SupplierUnavailable.class);
    }

    @Test
    void aStoredPayloadComesBackWithoutTouchingASupplier() {
        store("the payload", "gds", "/pricing", 19);
        Response reply = rig.replayFetch().fetch(rig.registry().get("gds"), task());
        assertThat(reply.ok()).isTrue();
        assertThat(reply.body()).isEqualTo("the payload".getBytes(UTF_8));
        assertThat(reply.url()).startsWith("replay://gds");
    }

    @Test
    void theNewestStoredPayloadIsTheOneReplayed() {
        store("old", "gds", "/pricing", 17);
        store("newer", "gds", "/pricing", 19);
        store("middle", "gds", "/pricing", 18);
        assertThat(rig.replayFetch().fetch(rig.registry().get("gds"), task()).body())
                .isEqualTo("newer".getBytes(UTF_8));
    }

    @Test
    void aReplayKeepsTheSuppliersApart() {
        store("from the gateway", "gds", "/pricing", 19);
        Fetch fetch = rig.replayFetch();
        assertThatThrownBy(() -> fetch.fetch(rig.registry().get("ndc"), task("ndc")))
                .isInstanceOf(SupplierUnavailable.class);
    }

    @Test
    void aReplayCanBeNarrowedToOneSupplier() {
        for (String name : List.of("gds", "ndc")) {
            store(name, name, "/x", 19);
        }
        Fetch fetch = rig.replayFetch("ndc");
        assertThat(fetch.fetch(rig.registry().get("ndc"), task("ndc")).body()).isEqualTo("ndc".getBytes(UTF_8));
        assertThatThrownBy(() -> fetch.fetch(rig.registry().get("gds"), task()))
                .isInstanceOf(SupplierUnavailable.class);
    }

    @Test
    void aPayloadStoredWithNoMarketOnItIsNotReplayable() {
        rig.archive().put("orphan".getBytes(UTF_8), "gds", "/pricing", LocalDateTime.of(2025, 7, 19, 6, 0));
        assertThatThrownBy(() -> rig.replayFetch().fetch(rig.registry().get("gds"), task()))
                .isInstanceOf(SupplierUnavailable.class);
    }

    @Test
    void aWholeRunCanBeReplayedOutOfTheArchive() {
        store("a payload", "gds", "/pricing", 19);
        RunReport report = rig.collector()
                .run(List.of(task()), TODAY, rig.replayFetch(), Map.of("gds", CollectRigTest::harvestOf));
        assertThat(report.size()).isEqualTo(1);
        assertThat(report.results().get(0).ok()).isTrue();
    }

    @Test
    void aReplayedMarketNobodyCollectedIsRecordedAndNotRaised() {
        RunReport report = rig.collector()
                .run(
                        List.of(task(), task("gds", new Market("MUC", "ORD"), AUGUST)),
                        TODAY,
                        rig.replayFetch(),
                        Map.of("gds", CollectRigTest::harvestOf));
        assertThat(report.byOutcome()).isEqualTo(Map.of("transient", 2));
        assertThat(report.failures().get(0).reason()).isEqualTo("nothing stored for that market");
    }

    @Test
    void aLiveFetchAsksTheSupplierForTheMarketAndTheDate() {
        List<String> paths = new ArrayList<>();
        List<Map<String, String>> asked = new ArrayList<>();
        Rig.Client fake = (path, params) -> {
            paths.add(path);
            asked.add(Map.copyOf(params));
            return new Response(200, "{}".getBytes(UTF_8), Map.of(), 1, 0, path);
        };
        try (Rig.LiveFetch fetch = rig.liveFetch(supplier -> fake)) {
            fetch.fetch(rig.registry().get("gds"), task());
        }
        assertThat(paths.get(0)).isEqualTo("/pricing");
        assertThat(asked.get(0))
                .containsEntry("origin", "FRA")
                .containsEntry("destination", "NYC")
                .containsEntry("departure", "2025-08-24");
    }

    @Test
    void aLiveFetchMakesOneClientForEachSupplier() {
        List<String> made = new ArrayList<>();
        Rig.Client fake = (path, params) -> new Response(200, "{}".getBytes(UTF_8), Map.of(), 1, 0, path);
        try (Rig.LiveFetch fetch = rig.liveFetch(supplier -> {
            made.add(supplier.name());
            return fake;
        })) {
            fetch.fetch(rig.registry().get("gds"), task());
            fetch.fetch(rig.registry().get("gds"), task("gds", FRA_JFK, LocalDate.of(2025, 9, 1)));
            fetch.fetch(rig.registry().get("ndc"), task("ndc"));
        }
        assertThat(made).containsExactly("gds", "ndc");
    }

    @Test
    void aRigCanBeBuiltOverARegistryOfOurOwn() {
        Registry registry = new Registry();
        registry.add(Supplier.builder("only", "one.example")
                .capabilities(Set.of(Capability.SHOP))
                .build());
        Rig made = Rig.builder(settings)
                .registry(registry)
                .clock(new ManualClock())
                .build();
        assertThat(made.registry().names()).containsExactly("only");
        assertThat(made.limiter().knownHosts()).containsExactly("one.example");
    }

    @Test
    void theQuotaStillStopsAReplay() {
        store("a payload", "gds", "/pricing", 19);
        List<Task> tasks = new ArrayList<>();
        for (int count = 0; count < 6; count++) {
            tasks.add(task());
        }
        RunReport report =
                rig.collector().run(tasks, TODAY, rig.replayFetch(), Map.of("gds", CollectRigTest::harvestOf));
        assertThat(report.skippedBudget()).isEqualTo(2);
        assertThat(report.byOutcome().get(Outcome.OK.value())).isEqualTo(4);
    }
}
