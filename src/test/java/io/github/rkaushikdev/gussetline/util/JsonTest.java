package io.github.rkaushikdev.gussetline.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    /** A manifest line exactly as the archive has always written one. */
    private static final String STORED_LINE = "{\"context\": {\"market\": \"MUC-CHI\", \"note\": \"Z\\u00fcrich \\u2708"
            + " \\\"quoted\\\"\"}, \"digest\": \"81f748ff678a375012a25c4bc744f663\", \"fresh\": true, \"seen_at\":"
            + " \"2025-05-12T09:05:00\", \"size\": 15, \"status\": 504, \"supplier\": \"ndc\", \"url\": \"/v2/offers\"}";

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            members.put((String) pairs[index], pairs[index + 1]);
        }
        return members;
    }

    @Test
    void keysAreWrittenInOrderAtEveryDepth() {
        Map<String, Object> value = ordered(
                "b", 1, "a", ordered("d", Arrays.asList(1, 2.5, null), "c", null), "e", List.of(), "f", Map.of());
        assertThat(Json.write(value))
                .isEqualTo("{\"a\": {\"c\": null, \"d\": [1, 2.5, null]}, \"b\": 1, \"e\": [], \"f\": {}}");
    }

    @Test
    void everythingOutsidePrintableAsciiIsEscaped() {
        String text = "Z\u00fcrich \u2014 \u6771\u4eac \uD83D\uDEEB \"q\" \\ \n\t\r\b\f\u0001\u001f\u007f/";
        assertThat(Json.write(Map.of("t", text)))
                .isEqualTo("{\"t\": \"Z\\u00fcrich \\u2014 \\u6771\\u4eac \\ud83d\\udeeb \\\"q\\\" \\\\"
                        + " \\n\\t\\r\\b\\f\\u0001\\u001f\\u007f/\"}");
    }

    @Test
    void numbersAreWrittenSoTheyReadBackTheSame() {
        List<Object> numbers = List.of(
                1.0,
                1e-05,
                1e16,
                0.1,
                -0.0,
                new BigInteger("123456789012345678901234567890"),
                true,
                false,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY);
        assertThat(Json.write(numbers))
                .isEqualTo("[1.0, 1e-05, 1e+16, 0.1, -0.0, 123456789012345678901234567890, true, false, NaN, Infinity,"
                        + " -Infinity]");
    }

    @Test
    void keysAreOrderedByCodePointAndNotByUtf16Unit() {
        // Ordered by UTF-16 unit, the surrogate pair of U+1F6EB would sort before U+FFFF.
        Map<String, Object> value = ordered("\u00e9", 1, "z", 2, "Z", 3, "\uD83D\uDEEB", 4, "\uffff", 5, "", 6);
        assertThat(Json.write(value))
                .isEqualTo("{\"\": 6, \"Z\": 3, \"z\": 2, \"\\u00e9\": 1, \"\\uffff\": 5, \"\\ud83d\\udeeb\": 4}");
    }

    @Test
    void aValueWithNoJsonFormIsRefused() {
        assertThatThrownBy(() -> Json.write(new BigDecimal("0.04"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Set.of("FRA-NYC"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Map.of(1, "one")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void aStoredLineReadsBackInItsOwnOrderAndWritesBackTheSame() {
        Map<String, Object> row = Json.readObject(STORED_LINE);
        assertThat(row.keySet())
                .containsExactly("context", "digest", "fresh", "seen_at", "size", "status", "supplier", "url");
        assertThat(row.get("context")).isEqualTo(Map.of("market", "MUC-CHI", "note", "Z\u00fcrich \u2708 \"quoted\""));
        assertThat(row.get("fresh")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo(504);
        assertThat(Json.write(row)).isEqualTo(STORED_LINE);
    }

    @Test
    void numbersReadAsTheSmallestTypeThatHoldsThem() {
        Map<String, Object> row = Json.readObject(
                "{\"a\": 504, \"b\": 12345678901, \"c\": 123456789012345678901234567890, \"d\": 1.5, \"e\": NaN}");
        assertThat(row.get("a")).isEqualTo(504);
        assertThat(row.get("b")).isEqualTo(12345678901L);
        assertThat(row.get("c")).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(row.get("d")).isEqualTo(1.5);
        assertThat(row.get("e")).isEqualTo(Double.NaN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "[1]", "5", "{} {}", "{'a': 1}", "not json", "{\"a\": 1"})
    void textThatIsNotOneObjectIsRefused(String text) {
        assertThatThrownBy(() -> Json.readObject(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatIsReadCannotBeChanged() {
        Map<String, Object> row = Json.readObject("{\"a\": [1, null], \"b\": {\"c\": null}}");
        assertThat(row.get("a")).asInstanceOf(LIST).containsExactly(1, null);
        assertThatThrownBy(() -> row.put("z", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) row.get("a")).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) row.get("b")).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
