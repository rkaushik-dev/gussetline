package io.github.rkaushikdev.gussetline.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON text in the one form the desk stores it, and read back into plain values.
 *
 * <p>Stored JSON lines are compared, deduplicated and rewritten whole, so the form is fixed: object
 * keys in code point order at every depth, a space after every comma and colon, and nothing but
 * printable ASCII. Every other character is written as a <code>&#92;u00e9</code> escape in lower
 * case hex, a character beyond the Basic Multilingual Plane as its surrogate pair, except the five
 * with a short escape of their own ({@code \b \f \n \r \t}). Floating point numbers are written as {@link
 * Numbers#floatText} writes them, with {@code NaN}, {@code Infinity} and {@code -Infinity} for the
 * three values that have no number text.
 *
 * <p>Reading gives back maps that keep the document's key order, lists, text, {@code Boolean}s,
 * {@code null}, whole numbers as the smallest of {@code Integer}, {@code Long} and {@code
 * BigInteger} that holds them, and any other number as a {@code Double}. What is read cannot be
 * changed.
 */
public final class Json {

    /** One whole document, nothing after it, and the three non-finite numbers allowed. */
    private static final ObjectMapper READER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Json() {}

    /**
     * A value as JSON text in the stored form.
     *
     * <p>A value may be {@code null}, a {@code Boolean}, text, an {@code Integer}, {@code Long},
     * {@code Short}, {@code Byte} or {@code BigInteger}, a {@code Double} or {@code Float}, a {@code
     * List} of values, or a {@code Map} from text to values.
     *
     * @throws IllegalArgumentException when the value, or anything inside it, is none of those, or a
     *     map has a key that is not text
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /**
     * Read one JSON object.
     *
     * @throws IllegalArgumentException when the text is not one JSON document, or the document is
     *     not an object
     */
    public static Map<String, Object> readObject(String text) {
        JsonNode document;
        try {
            document = READER.readTree(text);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalArgumentException("not JSON: " + unreadable.getOriginalMessage(), unreadable);
        }
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("not a JSON object: " + Text.quote(text));
        }
        return object(document);
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean flag -> out.append(flag ? "true" : "false");
            case CharSequence text -> quoted(text.toString(), out);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Short number -> out.append(number);
            case Byte number -> out.append(number);
            case BigInteger number -> out.append(number);
            case Double number -> real(number, out);
            case Float number -> real(number.doubleValue(), out);
            case List<?> items -> array(items, out);
            case Map<?, ?> members -> object(members, out);
            default ->
                throw new IllegalArgumentException("a " + value.getClass().getSimpleName() + " has no JSON form");
        }
    }

    private static void real(double number, StringBuilder out) {
        if (Double.isNaN(number)) {
            out.append("NaN");
        } else if (Double.isInfinite(number)) {
            out.append(number > 0 ? "Infinity" : "-Infinity");
        } else {
            out.append(Numbers.floatText(number));
        }
    }

    private static void array(List<?> items, StringBuilder out) {
        out.append('[');
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            write(items.get(index), out);
        }
        out.append(']');
    }

    private static void object(Map<?, ?> members, StringBuilder out) {
        Map<String, Object> named = new TreeMap<>(Text.CODE_POINT_ORDER);
        for (Map.Entry<?, ?> member : members.entrySet()) {
            if (!(member.getKey() instanceof String key)) {
                throw new IllegalArgumentException("a JSON key is text, not " + Text.repr(member.getKey()));
            }
            named.put(key, member.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> member : named.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            quoted(member.getKey(), out);
            out.append(": ");
            write(member.getValue(), out);
        }
        out.append('}');
    }

    private static void quoted(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < ' ' || character > '~') {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static Map<String, Object> object(JsonNode document) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> member : document.properties()) {
            members.put(member.getKey(), plain(member.getValue()));
        }
        return Collections.unmodifiableMap(members);
    }

    private static Object plain(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> object(value);
            case ARRAY -> {
                List<Object> items = new ArrayList<>(value.size());
                value.forEach(item -> items.add(plain(item)));
                yield Collections.unmodifiableList(items);
            }
            case STRING -> value.textValue();
            case NUMBER -> value.numberValue();
            case BOOLEAN -> value.booleanValue();
            case NULL -> null;
            case BINARY, MISSING, POJO -> throw new IllegalArgumentException("not a JSON value: " + value);
        };
    }
}
