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
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loading a fare filing feed, which is an amendment file and not a snapshot.
 *
 * <p>This is the distinction that matters, and getting it wrong once cost a month of quietly stale
 * prices. A filing feed does not describe the tariff. It describes the changes to it since the last
 * one, and every record carries an action saying which: add, change, or delete. Loading one as
 * though it were a full file leaves every deleted fare live in the store forever, and a deleted fare
 * is exactly the one a carrier withdrew because it was too cheap, so the desk goes on quoting a
 * price nobody will sell.
 *
 * <p>Three consequences, and all three are here rather than in the caller.
 *
 * <p>A delete for a fare we do not hold is not an error. Feeds are replayed, files overlap at the
 * edges, and we do not load every market. It is counted so a number that suddenly jumps says
 * something, and it is otherwise ignored.
 *
 * <p>A change for a fare we do not hold is an add. The alternative is to drop it and wait for a full
 * refresh that may be a week away, which means the desk prices off a fare the carrier has already
 * amended.
 *
 * <p>And a record the layout can read but the tariff refuses, a sale window that closes before it
 * opens or an amount of nothing, is one bad record and not a bad file. It goes in the problems list
 * against its line number, the rest of the file loads, and somebody reads the list in the morning.
 *
 * <p>The other thing worth stating is that a load is worked out in full before any of it is applied
 * ({@link #read} then {@link #apply}). A file that fails halfway through would otherwise leave the
 * store holding the first half of an amendment, which is worse than holding none of it, because
 * nothing afterwards can tell that is what happened.
 */
public final class FareFeed {

    /** The action letter of a fare the tariff did not have before. */
    public static final String ADD = "A";

    /** The action letter of an amendment to a fare the tariff already has. */
    public static final String CHANGE = "C";

    /** The action letter of a fare the carrier has withdrawn. */
    public static final String DELETE = "D";

    /** Fare types by the letter the feed spells them with, which is not how the tariff spells them. */
    public static final Map<String, FareType> FARE_TYPES = fareTypes();

    /** One fare: where it goes, what it costs, the rule and routing it is filed under, and when. */
    public static final RecordSpec FARE_RECORD = new RecordSpec(
            "FR",
            new Field("carrier", 2, 2),
            new Field("origin", 4, 3),
            new Field("destination", 7, 3),
            new Field("fare_basis", 10, 8),
            new Field("currency", 18, 3),
            new Field("amount", 21, 9, Kind.AMOUNT),
            new Field("owrt", 30, 1),
            new Field("global_indicator", 31, 2),
            new Field("rule_number", 33, 4),
            new Field("routing_number", 37, 4),
            new Field("tariff_number", 41, 3),
            new Field("footnote", 44, 2, Kind.TEXT, true),
            new Field("cabin", 46, 1),
            new Field("fare_type", 47, 1),
            new Field("effective", 48, 8, Kind.DATE),
            new Field("discontinue", 56, 8, Kind.DATE),
            new Field("first_travel", 64, 8, Kind.DATE),
            new Field("last_travel", 72, 8, Kind.DATE),
            new Field("action", 80, 1));

    /**
     * Which carrier and which day the file is for. It is read and reported rather than trusted,
     * because a file loaded against the wrong carrier looks like a very successful load.
     */
    public static final RecordSpec HEADER_RECORD = new RecordSpec(
            "HD",
            new Field("carrier", 2, 2),
            new Field("filed_on", 4, 8, Kind.DATE),
            new Field("sequence", 12, 6, Kind.INT));

    /** How many records the carrier says it wrote, which {@link FareLoad#counted()} checks. */
    public static final RecordSpec TRAILER_RECORD = new RecordSpec("TR", new Field("records", 2, 6, Kind.INT));

    /** The three records a filing file is made of. */
    public static final Layout LAYOUT = new Layout(List.of(HEADER_RECORD, FARE_RECORD, TRAILER_RECORD));

    private FareFeed() {}

    /** {@link #read(String)} for a file as it arrived, in UTF-8. */
    public static FareLoad read(byte[] payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Read a filing file without touching a store.
     *
     * <p>A record the layout reads but the tariff refuses is a problem against its own line, and the
     * rest of the file still reads.
     *
     * @throws IllegalArgumentException when a record's one way round trip indicator is not a digit
     *     the tariff files; that refusal stops the read rather than becoming one bad record
     */
    public static FareLoad read(String payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Put a read file into the store, all of it or none of it.
     *
     * <p>Nothing here can fail partway: the load was checked when it was read, and every operation
     * below is a write the store already knows how to do. That is the whole reason reading and
     * applying are two calls. A delete takes out the filing it names, a change replaces the one it
     * amends, and an add replaces a copy already held, so applying a file twice does not double the
     * store.
     */
    public static FaresApplied apply(TariffStore store, FareLoad load) {
        int added = 0;
        int changed = 0;
        int deleted = 0;
        int unknownDeletes = 0;
        int changedAsAdds = 0;
        for (Filing filing : load.filings()) {
            if (filing.action() == Filing.Action.DELETED) {
                if (store.remove(filing.fare())) {
                    deleted++;
                } else {
                    unknownDeletes++;
                }
                continue;
            }

            boolean replaced = store.replace(filing.fare());
            if (filing.action() != Filing.Action.CHANGED) {
                added++;
            } else if (replaced) {
                changed++;
            } else {
                changedAsAdds++;
                added++;
            }
        }
        return new FaresApplied(added, changed, deleted, unknownDeletes, changedAsAdds);
    }

    /** {@link #loadInto(TariffStore, String)} for a file as it arrived, in UTF-8. */
    public static Loaded<FareLoad, FaresApplied> loadInto(TariffStore store, byte[] payload) {
        return applied(store, read(payload));
    }

    /** Read and apply in one call, for the ordinary case. */
    public static Loaded<FareLoad, FaresApplied> loadInto(TariffStore store, String payload) {
        return applied(store, read(payload));
    }

    private static Loaded<FareLoad, FaresApplied> applied(TariffStore store, FareLoad load) {
        return new Loaded<>(load, apply(store, load));
    }

    private static FareLoad read(Reading reading) {
        List<Problem> problems = new ArrayList<>(reading.problems());
        Optional<Record> header = reading.first(HEADER_RECORD.tag());
        Optional<Integer> declared =
                reading.first(TRAILER_RECORD.tag()).map(trailer -> trailer.get("records", Integer.class));

        List<Filing> filings = new ArrayList<>();
        for (Record record : reading.tagged(FARE_RECORD.tag())) {
            try {
                filings.add(filing(record));
            } catch (ValidationError | MalformedPayload refused) {
                problems.add(new Problem(record.lineNumber(), record.toString(), refused.message()));
            }
        }

        return new FareLoad(
                header.map(found -> Text.code(found.get("carrier", String.class)))
                        .orElse(""),
                header.map(found -> found.get("filed_on", LocalDate.class)),
                header.map(found -> found.get("sequence", Integer.class)).orElse(0),
                filings,
                problems,
                declared);
    }

    /** Turn one read record into a filing, or refuse it by itself. */
    private static Filing filing(Record record) {
        Filing.Action action = action(text(record, "action"));
        Money amount = new Money(record.get("amount", BigDecimal.class), Text.code(text(record, "currency")));
        OwRt owrt = OwRt.of(Text.strip(text(record, "owrt")));
        GlobalIndicator indicator = indicator(text(record, "global_indicator"));
        String footnote = Text.strip(record.find("footnote", String.class).orElse(""));
        Cabin cabin = cabin(text(record, "cabin"));
        FareType fareType = fareType(text(record, "fare_type"));

        Fare fare = Fare.builder()
                .carrier(text(record, "carrier"))
                .origin(text(record, "origin"))
                .destination(text(record, "destination"))
                .fareBasis(text(record, "fare_basis"))
                .amount(amount)
                .owrt(owrt)
                .globalIndicator(indicator)
                .ruleNumber(Text.strip(text(record, "rule_number")))
                .routingNumber(Text.strip(text(record, "routing_number")))
                .tariffNumber(Text.strip(text(record, "tariff_number")))
                .footnote(footnote)
                .cabin(cabin)
                .fareType(fareType)
                .effective(record.get("effective", LocalDate.class))
                .discontinue(record.get("discontinue", LocalDate.class))
                .firstTravel(record.get("first_travel", LocalDate.class))
                .lastTravel(record.get("last_travel", LocalDate.class))
                .build();
        return new Filing(action, fare, record.lineNumber());
    }

    private static Filing.Action action(String raw) {
        return switch (Text.code(raw)) {
            case ADD -> Filing.Action.ADDED;
            case CHANGE -> Filing.Action.CHANGED;
            case DELETE -> Filing.Action.DELETED;
            default -> throw new MalformedPayload("that is not a filing action", Details.of("action", raw));
        };
    }

    private static GlobalIndicator indicator(String raw) {
        try {
            return GlobalIndicator.of(Text.code(raw));
        } catch (IllegalArgumentException unknown) {
            throw refused("no such global indicator", "indicator", raw, unknown);
        }
    }

    private static Cabin cabin(String raw) {
        try {
            return Cabin.of(Text.code(raw));
        } catch (IllegalArgumentException unknown) {
            throw refused("no such cabin", "cabin", raw, unknown);
        }
    }

    private static FareType fareType(String raw) {
        FareType found = FARE_TYPES.get(Text.code(raw));
        if (found == null) {
            throw new MalformedPayload("no such fare type", Details.of("fare_type", raw));
        }
        return found;
    }

    private static MalformedPayload refused(String message, String detail, String raw, Exception cause) {
        MalformedPayload refusal = new MalformedPayload(message, Details.of(detail, raw));
        refusal.initCause(cause);
        return refusal;
    }

    private static String text(Record record, String name) {
        return record.get(name, String.class);
    }

    private static Map<String, FareType> fareTypes() {
        Map<String, FareType> types = new LinkedHashMap<>();
        types.put("P", FareType.PUBLISHED);
        types.put("V", FareType.PRIVATE);
        types.put("N", FareType.NEGOTIATED);
        types.put("T", FareType.INCLUSIVE_TOUR);
        types.put("C", FareType.CORPORATE);
        return Collections.unmodifiableMap(types);
    }
}
