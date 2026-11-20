package io.github.rkaushikdev.gussetline.logs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.Details;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogsTest {

    /** Leave the desk's logger as the rest of the suite found it. */
    @AfterAll
    static void handTheLoggerBack() {
        ch.qos.logback.classic.Logger desk = deskLogger();
        desk.detachAndStopAllAppenders();
        desk.setLevel(null);
        desk.setAdditive(true);
    }

    private static ch.qos.logback.classic.Logger deskLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logs.LOGGER_NAME);
    }

    @Test
    void aLineOpensWithItsEvent() {
        assertThat(Logs.line("night.started")).startsWith("event=night.started");
    }

    @Test
    void theFieldsAreSortedSoTwoLinesLineUp() {
        assertThat(Logs.line("x", Map.of("zebra", 1, "apple", 2))).isEqualTo("event=x apple=2 zebra=1");
    }

    @Test
    void aFieldWithNothingInItIsLeftOut() {
        assertThat(Logs.line("x", Details.of("present", 1, "missing", null))).isEqualTo("event=x present=1");
    }

    @Test
    void aValueWithASpaceInItIsQuoted() {
        assertThat(Logs.line("x", Map.of("why", "no fare found"))).isEqualTo("event=x why=\"no fare found\"");
    }

    @Test
    void aValueWithAnEqualsInItIsQuoted() {
        assertThat(Logs.line("x", Map.of("why", "a=b"))).contains("why=\"a=b\"");
    }

    @Test
    void anEmptyValueIsQuotedSoTheFieldIsStillVisible() {
        assertThat(Logs.line("x", Map.of("why", ""))).isEqualTo("event=x why=\"\"");
    }

    @Test
    void aQuoteInsideAValueDoesNotBreakTheLine() {
        assertThat(Logs.line("x", Map.of("why", "he said \"no\""))).isEqualTo("event=x why=\"he said 'no'\"");
    }

    @Test
    void aBooleanReadsAsAWord() {
        assertThat(Logs.line("x", Map.of("dry", true, "live", false))).isEqualTo("event=x dry=yes live=no");
    }

    @Test
    void aDateReadsTheWayEverythingElseWritesOne() {
        assertThat(Logs.line("x", Map.of("on", LocalDate.of(2025, 8, 4)))).contains("on=2025-08-04");
    }

    @Test
    void aTimeLosesItsMicroseconds() {
        assertThat(Logs.line("x", Map.of("at", LocalDateTime.of(2025, 8, 4, 10, 25, 0, 12_345_000))))
                .contains("at=2025-08-04T10:25:00");
    }

    @Test
    void aDecimalKeepsItsTrailingZeroes() {
        assertThat(Logs.line("x", Map.of("worth", new BigDecimal("90.00")))).contains("worth=90.00");
    }

    @Test
    void aTicketNumberIsMaskedToItsLastFour() {
        // A payment instrument. Anybody holding one can call a carrier.
        assertThat(Logs.line("x", Map.of("ticket", "220-1234567890"))).contains("ticket=**********7890");
    }

    @Test
    void aPassengerNameIsMaskedAsWell() {
        assertThat(Logs.line("x", Map.of("passenger", "MUELLER/ANNA"))).doesNotContain("MUELLER");
    }

    @Test
    void somethingTooShortToMaskIsHiddenWhole() {
        assertThat(Logs.line("x", Map.of("ticket", "12"))).isEqualTo("event=x ticket=**");
    }

    @Test
    void aKeyNeverReachesALineEvenMasked() {
        // A masked key is still a key narrowed down, and no question a log answers needs one.
        String line = Logs.line("x", Map.of("key", "secret", "api_key", "secret", "token", "secret", "why", "refused"));
        assertThat(line).doesNotContain("secret");
        assertThat(line).doesNotContain("key");
        assertThat(line).contains("why=refused");
    }

    @Test
    void maskingKeepsTheShapeOfTheValue() {
        assertThat(Logs.mask("220-1234567890")).hasSameSizeAs("220-1234567890");
    }

    @Test
    void aLineCarriesNoRunWhenThereIsNone() {
        assertThat(Logs.line("x")).doesNotContain("run=");
    }

    @Test
    void everyLineInsideARunCarriesTheSameIdentifier() {
        List<String> seen = Logs.inRun(runId -> List.of(runId, Logs.line("one"), Logs.line("two")));
        String runId = seen.get(0);
        assertThat(seen.get(1)).contains("run=" + runId);
        assertThat(seen.get(2)).contains("run=" + runId);
    }

    @Test
    void aRunCanBeGivenItsOwnIdentifier() {
        Logs.inRun("abc123", () -> assertThat(Logs.line("x")).contains("run=abc123"));
    }

    @Test
    void theRunIsPutBackWhenTheBlockEnds() {
        Logs.inRun("abc123", () -> {});
        assertThat(Logs.currentRun()).isEmpty();
    }

    @Test
    void aRunInsideARunPutsTheOuterOneBack() {
        Logs.inRun("outer", () -> {
            Logs.inRun("inner", () -> assertThat(Logs.currentRun()).isEqualTo("inner"));
            assertThat(Logs.currentRun()).isEqualTo("outer");
        });
    }

    @Test
    void twoRunsAreNeverTheSame() {
        assertThat(Logs.newRunId()).isNotEqualTo(Logs.newRunId()).matches("[0-9a-f]{12}");
    }

    @Test
    void anEventReachesTheStreamItWasPointedAt() {
        StringWriter stream = new StringWriter();
        Logs.setup(stream);
        Logs.event("night.started", Map.of("watches", 9));
        assertThat(stream.toString()).contains("event=night.started watches=9");
        // The time to the millisecond, the level, then the line.
        assertThat(stream.toString())
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} INFO event=night\\.started watches=9\n");
    }

    @Test
    void aProblemIsWrittenAtALevelThatStandsOut() {
        StringWriter stream = new StringWriter();
        Logs.setup(stream);
        Logs.problem("supplier.blocked", Map.of("supplier", "gds"));
        assertThat(stream.toString()).contains("WARNING");
    }

    @Test
    void settingUpTwiceDoesNotWriteEveryLineTwice() {
        // The second copy is the one that makes a file unreadable.
        StringWriter stream = new StringWriter();
        Logs.setup(stream);
        Logs.setup(stream);
        Logs.event("once");
        assertThat(stream.toString().split("event=once", -1)).hasSize(2);
    }

    @Test
    void pointingItSomewhereElseStopsWritingToTheFirstPlace() {
        StringWriter first = new StringWriter();
        StringWriter second = new StringWriter();
        Logs.setup(first);
        Logs.setup(second);
        Logs.event("here");
        assertThat(first.toString()).doesNotContain("here");
        assertThat(second.toString()).contains("here");
    }

    @Test
    void theLevelCanBeTurnedDown() {
        StringWriter stream = new StringWriter();
        Logs.setup("WARNING", stream);
        Logs.event("quiet");
        Logs.problem("loud");
        assertThat(stream.toString()).doesNotContain("quiet");
        assertThat(stream.toString()).contains("loud");
        Logs.setup("INFO", stream);
    }

    @Test
    void theLoggerDoesNotHandItsLinesToTheRoot() {
        Logs.setup(new StringWriter());
        assertThat(deskLogger().isAdditive()).isFalse();
    }
}
