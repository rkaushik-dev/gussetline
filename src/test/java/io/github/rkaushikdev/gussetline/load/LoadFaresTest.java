package io.github.rkaushikdev.gussetline.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.load.Filing.Action;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.Market;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadFaresTest {

    private static final LocalDate FILED = LocalDate.of(2025, 3, 1);

    /**
     * One fare record, at the values most tests want: an LH add from Frankfurt to New York under
     * KLWAP7RT, 420.00 EUR as half a round trip, on sale and flyable through 2025. Each setter changes
     * one value before the record is written.
     */
    private static final class FareLine {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private FareLine() {
            values.put("carrier", "LH");
            values.put("origin", "FRA");
            values.put("destination", "JFK");
            values.put("fare_basis", "KLWAP7RT");
            values.put("currency", "EUR");
            values.put("amount", new BigDecimal("420.00"));
            values.put("owrt", "2");
            values.put("global_indicator", "AT");
            values.put("rule_number", "0100");
            values.put("routing_number", "0011");
            values.put("tariff_number", "001");
            values.put("footnote", null);
            values.put("cabin", "Y");
            values.put("fare_type", "P");
            values.put("effective", LocalDate.of(2025, 1, 1));
            values.put("discontinue", LocalDate.of(2025, 12, 31));
            values.put("first_travel", LocalDate.of(2025, 1, 1));
            values.put("last_travel", LocalDate.of(2025, 12, 31));
            values.put("action", "A");
        }

        FareLine action(String code) {
            return with("action", code);
        }

        FareLine basis(String code) {
            return with("fare_basis", code);
        }

        FareLine amount(String amount) {
            return with("amount", new BigDecimal(amount));
        }

        FareLine indicator(String code) {
            return with("global_indicator", code);
        }

        /** An empty footnote is written as a blank field. */
        FareLine footnote(String note) {
            return with("footnote", note.isEmpty() ? null : note);
        }

        FareLine cabin(String code) {
            return with("cabin", code);
        }

        FareLine fareType(String code) {
            return with("fare_type", code);
        }

        FareLine effective(LocalDate day) {
            return with("effective", day);
        }

        FareLine discontinue(LocalDate day) {
            return with("discontinue", day);
        }

        String write() {
            return FareFeed.FARE_RECORD.write(values);
        }

        private FareLine with(String name, Object value) {
            values.put(name, value);
            return this;
        }
    }

    private static FareLine fareLine() {
        return new FareLine();
    }

    private static String header() {
        return FareFeed.HEADER_RECORD.write(Map.of("carrier", "LH", "filed_on", FILED, "sequence", 41));
    }

    private static String trailer(int count) {
        return FareFeed.TRAILER_RECORD.write(Map.of("records", count));
    }

    /** The lines under a header, one per line. */
    private static String payload(String... lines) {
        List<String> rows = new ArrayList<>();
        rows.add(header());
        rows.addAll(List.of(lines));
        return String.join("\n", rows);
    }

    /** {@link #payload} with a trailer declaring {@code count} records. */
    private static String counted(int count, String... lines) {
        return payload(lines) + "\n" + trailer(count);
    }

    @Test
    void anEmptyFileReadsAsAnEmptyLoad() {
        FareLoad load = FareFeed.read("");
        assertThat(load.size()).isZero();
        assertThat(load.clean()).isTrue();
        assertThat(load.carrier()).isEmpty();
    }

    @Test
    void theHeaderSaysWhichCarrierAndWhichDay() {
        FareLoad load = FareFeed.read(payload(fareLine().write()));
        assertThat(load.carrier()).isEqualTo("LH");
        assertThat(load.filedOn()).contains(FILED);
        assertThat(load.sequence()).isEqualTo(41);
    }

    @Test
    void aFareRecordReadsIntoAFareTheStoreWouldTake() {
        FareLoad load = FareFeed.read(payload(fareLine().write()));
        assertThat(load.size()).isEqualTo(1);
        Filing filing = load.filings().get(0);
        assertThat(filing.action()).isSameAs(Action.ADDED);
        Fare fare = filing.fare();
        assertThat(fare.carrier()).isEqualTo("LH");
        assertThat(fare.market()).isEqualTo(new Market("FRA", "NYC"));
        assertThat(fare.fareBasis()).isEqualTo("KLWAP7RT");
        assertThat(fare.amount()).isEqualTo(Money.of("420.00", "EUR"));
        assertThat(fare.owrt()).isSameAs(OwRt.ROUND_TRIP_HALF);
        assertThat(fare.globalIndicator()).isSameAs(GlobalIndicator.AT);
        assertThat(fare.cabin()).isSameAs(Cabin.ECONOMY);
        assertThat(fare.fareType()).isSameAs(FareType.PUBLISHED);
        assertThat(fare.effective()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(fare.lastTravel()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void theAmountArrivesWithNoDecimalPointInIt() {
        String line = fareLine().amount("1234.50").write();
        assertThat(line).contains("000123450");
        assertThat(FareFeed.read(payload(line)).filings().get(0).fare().amount())
                .isEqualTo(Money.of("1234.50", "EUR"));
    }

    @Test
    void aMissingFootnoteIsEmptyAndNotAProblem() {
        FareLoad load = FareFeed.read(payload(fareLine().footnote("").write()));
        assertThat(load.clean()).isTrue();
        assertThat(load.filings().get(0).fare().footnote()).isEmpty();
    }

    @Test
    void aFootnoteIsCarriedThroughWhenThereIsOne() {
        assertThat(FareFeed.read(payload(fareLine().footnote("12").write()))
                        .filings()
                        .get(0)
                        .fare()
                        .footnote())
                .isEqualTo("12");
    }

    @Test
    void theThreeActionsReadAsThreeActions() {
        FareLoad load = FareFeed.read(payload(
                fareLine().action("A").write(),
                fareLine().action("C").basis("MLWAP7RT").write(),
                fareLine().action("D").write()));
        assertThat(load.filings())
                .extracting(Filing::action)
                .containsExactly(Action.ADDED, Action.CHANGED, Action.DELETED);
        assertThat(load.of(Action.DELETED)).hasSize(1);
    }

    @Test
    void anActionNobodyFilesIsOneBadRecordAndNotABadFile() {
        FareLoad load = FareFeed.read(payload(
                fareLine().action("A").write(),
                fareLine().action("Z").basis("MLWAP7RT").write()));
        assertThat(load.size()).isEqualTo(1);
        assertThat(load.problems()).hasSize(1);
        assertThat(load.problems().get(0).reason()).contains("filing action");
    }

    @Test
    void anIndicatorWeDoNotSellIsRefusedByItself() {
        FareLoad load = FareFeed.read(payload(fareLine().indicator("XX").write()));
        assertThat(load.size()).isZero();
        assertThat(load.problems().get(0).reason()).contains("global indicator");
    }

    @Test
    void aCabinThatIsNotOneOfTheFourIsRefusedByItself() {
        FareLoad load = FareFeed.read(payload(fareLine().cabin("Q").write()));
        assertThat(load.problems().get(0).reason()).contains("cabin");
    }

    @Test
    void aFareTypeTheFeedInventedIsRefusedByItself() {
        FareLoad load = FareFeed.read(payload(fareLine().fareType("X").write()));
        assertThat(load.problems().get(0).reason()).contains("fare type");
    }

    @Test
    void aSaleWindowThatClosesBeforeItOpensIsOneBadRecord() {
        FareLoad load = FareFeed.read(payload(
                fareLine()
                        .effective(LocalDate.of(2025, 6, 1))
                        .discontinue(LocalDate.of(2025, 1, 1))
                        .write(),
                fareLine().basis("MLWAP7RT").write()));
        assertThat(load.size()).isEqualTo(1);
        assertThat(load.problems()).hasSize(1);
        assertThat(load.problems().get(0).reason()).contains("closes before it opens");
    }

    @Test
    void aBadRecordIsReportedAgainstItsOwnLineNumber() {
        FareLoad load =
                FareFeed.read(payload(fareLine().write(), fareLine().cabin("Q").write()));
        assertThat(load.problems().get(0).lineNumber()).isEqualTo(3);
    }

    @Test
    void theTrailerCountIsCheckedRatherThanTrusted() {
        assertThat(FareFeed.read(counted(1, fareLine().write())).counted()).isTrue();
        assertThat(FareFeed.read(counted(9, fareLine().write())).counted()).isFalse();
    }

    @Test
    void aFileWithNoTrailerIsNotReportedAsMiscounted() {
        assertThat(FareFeed.read(payload(fareLine().write())).counted()).isTrue();
    }

    @Test
    void theBadRecordsCountTowardsTheTrailerToo() {
        // The carrier counted every record it wrote, including the ones we could
        // not use, so a count that excludes them would fail on every real file.
        FareLoad load = FareFeed.read(
                counted(2, fareLine().write(), fareLine().cabin("Q").write()));
        assertThat(load.counted()).isTrue();
    }

    @Test
    void aLoadReadsBackInOneLine() {
        FareLoad load = FareFeed.read(payload(fareLine().write()));
        assertThat(load).hasToString("LH 2025-03-01: 1 filings, 0 problems");
    }

    @Test
    void anAddedFareLandsInTheStore() {
        TariffStore store = new TariffStore();
        FaresApplied applied =
                FareFeed.loadInto(store, payload(fareLine().write())).applied();
        assertThat(applied.added()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.allFares().get(0).fareBasis()).isEqualTo("KLWAP7RT");
    }

    @Test
    void aDeletedFareLeavesTheStore() {
        TariffStore store = new TariffStore();
        FareFeed.loadInto(store, payload(fareLine().action("A").write()));
        FaresApplied applied = FareFeed.loadInto(
                        store, payload(fareLine().action("D").write()))
                .applied();
        assertThat(applied.deleted()).isEqualTo(1);
        assertThat(store.size()).isZero();
    }

    @Test
    void aDeleteForAFareWeNeverHeldIsCountedAndIgnored() {
        TariffStore store = new TariffStore();
        FaresApplied applied = FareFeed.loadInto(
                        store, payload(fareLine().action("D").write()))
                .applied();
        assertThat(applied.unknownDeletes()).isEqualTo(1);
        assertThat(applied.deleted()).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void aChangeReplacesTheOneItAmendsRatherThanStackingOnIt() {
        TariffStore store = new TariffStore();
        FareFeed.loadInto(store, payload(fareLine().action("A").amount("420.00").write()));
        FaresApplied applied = FareFeed.loadInto(
                        store, payload(fareLine().action("C").amount("380.00").write()))
                .applied();
        assertThat(applied.changed()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.allFares().get(0).amount()).isEqualTo(Money.of("380.00", "EUR"));
    }

    @Test
    void aChangeForAFareWeDoNotHoldIsAddedAndSaidSo() {
        TariffStore store = new TariffStore();
        FaresApplied applied = FareFeed.loadInto(
                        store, payload(fareLine().action("C").write()))
                .applied();
        assertThat(applied.changedAsAdds()).isEqualTo(1);
        assertThat(applied.added()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aDeleteOnlyTakesTheFilingItNames() {
        // Same fare basis, two sale windows. A withdrawal names one of them and
        // matching on the basis alone would take next winter's fare with it.
        TariffStore store = new TariffStore();
        FareFeed.loadInto(
                store,
                payload(
                        fareLine()
                                .action("A")
                                .effective(LocalDate.of(2025, 1, 1))
                                .discontinue(LocalDate.of(2025, 6, 30))
                                .write(),
                        fareLine()
                                .action("A")
                                .effective(LocalDate.of(2025, 7, 1))
                                .discontinue(LocalDate.of(2025, 12, 31))
                                .write()));
        assertThat(store.size()).isEqualTo(2);
        FaresApplied applied = FareFeed.loadInto(
                        store,
                        payload(fareLine()
                                .action("D")
                                .effective(LocalDate.of(2025, 1, 1))
                                .discontinue(LocalDate.of(2025, 6, 30))
                                .write()))
                .applied();
        assertThat(applied.deleted()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.allFares().get(0).effective()).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void applyingALoadTwiceDoesNotDoubleTheStore() {
        TariffStore store = new TariffStore();
        String file = payload(
                fareLine().action("A").write(),
                fareLine().action("A").basis("MLWAP7RT").write());
        FareFeed.loadInto(store, file);
        FareFeed.loadInto(store, file);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void readingNeverTouchesTheStore() {
        TariffStore store = new TariffStore();
        FareLoad load = FareFeed.read(payload(
                fareLine().action("A").write(),
                fareLine().action("D").basis("MLWAP7RT").write()));
        assertThat(store.size()).isZero();
        FaresApplied applied = FareFeed.apply(store, load);
        assertThat(applied.added()).isEqualTo(1);
    }

    @Test
    void whatWasAppliedReadsBackInOneLine() {
        TariffStore store = new TariffStore();
        FaresApplied applied = FareFeed.loadInto(
                        store,
                        payload(
                                fareLine().action("A").write(),
                                fareLine().action("D").basis("MLWAP7RT").write()))
                .applied();
        assertThat(applied.touched()).isEqualTo(1);
        assertThat(applied).hasToString("1 added, 0 changed, 0 deleted, 1 deletes for fares we do not hold");
    }

    @Test
    void aShortLineIsAProblemAndTheRestOfTheFileStillLoads() {
        TariffStore store = new TariffStore();
        Loaded<FareLoad, FaresApplied> loaded =
                FareFeed.loadInto(store, payload(fareLine().write(), "FR" + " ".repeat(10)));
        assertThat(loaded.applied().added()).isEqualTo(1);
        assertThat(loaded.load().problems()).hasSize(1);
    }

    @Test
    void aRecordTagNobodyDeclaredIsAProblemAndNotAStop() {
        TariffStore store = new TariffStore();
        Loaded<FareLoad, FaresApplied> loaded = FareFeed.loadInto(
                store, payload("ZZ" + " ".repeat(20), fareLine().write()));
        assertThat(loaded.applied().added()).isEqualTo(1);
        assertThat(loaded.load().problems()).hasSize(1);
    }
}
