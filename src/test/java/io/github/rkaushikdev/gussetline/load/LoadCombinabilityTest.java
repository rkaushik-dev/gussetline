package io.github.rkaushikdev.gussetline.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadCombinabilityTest {

    /**
     * One category 10 record, at the values most tests want: LH, KLWAP7RT, round trips permitted and
     * every other flag left blank. Each setter changes one flag before the record is written; a null
     * leaves the field blank.
     */
    private static final class Line {

        private final Map<String, Object> values = new HashMap<>();

        private Line() {
            values.put("carrier", "LH");
            values.put("fare_basis", "KLWAP7RT");
            values.put("round_trip", "Y");
        }

        Line carrier(String code) {
            return with("carrier", code);
        }

        Line basis(String code) {
            return with("fare_basis", code);
        }

        Line roundTrip(String flag) {
            return with("round_trip", flag);
        }

        Line circleTrip(String flag) {
            return with("circle_trip", flag);
        }

        Line openJaw(String flag) {
            return with("open_jaw", flag);
        }

        Line doubleOpenJaw(String flag) {
            return with("double_open_jaw", flag);
        }

        Line endOnEnd(String flag) {
            return with("end_on_end", flag);
        }

        Line sideTrip(String flag) {
            return with("side_trip", flag);
        }

        Line anyCarrier(String flag) {
            return with("any_carrier", flag);
        }

        Line sameRuleOnly(String flag) {
            return with("same_rule_only", flag);
        }

        Line action(String flag) {
            return with("action", flag);
        }

        String write() {
            return CombinabilityFeed.COMBINABILITY.write(values);
        }

        private Line with(String name, String value) {
            values.put(name, value);
            return this;
        }
    }

    private static Line line() {
        return new Line();
    }

    private static String fileOf(String... lines) {
        return String.join("\n", lines);
    }

    private static Combinability firstRecord(Line line) {
        return CombinabilityFeed.read(line.write()).records().get(0);
    }

    @Test
    void anEmptyFileReadsAsNothing() {
        CombinabilityLoad load = CombinabilityFeed.read("");
        assertThat(load.size()).isZero();
        assertThat(load.clean()).isTrue();
    }

    @Test
    void aRecordReadsIntoTheObjectTheEngineUses() {
        Combinability record = firstRecord(line().roundTrip("Y").circleTrip("Y"));
        assertThat(record.carrier()).isEqualTo("LH");
        assertThat(record.fareBasis()).isEqualTo("KLWAP7RT");
        assertThat(record.roundTrip()).isTrue();
        assertThat(record.circleTrip()).isTrue();
    }

    @Test
    void everythingARecordDoesNotMentionIsRefused() {
        // A half read filing has to produce a fare that combines with nothing.
        // The alternative prices a round trip a hundred euro light in silence.
        Combinability record = firstRecord(line().roundTrip(null));
        assertThat(record.roundTrip()).isFalse();
        assertThat(record.circleTrip()).isFalse();
        assertThat(record.openJaw()).isFalse();
        assertThat(record.endOnEnd()).isFalse();
        assertThat(record.sideTrip()).isFalse();
    }

    @Test
    void sameCarrierOnlyDefaultsTheOtherWayBecauseItIsARestriction() {
        assertThat(firstRecord(line()).sameCarrierOnly()).isTrue();
    }

    @Test
    void aRecordThatSaysAnyCarrierLiftsTheRestriction() {
        assertThat(firstRecord(line().anyCarrier("Y")).sameCarrierOnly()).isFalse();
    }

    @Test
    void everyFlagCanBeFiled() {
        Combinability record = firstRecord(line().circleTrip("Y")
                .openJaw("Y")
                .doubleOpenJaw("Y")
                .endOnEnd("Y")
                .sideTrip("Y")
                .sameRuleOnly("Y"));
        assertThat(record.circleTrip()).isTrue();
        assertThat(record.openJaw()).isTrue();
        assertThat(record.doubleOpenJaw()).isTrue();
        assertThat(record.endOnEnd()).isTrue();
        assertThat(record.sideTrip()).isTrue();
        assertThat(record.sameRuleOnly()).isTrue();
    }

    @Test
    void anythingThatIsNotAYesIsANo() {
        Combinability record = firstRecord(line().roundTrip("N").circleTrip("X"));
        assertThat(record.roundTrip()).isFalse();
        assertThat(record.circleTrip()).isFalse();
    }

    @Test
    void aRecordWithNoFareBasisIsOneBadRecord() {
        CombinabilityLoad load =
                CombinabilityFeed.read(fileOf(line().write(), line().basis("").write()));
        assertThat(load.size()).isEqualTo(1);
        assertThat(load.problems()).hasSize(1);
        assertThat(load.problems().get(0).reason()).contains("names a fare basis");
    }

    @Test
    void theRecordsLandInAStoreTheEngineCanRead() {
        CombinabilityStore store = new CombinabilityStore();
        CombinabilityFeed.loadInto(
                store, fileOf(line().write(), line().basis("MLWAP7RT").write()));
        assertThat(store.bases("LH")).containsExactly("KLWAP7RT", "MLWAP7RT");
        assertThat(store.get("LH", "KLWAP7RT"))
                .hasValueSatisfying(found ->
                        assertThat(found.permits(JourneyType.ROUND_TRIP)).isTrue());
    }

    @Test
    void aRefiledRecordReplacesTheOldOneWhole() {
        CombinabilityStore store = new CombinabilityStore();
        CombinabilityFeed.loadInto(store, line().roundTrip("Y").circleTrip("Y").write());
        CombinabilityApplied applied =
                CombinabilityFeed.loadInto(store, line().roundTrip("Y").write()).applied();
        assertThat(applied.replaced()).isEqualTo(1);
        assertThat(store.get("LH", "KLWAP7RT"))
                .hasValueSatisfying(found -> assertThat(found.circleTrip()).isFalse());
    }

    @Test
    void aDeletedRecordLeavesTheStore() {
        CombinabilityStore store = new CombinabilityStore();
        CombinabilityFeed.loadInto(store, line().write());
        CombinabilityApplied applied =
                CombinabilityFeed.loadInto(store, line().action("D").write()).applied();
        assertThat(applied.deleted()).isEqualTo(1);
        assertThat(store.get("LH", "KLWAP7RT")).isEmpty();
    }

    @Test
    void aDeleteForARecordWeNeverHeldIsCountedAndIgnored() {
        CombinabilityApplied applied = CombinabilityFeed.loadInto(
                        new CombinabilityStore(), line().action("D").write())
                .applied();
        assertThat(applied.unknownDeletes()).isEqualTo(1);
        assertThat(applied.deleted()).isZero();
    }

    @Test
    void aDeleteCarriesNoFlagsWithIt() {
        CombinabilityLoad load =
                CombinabilityFeed.read(line().roundTrip("Y").action("D").write());
        assertThat(load.records()).isEmpty();
        assertThat(load.deleted()).containsExactly(new CombinabilityLoad.Withdrawn("LH", "KLWAP7RT"));
    }

    @Test
    void theOnesThatCombineWithNothingAreCountedForTheOperator() {
        CombinabilityApplied applied = CombinabilityFeed.loadInto(
                        new CombinabilityStore(),
                        fileOf(
                                line().write(),
                                line().basis("MLWAP7RT").roundTrip(null).write()))
                .applied();
        assertThat(applied.combineWithNothing()).isEqualTo(1);
        assertThat(applied.added()).isEqualTo(2);
    }

    @Test
    void twoCarriersAreTwoRecordsAtTheSameFareBasis() {
        CombinabilityStore store = new CombinabilityStore();
        CombinabilityFeed.loadInto(
                store, fileOf(line().carrier("LH").write(), line().carrier("UA").write()));
        assertThat(store.get("LH", "KLWAP7RT")).isPresent();
        assertThat(store.get("UA", "KLWAP7RT")).isPresent();
    }

    @Test
    void readingNeverTouchesTheStore() {
        CombinabilityStore store = new CombinabilityStore();
        CombinabilityLoad load = CombinabilityFeed.read(line().write());
        assertThat(store.get("LH", "KLWAP7RT")).isEmpty();
        assertThat(CombinabilityFeed.apply(store, load).added()).isEqualTo(1);
    }

    @Test
    void aLoadReadsBackInOneLine() {
        CombinabilityLoad load = CombinabilityFeed.read(
                fileOf(line().write(), line().basis("MLWAP7RT").action("D").write()));
        assertThat(load).hasToString("1 records, 1 deleted, 0 problems");
    }

    @Test
    void whatWasAppliedReadsBackInOneLine() {
        CombinabilityApplied applied = CombinabilityFeed.loadInto(new CombinabilityStore(), line().write())
                .applied();
        assertThat(applied).hasToString("1 added, 0 replaced, 0 deleted, 0 that combine with nothing");
    }

    @Test
    void thePermissiveRecordIsForALaptopAndPermitsEverything() {
        Combinability record = CombinabilityFeed.permissive("LH", "KLWAP7RT");
        for (JourneyType kind : List.of(JourneyType.ROUND_TRIP, JourneyType.CIRCLE_TRIP, JourneyType.OPEN_JAW)) {
            assertThat(record.permits(kind)).as(kind.toString()).isTrue();
        }
        assertThat(record.endOnEnd()).isTrue();
        assertThat(record.sameCarrierOnly()).isFalse();
    }
}
