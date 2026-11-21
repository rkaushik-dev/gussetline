package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Field;
import io.github.rkaushikdev.gussetline.collect.Field.Kind;
import io.github.rkaushikdev.gussetline.collect.Layout;
import io.github.rkaushikdev.gussetline.collect.Problem;
import io.github.rkaushikdev.gussetline.collect.Reading;
import io.github.rkaushikdev.gussetline.collect.Record;
import io.github.rkaushikdev.gussetline.collect.RecordSpec;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * Loading category 10, which is filed against a fare and not against a rule.
 *
 * <p>That is the only structural thing about it and it is the reason it is here rather than in the
 * rule loader. A rule is a numbered set of provisions that any number of fares point at.
 * Combinability is filed per fare basis, so two fares on the same rule can permit entirely different
 * joins, and reading it out of the rule file would give both of them whichever one was filed last.
 *
 * <p>The defaults are the whole safety argument. Everything a record does not mention is refused. A
 * filing that arrives half read produces a fare that combines with nothing, which shows up as a
 * refusal somebody looks at, and the alternative is a fare that quietly combines with everything and
 * prices a round trip a hundred euro light. The one that fails loudly is the one to have.
 *
 * <p>Same carrier only is the exception and it defaults the other way, to true, because it is a
 * restriction rather than a permission. A record that does not mention it is the ordinary case of a
 * carrier that will not combine with anybody else, and defaulting it to false would let every unread
 * filing build an interline construction nobody permitted.
 */
public final class CombinabilityFeed {

    /** The action letter of a record that files its carrier and fare basis. A blank action does too. */
    public static final String ADD = "A";

    /** The action letter of a record that withdraws its carrier and fare basis. */
    public static final String DELETE = "D";

    /**
     * One record: a carrier, a fare basis, and a {@code Y} for each thing the fare permits. A flag
     * left blank is a no.
     */
    public static final RecordSpec COMBINABILITY = new RecordSpec(
            "C10",
            new Field("carrier", 3, 2),
            new Field("fare_basis", 5, 10),
            flag("round_trip", 15),
            flag("circle_trip", 16),
            flag("open_jaw", 17),
            flag("double_open_jaw", 18),
            flag("end_on_end", 19),
            flag("side_trip", 20),
            flag("any_carrier", 21),
            flag("same_rule_only", 22),
            flag("action", 23));

    /** The one record a category 10 file is made of, under a three character tag. */
    public static final Layout LAYOUT = new Layout(List.of(COMBINABILITY), 3);

    private CombinabilityFeed() {}

    /** {@link #read(String)} for a file as it arrived, in UTF-8. */
    public static CombinabilityLoad read(byte[] payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Read a category 10 file without touching a store. A record with no fare basis, or with a carrier
     * that is not a designator, is a problem against its own line, and the rest of the file still
     * reads.
     */
    public static CombinabilityLoad read(String payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Put a read file into a store, whole records at a time: a record filed again replaces the one
     * held, flags and all. The records go in first, then the withdrawals come out.
     *
     * @throws ValidationError when a withdrawal names a carrier that is not a designator
     */
    public static CombinabilityApplied apply(CombinabilityStore store, CombinabilityLoad load) {
        int added = 0;
        int replaced = 0;
        int deleted = 0;
        int unknownDeletes = 0;
        int combineWithNothing = 0;
        for (Combinability record : load.records()) {
            if (store.get(record.carrier(), record.fareBasis()).isPresent()) {
                replaced++;
            } else {
                added++;
            }
            store.add(record);
            if (!(record.roundTrip() || record.circleTrip() || record.openJaw() || record.endOnEnd())) {
                combineWithNothing++;
            }
        }

        for (CombinabilityLoad.Withdrawn gone : load.deleted()) {
            if (store.remove(gone.carrier(), gone.fareBasis())) {
                deleted++;
            } else {
                unknownDeletes++;
            }
        }
        return new CombinabilityApplied(added, replaced, deleted, unknownDeletes, combineWithNothing);
    }

    /** {@link #loadInto(CombinabilityStore, String)} for a file as it arrived, in UTF-8. */
    public static Loaded<CombinabilityLoad, CombinabilityApplied> loadInto(CombinabilityStore store, byte[] payload) {
        return applied(store, read(payload));
    }

    /** Read and apply in one call, for the ordinary case. */
    public static Loaded<CombinabilityLoad, CombinabilityApplied> loadInto(CombinabilityStore store, String payload) {
        return applied(store, read(payload));
    }

    /**
     * A record that permits everything, for a laptop and a first run.
     *
     * <p>Never used by the loader and never reachable from a file. It exists so a test or a
     * demonstration can price a round trip before anybody has filed category 10, and it is
     * deliberately awkward to reach from anywhere else.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public static Combinability permissive(String carrier, String fareBasis) {
        return Combinability.builder(carrier, fareBasis)
                .roundTrip(true)
                .circleTrip(true)
                .openJaw(true)
                .doubleOpenJaw(true)
                .endOnEnd(true)
                .sideTrip(true)
                .sameCarrierOnly(false)
                .build();
    }

    private static Loaded<CombinabilityLoad, CombinabilityApplied> applied(
            CombinabilityStore store, CombinabilityLoad load) {
        return new Loaded<>(load, apply(store, load));
    }

    private static CombinabilityLoad read(Reading reading) {
        List<Combinability> records = new ArrayList<>();
        List<CombinabilityLoad.Withdrawn> deleted = new ArrayList<>();
        List<Problem> problems = new ArrayList<>(reading.problems());

        for (Record record : reading.tagged(COMBINABILITY.tag())) {
            try {
                readOne(record, records, deleted);
            } catch (ValidationError | MalformedPayload refused) {
                problems.add(new Problem(record.lineNumber(), record.toString(), refused.message()));
            }
        }
        return new CombinabilityLoad(records, deleted, problems);
    }

    /** File one record as a withdrawal or as a record, or refuse it by itself. */
    private static void readOne(Record record, List<Combinability> records, List<CombinabilityLoad.Withdrawn> deleted) {
        String carrier = Text.code(record.get("carrier", String.class));
        String basis = Text.code(record.get("fare_basis", String.class));
        if (basis.isEmpty()) {
            throw new MalformedPayload("a combinability record names a fare basis", Details.of("carrier", carrier));
        }

        if (flag(record, "action").equals(DELETE)) {
            deleted.add(new CombinabilityLoad.Withdrawn(carrier, basis));
            return;
        }

        records.add(Combinability.builder(carrier, basis)
                .roundTrip(yes(record, "round_trip"))
                .circleTrip(yes(record, "circle_trip"))
                .openJaw(yes(record, "open_jaw"))
                .doubleOpenJaw(yes(record, "double_open_jaw"))
                .endOnEnd(yes(record, "end_on_end"))
                .sideTrip(yes(record, "side_trip"))
                // Filed the other way round. The record says whether any carrier
                // will do, and the domain object says whether only ours will.
                .sameCarrierOnly(!yes(record, "any_carrier"))
                .sameRuleOnly(yes(record, "same_rule_only"))
                .build());
    }

    private static Field flag(String name, int start) {
        return new Field(name, start, 1, Kind.TEXT, true);
    }

    /** A one letter field, upper case, or empty when it was left blank. */
    private static String flag(Record record, String name) {
        return record.find(name, String.class).map(Text::code).orElse("");
    }

    private static boolean yes(Record record, String name) {
        return flag(record, name).equals("Y");
    }
}
