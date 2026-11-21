package io.github.rkaushikdev.gussetline.cli;

import static io.github.rkaushikdev.gussetline.cli.Desk.OPERATOR_CAN_FIX;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.load.FareFeed;
import io.github.rkaushikdev.gussetline.load.RuleFeed;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLoadTest {

    private static final LocalDate FILED = LocalDate.of(2025, 3, 1);

    @TempDir
    Path tmp;

    private final Shell runner = new Shell();
    private String onDisk;

    @BeforeEach
    void aDatabaseOnDisk() {
        onDisk = Shell.fileUrl(tmp);
    }

    /** A desk with its schema built, by the command line, as an operator would. */
    private String desk() {
        runner.run("--database", onDisk, "create-schema");
        return onDisk;
    }

    private static String fareLine() {
        return fareLine("KLWAP7RT", "A", "Y", "420.00");
    }

    private static String fareLine(String basis) {
        return fareLine(basis, "A", "Y", "420.00");
    }

    private static String fareLine(String basis, String action, String cabin, String amount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("carrier", "LH");
        values.put("origin", "FRA");
        values.put("destination", "JFK");
        values.put("fare_basis", basis);
        values.put("currency", "EUR");
        values.put("amount", new BigDecimal(amount));
        values.put("owrt", "2");
        values.put("global_indicator", "AT");
        values.put("rule_number", "0100");
        values.put("routing_number", "0011");
        values.put("tariff_number", "001");
        values.put("cabin", cabin);
        values.put("fare_type", "P");
        values.put("effective", LocalDate.of(2025, 1, 1));
        values.put("discontinue", LocalDate.of(2025, 12, 31));
        values.put("first_travel", LocalDate.of(2025, 1, 1));
        values.put("last_travel", LocalDate.of(2025, 12, 31));
        values.put("action", action);
        return FareFeed.FARE_RECORD.write(values);
    }

    /** A fare file under a header: these lines, or one plain fare when there are none, and a trailer when counted. */
    private String fareFileCalled(String name, Integer count, String... lines) {
        List<String> rows = new ArrayList<>();
        rows.add(FareFeed.HEADER_RECORD.write(Map.of("carrier", "LH", "filed_on", FILED, "sequence", 41)));
        rows.addAll(lines.length == 0 ? List.of(fareLine()) : List.of(lines));
        if (count != null) {
            rows.add(FareFeed.TRAILER_RECORD.write(Map.of("records", count)));
        }
        return write(name, rows);
    }

    private String fareFile(String... lines) {
        return fareFileCalled("lh.dat", null, lines);
    }

    private static String ruleHeader(String action) {
        return RuleFeed.RULE_HEADER.write(Map.of("carrier", "LH", "number", "0100", "tariff", "001", "action", action));
    }

    private static String minimumStay() {
        return RuleFeed.MINIMUM.write(Map.of("days", 3));
    }

    /** A rule file of these lines, or of one rule with seven days of advance reservation when there are none. */
    private String ruleFileCalled(String name, String... lines) {
        List<String> rows = lines.length == 0
                ? List.of(ruleHeader("A"), RuleFeed.ADVANCE.write(Map.of("reservation_days", 7)))
                : List.of(lines);
        return write(name, rows);
    }

    private String ruleFile(String... lines) {
        return ruleFileCalled("lh-rules.dat", lines);
    }

    private String write(String name, List<String> rows) {
        Path path = tmp.resolve(name);
        try {
            Files.writeString(path, String.join("\n", rows), StandardCharsets.US_ASCII);
        } catch (IOException unwritable) {
            throw new AssertionError(unwritable);
        }
        return path.toString();
    }

    /** How many rules the database holds, and how many of each category. */
    private record Held(long count, Map<Integer, Long> categories) {}

    private static Held rulesReachTheDatabase(String url) {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            return new Held(work.rules().count(), work.rules().categoriesHeld());
        }
    }

    @Test
    void theLoadGroupShowsUpInTheHelp() {
        Shell.Result reply = runner.run("--help");
        assertThat(reply.output()).contains("load");
    }

    @Test
    void theLoadGroupListsItsTwoCommands() {
        Shell.Result reply = runner.run("load", "--help");
        assertThat(reply.output()).contains("fares", "rules");
    }

    @Test
    void aCleanFareFileLoads() {
        String desk = desk();
        String path = fareFile(fareLine(), fareLine("MLWAP7RT"));
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("loaded 2 fares");
    }

    @Test
    void theFaresReachTheDatabase() {
        String desk = desk();
        String path = fareFile(fareLine(), fareLine("MLWAP7RT"));
        runner.run("--database", desk, "load", "fares", path);
        Shell.Result reply = runner.run("--database", desk, "check");
        assertThat(reply.output()).contains("fares         2");
    }

    @Test
    void theSameBytesAreRefusedTheSecondTime() {
        // Transfers retry. Loading the file twice doubles a market's fares and the duplicate then wins
        // a selection it should never have been in.
        String desk = desk();
        String path = fareFile(fareLine());
        assertThat(runner.run("--database", desk, "load", "fares", path).code()).isZero();
        Shell.Result again = runner.run("--database", desk, "load", "fares", path);
        assertThat(again.code()).isNotZero();
        assertThat(again.output()).contains("already loaded");
    }

    @Test
    void theSameBytesCanBeForcedThroughWhenSomebodyMeansIt() {
        String desk = desk();
        String path = fareFile(fareLine());
        runner.run("--database", desk, "load", "fares", path);
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path, "--again");
        assertThat(reply.code()).isZero();
    }

    @Test
    void aDifferentFileWithTheSameNameIsNotTheSameBytes() {
        String desk = desk();
        String first = fareFileCalled("lh.dat", null, fareLine());
        runner.run("--database", desk, "load", "fares", first);
        String second = fareFileCalled("lh.dat", null, fareLine("MLWAP7RT"));
        Shell.Result reply = runner.run("--database", desk, "load", "fares", second);
        assertThat(reply.code()).isZero();
    }

    @Test
    void aDryRunSaysWhatWouldHappenAndWritesNothing() {
        String desk = desk();
        String path = fareFile(fareLine());
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path, "--dry-run");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("would be 1 added");
        assertThat(runner.run("--database", desk, "check").output()).contains("fares         0");
    }

    @Test
    void aDryRunDoesNotClaimTheDigest() {
        String desk = desk();
        String path = fareFile(fareLine());
        runner.run("--database", desk, "load", "fares", path, "--dry-run");
        assertThat(runner.run("--database", desk, "load", "fares", path).code()).isZero();
    }

    @Test
    void aFileWithABadRecordLoadsTheRestAndAsksForALook() {
        String desk = desk();
        String path = fareFile(fareLine(), fareLine("MLWAP7RT", "A", "Q", "420.00"));
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path);
        assertThat(reply.code()).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(reply.output()).contains("loaded 1 fares");
    }

    @Test
    void theBadRecordsArePrintedWithTheirLineNumbers() {
        String desk = desk();
        String path = fareFile(fareLine("KLWAP7RT", "A", "Q", "420.00"));
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path);
        assertThat(reply.output()).contains("line 2");
    }

    @Test
    void aTrailerThatDisagreesIsSaidOutLoud() {
        String desk = desk();
        String path = fareFileCalled("lh.dat", 9, fareLine());
        Shell.Result reply = runner.run("--database", desk, "load", "fares", path);
        assertThat(reply.output()).contains("the trailer says 9 records");
    }

    @Test
    void aQuietRunStillSaysWhatWentWrong() {
        String desk = desk();
        String path = fareFile(fareLine("KLWAP7RT", "A", "Q", "420.00"));
        Shell.Result reply = runner.run("--database", desk, "--quiet", "load", "fares", path);
        assertThat(reply.output()).contains("line 2");
        assertThat(reply.output()).doesNotContain("loaded");
    }

    @Test
    void aFileThatIsNotThereIsAUsageError() {
        String desk = desk();
        Shell.Result reply = runner.run(
                "--database", desk, "load", "fares", tmp.resolve("nope.dat").toString());
        assertThat(reply.code()).isEqualTo(2);
    }

    @Test
    void aRuleFileReadsAndCountsItsCategories() {
        String desk = desk();
        String path = ruleFile();
        Shell.Result reply = runner.run("--database", desk, "load", "rules", path);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("1 rules, 0 deleted, 0 problems", "category  5  1");
    }

    @Test
    void aRuleFileWithOrphansSaysSo() {
        String desk = desk();
        String path = ruleFile(minimumStay(), ruleHeader("A"));
        Shell.Result reply = runner.run("--database", desk, "load", "rules", path);
        assertThat(reply.output()).contains("1 category records had no rule above them");
        assertThat(reply.code()).isEqualTo(OPERATOR_CAN_FIX);
    }

    @Test
    void aRuleDryRunSaysWouldBe() {
        String desk = desk();
        String path = ruleFile();
        Shell.Result reply = runner.run("--database", desk, "load", "rules", path, "--dry-run");
        assertThat(reply.output()).contains("would be: 1 rules loaded");
    }

    @Test
    void aRuleFileLandsInTheDatabase() {
        String desk = desk();
        String path = ruleFile();
        Shell.Result reply = runner.run("--database", desk, "load", "rules", path);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("kept 1 rules");
        Held held = rulesReachTheDatabase(desk);
        assertThat(held.count()).isEqualTo(1);
        assertThat(held.categories()).isEqualTo(Map.of(5, 1L));
    }

    @Test
    void aRefiledRuleReplacesWhatIsStored() {
        String desk = desk();
        String first = ruleFileCalled("one.dat");
        runner.run("--database", desk, "load", "rules", first);
        String second = ruleFileCalled("two.dat", ruleHeader("A"), minimumStay());
        runner.run("--database", desk, "load", "rules", second);
        Held held = rulesReachTheDatabase(desk);
        assertThat(held.count()).isEqualTo(1);
        assertThat(held.categories()).isEqualTo(Map.of(6, 1L));
    }

    @Test
    void aDeletedRuleIsDroppedFromTheDatabase() {
        String desk = desk();
        runner.run("--database", desk, "load", "rules", ruleFileCalled("a.dat"));
        String gone = ruleFileCalled("b.dat", ruleHeader("D"));
        Shell.Result reply = runner.run("--database", desk, "load", "rules", gone);
        assertThat(reply.output()).contains("dropped 1");
        assertThat(rulesReachTheDatabase(desk).count()).isZero();
    }

    @Test
    void aDryRunOfARuleFileWritesNothing() {
        String desk = desk();
        String path = ruleFile();
        Shell.Result reply = runner.run("--database", desk, "load", "rules", path, "--dry-run");
        assertThat(reply.output()).doesNotContain("kept");
        assertThat(rulesReachTheDatabase(desk).count()).isZero();
    }
}
