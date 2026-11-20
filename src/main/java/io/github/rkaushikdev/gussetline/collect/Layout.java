package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Text;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A set of record specs read as one fixed width payload, which is what the pricing gateway still
 * sends and what the tariff filings arrive in.
 *
 * <p>The format is from the seventies and it has not moved: a record tag, then fields at documented
 * offsets, one record per line, and a whole response is a run of records that only makes sense in
 * order. Every gateway's layout is different, so what is here is a way to declare a layout rather
 * than a parser for one.
 *
 * <p>Two things this refuses to do, both learned the hard way.
 *
 * <p>It does not strip a field before deciding whether it is empty. A quantity field of four spaces
 * is missing and a quantity field of four zeroes is zero, and a parser that strips first cannot tell
 * them apart. So the raw slice is kept and the emptiness test is on the raw.
 *
 * <p>And it does not skip a line it cannot read. An unknown tag, a short line, a number with a letter
 * in it are all recorded as problems against the line number, and the read continues. A supplier
 * that adds a record type should not cost us the whole payload, and a supplier that corrupts one
 * should not do it silently.
 */
public final class Layout {

    /** How many characters open a line as its tag unless a layout says otherwise. */
    public static final int TAG_WIDTH = 2;

    private final int tagWidth;
    private final Map<String, RecordSpec> specs = new LinkedHashMap<>();

    /** A layout whose tags are two characters. */
    public Layout(Iterable<RecordSpec> specs) {
        this(specs, TAG_WIDTH);
    }

    /** A layout whose tags are {@code tagWidth} characters. A later spec replaces one with its tag. */
    public Layout(Iterable<RecordSpec> specs, int tagWidth) {
        this.tagWidth = tagWidth;
        for (RecordSpec spec : specs) {
            this.specs.put(spec.tag(), spec);
        }
    }

    public int tagWidth() {
        return tagWidth;
    }

    /** The spec for a tag, when the layout has one. */
    public Optional<RecordSpec> specFor(String tag) {
        return Optional.ofNullable(specs.get(tag));
    }

    /** Every tag the layout knows, in order. */
    public List<String> tags() {
        return specs.keySet().stream().sorted().toList();
    }

    /**
     * Read a whole payload sent as bytes, keeping problems rather than throwing. The bytes are UTF-8,
     * and a sequence that is not becomes a replacement character rather than a refusal.
     */
    public Reading read(byte[] payload) {
        return read(new String(payload, StandardCharsets.UTF_8));
    }

    /** Read a whole payload, keeping problems rather than throwing. Blank lines are passed over. */
    public Reading read(String payload) {
        List<Record> records = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        List<String> lines = Text.lines(payload);

        for (int index = 0; index < lines.size(); index++) {
            int number = index + 1;
            String line = lines.get(index);
            if (Text.isBlank(line)) {
                continue;
            }
            String tag = Text.slice(line, 0, tagWidth);
            RecordSpec spec = specs.get(tag);
            if (spec == null) {
                problems.add(new Problem(number, line, "unknown record tag " + Text.repr(tag)));
                continue;
            }
            int length = Text.length(line);
            if (length < spec.width()) {
                problems.add(new Problem(number, line, "line is " + length + " and the layout wants " + spec.width()));
                continue;
            }
            readLine(spec, line, number, problems).ifPresent(records::add);
        }

        return new Reading(records, problems);
    }

    private static Optional<Record> readLine(RecordSpec spec, String line, int number, List<Problem> problems) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field column : spec.fields()) {
            String raw = column.sliceFrom(line);
            if (Text.isBlank(raw)) {
                if (column.optional()) {
                    continue;
                }
                if (column.kind().readsBlank()) {
                    values.put(column.name(), column.kind().read(raw));
                    continue;
                }
                problems.add(new Problem(number, line, column.name() + " is empty and is not optional"));
                return Optional.empty();
            }
            try {
                values.put(column.name(), column.kind().read(raw));
            } catch (IllegalArgumentException unreadable) {
                problems.add(new Problem(number, line, column.name() + " did not read: " + unreadable.getMessage()));
                return Optional.empty();
            }
        }
        return Optional.of(new Record(spec.tag(), values, number));
    }
}
