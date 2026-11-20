package io.github.rkaushikdev.gussetline.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.collect.Field.Kind;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectFixedwidthTest {

    private static final RecordSpec HEADER = new RecordSpec(
            "H1",
            new Field("source", 2, 8),
            new Field("stamp", 10, 8, Kind.DATE),
            new Field("origin", 18, 3),
            new Field("destination", 21, 3));

    private static final RecordSpec AMOUNT = new RecordSpec(
            "F1",
            new Field("basis", 2, 10),
            new Field("currency", 12, 3),
            new Field("amount", 15, 9, Kind.AMOUNT),
            new Field("seats", 24, 2, Kind.INT, true));

    private static final Layout LAYOUT = new Layout(List.of(HEADER, AMOUNT));

    // Counted against the layout above. The basis field is ten wide and the code
    // is eight, so it carries two trailing spaces and not one, which is exactly
    // the kind of thing that makes a fixed width payload readable only against
    // its spec sheet.
    private static final String GOOD = "H1GDSFARE 20250214FRAJFK\nF1KLWAP7RT  EUR000042000 2\n";

    private static Layout layoutOf(RecordSpec spec) {
        return new Layout(List.of(spec));
    }

    @Test
    void aFieldKnowsWhereItEnds() {
        Field column = new Field("basis", 2, 10);
        assertThat(column.end()).isEqualTo(12);
        assertThat(column.sliceFrom("F1KLWAP7RT  EUR")).isEqualTo("KLWAP7RT  ");
    }

    @Test
    void aLayoutListsTheTagsItKnows() {
        assertThat(LAYOUT.tags()).containsExactly("F1", "H1");
        assertThat(LAYOUT.specFor("H1")).containsSame(HEADER);
        assertThat(LAYOUT.specFor("ZZ")).isEmpty();
    }

    @Test
    void aCleanPayloadReadsEveryRecord() {
        Reading reading = LAYOUT.read(GOOD);
        assertThat(reading.clean()).isTrue();
        assertThat(reading.size()).isEqualTo(2);
        assertThat(reading).hasToString("2 records, 0 problems");
    }

    @Test
    void theHeaderFieldsComeOutTyped() {
        Record header = LAYOUT.read(GOOD).require("H1");
        assertThat(header.get("source")).isEqualTo("GDSFARE");
        assertThat(header.get("stamp")).isEqualTo(LocalDate.of(2025, 2, 14));
        assertThat(header.get("origin")).isEqualTo("FRA");
        assertThat(header.get("destination")).isEqualTo("JFK");
    }

    @Test
    void anImpliedDecimalIsReadWithItsScale() {
        Record fare = LAYOUT.read(GOOD).require("F1");
        assertThat(fare.get("amount", BigDecimal.class)).isEqualByComparingTo("420.00");
        assertThat(fare.get("currency")).isEqualTo("EUR");
        assertThat(fare.get("seats")).isEqualTo(2);
    }

    @Test
    void aPayloadCanBeBytesOrText() {
        assertThat(LAYOUT.read(GOOD.getBytes(StandardCharsets.UTF_8)).size()).isEqualTo(2);
    }

    @Test
    void blankLinesAreSkippedAndAreNotProblems() {
        Reading reading = LAYOUT.read("\n\n" + GOOD + "   \n");
        assertThat(reading.clean()).isTrue();
        assertThat(reading.size()).isEqualTo(2);
    }

    @Test
    void anUnknownTagIsAProblemAndTheRestStillReads() {
        Reading reading = LAYOUT.read("Z9something\n" + GOOD);
        assertThat(reading.clean()).isFalse();
        assertThat(reading.size()).isEqualTo(2);
        assertThat(reading.problems().get(0).reason()).contains("unknown record tag");
        assertThat(reading.problems().get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    void aShortLineIsAProblemAndNamesBothWidths() {
        Reading reading = LAYOUT.read("H1GDSFARE 2025\n");
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0).reason()).contains("the layout wants 24");
    }

    @Test
    void aNumberWithALetterInItIsAProblem() {
        Reading reading = LAYOUT.read("F1KLWAP7RT  EURxxxxx2000 2\n");
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0).reason()).contains("amount did not read");
    }

    @Test
    void aBadDateIsAProblem() {
        Reading reading = LAYOUT.read("H1GDSFARE 2025FEB14FRAJFK\n");
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0).reason()).contains("stamp did not read");
    }

    @Test
    void anOptionalFieldMayBeBlank() {
        Reading reading = LAYOUT.read("F1KLWAP7RT  EUR000042000  \n");
        assertThat(reading.clean()).isTrue();
        assertThat(reading.require("F1").contains("seats")).isFalse();
    }

    @Test
    void aRequiredNumberThatIsBlankIsAProblem() {
        Reading reading = LAYOUT.read("F1KLWAP7RT  EUR          2\n");
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0).reason()).contains("amount is empty");
    }

    @Test
    void aBlankTextFieldReadsAsEmptyRatherThanFailing() {
        RecordSpec spec = new RecordSpec("T1", new Field("code", 2, 3), new Field("note", 5, 5));
        Reading reading = layoutOf(spec).read("T1DE      \n");
        assertThat(reading.clean()).isTrue();
        assertThat(reading.require("T1").get("note")).isEqualTo("");
    }

    @Test
    void aRawFieldKeepsItsSpacesSoMissingAndZeroStayApart() {
        RecordSpec spec = new RecordSpec("Q1", new Field("quantity", 2, 4, Kind.RAW));
        Reading reading = layoutOf(spec).read("Q1    \nQ10000\n");
        assertThat(reading.tagged("Q1").stream().map(r -> r.get("quantity"))).containsExactly("    ", "0000");
    }

    @Test
    void recordsCanBeReadBackByTag() {
        Reading reading = LAYOUT.read(GOOD + "F1QLWAP14RT EUR000038000 4\n");
        assertThat(reading.tagged("F1")).hasSize(2);
        assertThat(reading.first("F1").orElseThrow().get("basis")).isEqualTo("KLWAP7RT");
        assertThat(reading.first("ZZ")).isEmpty();
    }

    @Test
    void requiringARecordThatIsNotThereIsAMalformedPayload() {
        MalformedPayload caught = catchThrowableOfType(
                MalformedPayload.class, () -> LAYOUT.read("").require("H1"));
        assertThat(caught.details()).containsEntry("tag", "H1");
    }

    @Test
    void aRecordAnswersLikeAMapping() {
        Record record = new Record("F1", Map.of("basis", "KLWAP7RT"));
        assertThat(record.get("basis")).isEqualTo("KLWAP7RT");
        assertThat(record.getOrDefault("missing", "none")).isEqualTo("none");
        assertThat(record.contains("basis")).isTrue();
        assertThat(record.contains("missing")).isFalse();
    }

    @Test
    void aProblemReadsBackWithItsLineNumber() {
        assertThat(new Problem(4, "junk", "unknown record tag")).hasToString("line 4: unknown record tag");
    }

    @Test
    void aZeroScaleAmountHasNoDecimalPlaces() {
        RecordSpec spec = new RecordSpec("Y1", new Field("yen", 2, 6, Kind.AMOUNT0));
        assertThat(layoutOf(spec).read("Y1018100\n").require("Y1").get("yen", BigDecimal.class))
                .isEqualByComparingTo("18100");
    }

    @Test
    void aThreeScaleAmountReadsThreePlaces() {
        RecordSpec spec = new RecordSpec("K1", new Field("dinar", 2, 7, Kind.AMOUNT3));
        assertThat(layoutOf(spec).read("K10012999\n").require("K1").get("dinar", BigDecimal.class))
                .isEqualByComparingTo("12.999");
    }

    @Test
    void aNegativeAmountKeepsItsSign() {
        RecordSpec spec = new RecordSpec("A1", new Field("adjust", 2, 8, Kind.AMOUNT));
        assertThat(layoutOf(spec).read("A1-0001250\n").require("A1").get("adjust", BigDecimal.class))
                .isEqualByComparingTo("-12.50");
    }

    @Test
    void aDayAndTimeFieldComesOutAsFourNumbers() {
        RecordSpec spec = new RecordSpec("S1", new Field("when", 2, 8, Kind.DAYTIME));
        assertThat(layoutOf(spec).read("S103041025\n").require("S1").get("when"))
                .isEqualTo(new DayTime(3, 4, 10, 25));
    }

    @Test
    void aDayAndTimeThatIsNotDigitsIsAProblem() {
        RecordSpec spec = new RecordSpec("S1", new Field("when", 2, 8, Kind.DAYTIME));
        Reading reading = layoutOf(spec).read("S103MAR102\n");
        assertThat(reading.clean()).isFalse();
    }

    @Test
    void aRecordWrittenBackReadsTheSameWay() {
        String line = AMOUNT.write(
                Map.of("basis", "KLWAP7RT", "currency", "EUR", "amount", new BigDecimal("420.00"), "seats", 2));
        assertThat(line).hasSize(26);
        Record record = layoutOf(AMOUNT).read(line).require("F1");
        assertThat(record.get("basis")).isEqualTo("KLWAP7RT");
        assertThat(record.get("amount", BigDecimal.class)).isEqualByComparingTo("420.00");
        assertThat(record.get("seats")).isEqualTo(2);
    }

    @Test
    void aHeaderRoundTripsThroughBothDirections() {
        String line = HEADER.write(Map.of(
                "source", "GDSFARE",
                "stamp", LocalDate.of(2025, 2, 14),
                "origin", "FRA",
                "destination", "JFK"));
        assertThat(line).isEqualTo("H1GDSFARE 20250214FRAJFK");
        assertThat(layoutOf(HEADER).read(line).require("H1").get("stamp")).isEqualTo(LocalDate.of(2025, 2, 14));
    }

    @Test
    void anOptionalFieldLeftOutWritesAsSpaces() {
        String line = AMOUNT.write(Map.of("basis", "KLWAP7RT", "currency", "EUR", "amount", new BigDecimal("420.00")));
        assertThat(line).endsWith("  ");
        assertThat(layoutOf(AMOUNT).read(line).require("F1").contains("seats")).isFalse();
    }

    @Test
    void aRequiredFieldLeftOutIsRefused() {
        MalformedPayload caught = catchThrowableOfType(
                MalformedPayload.class, () -> AMOUNT.write(Map.of("basis", "KLWAP7RT", "currency", "EUR")));
        assertThat(caught.details()).containsEntry("field", "amount");
    }

    @Test
    void aNegativeAmountRoundTrips() {
        RecordSpec spec = new RecordSpec("A1", new Field("adjust", 2, 8, Kind.AMOUNT));
        String line = spec.write(Map.of("adjust", new BigDecimal("-12.50")));
        assertThat(line).isEqualTo("A1-0001250");
        assertThat(layoutOf(spec).read(line).require("A1").get("adjust", BigDecimal.class))
                .isEqualByComparingTo("-12.50");
    }

    @Test
    void aZeroScaleAmountRoundTrips() {
        RecordSpec spec = new RecordSpec("Y1", new Field("yen", 2, 6, Kind.AMOUNT0));
        String line = spec.write(Map.of("yen", new BigDecimal("18100")));
        assertThat(line).isEqualTo("Y1018100");
        assertThat(layoutOf(spec).read(line).require("Y1").get("yen", BigDecimal.class))
                .isEqualByComparingTo("18100");
    }

    @Test
    void aDayAndTimeRoundTrips() {
        RecordSpec spec = new RecordSpec("S1", new Field("when", 2, 8, Kind.DAYTIME));
        String line = spec.write(Map.of("when", new DayTime(3, 4, 10, 25)));
        assertThat(line).isEqualTo("S103041025");
        assertThat(layoutOf(spec).read(line).require("S1").get("when")).isEqualTo(new DayTime(3, 4, 10, 25));
    }

    @Test
    void aValueTooWideForItsFieldIsTruncatedForText() {
        RecordSpec spec = new RecordSpec("T1", new Field("code", 2, 3));
        assertThat(spec.write(Map.of("code", "TOOLONG"))).isEqualTo("T1TOO");
    }

    @Test
    void aNumberTooWideForItsFieldIsRefused() {
        RecordSpec spec = new RecordSpec("N1", new Field("count", 2, 2, Kind.INT));
        assertThatThrownBy(() -> spec.write(Map.of("count", 1234))).isInstanceOf(MalformedPayload.class);
    }

    /**
     * A field kind nobody can write cannot be declared at all, since every kind there is carries its
     * own writer. So the check is the other way round: each kind writes a value that fills its field
     * exactly and reads back clean, and adding a kind without a sample here fails the first line.
     */
    @Test
    void everyFieldKindHasAWriter() {
        Map<Kind, Object> samples = new EnumMap<>(Kind.class);
        samples.put(Kind.TEXT, "abcd");
        samples.put(Kind.RAW, "abcd");
        samples.put(Kind.INT, 12);
        samples.put(Kind.AMOUNT, new BigDecimal("1.25"));
        samples.put(Kind.AMOUNT0, new BigDecimal("125"));
        samples.put(Kind.AMOUNT3, new BigDecimal("0.125"));
        samples.put(Kind.DATE, LocalDate.of(2025, 2, 14));
        samples.put(Kind.DAYTIME, new DayTime(3, 4, 10, 25));
        assertThat(samples).containsOnlyKeys(Kind.values());

        samples.forEach((kind, value) -> {
            RecordSpec spec = new RecordSpec("X1", new Field("thing", 2, 8, kind));
            String line = spec.write(Map.of("thing", value));
            assertThat(line).as(kind.name()).hasSize(10);
            assertThat(layoutOf(spec).read(line).clean()).as(kind.name()).isTrue();
        });
    }
}
