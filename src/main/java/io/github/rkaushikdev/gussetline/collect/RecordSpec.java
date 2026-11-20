package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A record tag and the fields that follow it, which is one line of a fixed width layout's spec
 * sheet.
 *
 * @param tag the characters that open every line of this record
 * @param fields the fields, in the order the sheet lists them
 */
public record RecordSpec(String tag, List<Field> fields) {

    public RecordSpec {
        fields = List.copyOf(fields);
    }

    /** A record declared field by field, which reads like the spec sheet. */
    public RecordSpec(String tag, Field... fields) {
        this(tag, List.of(fields));
    }

    /** How long a line must be to hold every field: the furthest field end, or the tag alone. */
    public int width() {
        return fields.stream().mapToInt(Field::end).max().orElse(Text.length(tag));
    }

    /**
     * Render one record, which is how a request goes back to the gateway.
     *
     * <p>The same offsets read and write, so a layout that can read a response can build a request,
     * and a round trip through both is a test that the offsets agree with themselves. Two of the
     * three suppliers take their requests in the same format they answer in.
     *
     * <p>Text is cut to its field. A field with no value is written as spaces when it is optional.
     *
     * @param values the value for each field, by name; a missing or null value is absent
     * @throws MalformedPayload when a required field has no value or a value does not fit its field,
     *     naming the field
     * @throws IllegalArgumentException when a value is not one its field's kind can write
     */
    public String write(Map<String, ?> values) {
        int[] line = new int[Math.max(width(), Text.length(tag))];
        Arrays.fill(line, ' ');
        int[] opening = tag.codePoints().toArray();
        System.arraycopy(opening, 0, line, 0, opening.length);

        for (Field column : fields) {
            Object value = values.get(column.name());
            String rendered;
            if (value == null) {
                if (!column.optional()) {
                    throw new MalformedPayload("a required field was not given", Details.of("field", column.name()));
                }
                rendered = " ".repeat(column.length());
            } else {
                rendered = column.kind().write(value, column.length());
            }
            int[] characters = rendered.codePoints().toArray();
            if (characters.length != column.length()) {
                throw new MalformedPayload(
                        "a value did not fit its field",
                        Details.of("field", column.name(), "width", column.length(), "rendered", rendered));
            }
            System.arraycopy(characters, 0, line, column.start(), characters.length);
        }
        return new String(line, 0, line.length);
    }
}
