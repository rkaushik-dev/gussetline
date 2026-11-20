package io.github.rkaushikdev.gussetline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The specifics that travel beside an error's sentence or a log event, kept in the order they were
 * written.
 *
 * <p>{@link Map#of} would do, except that it forgets the order and refuses a null, and a detail
 * whose value is absent is still worth naming.
 */
public final class Details {

    private Details() {}

    /** No details at all. */
    public static Map<String, Object> none() {
        return Map.of();
    }

    public static Map<String, Object> of(String key, Object value) {
        return ordered(key, value);
    }

    public static Map<String, Object> of(String k1, Object v1, String k2, Object v2) {
        return ordered(k1, v1, k2, v2);
    }

    public static Map<String, Object> of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return ordered(k1, v1, k2, v2, k3, v3);
    }

    public static Map<String, Object> of(
            String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4) {
        return ordered(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    public static Map<String, Object> of(
            String k1,
            Object v1,
            String k2,
            Object v2,
            String k3,
            Object v3,
            String k4,
            Object v4,
            String k5,
            Object v5) {
        return ordered(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    public static Map<String, Object> of(
            String k1,
            Object v1,
            String k2,
            Object v2,
            String k3,
            Object v3,
            String k4,
            Object v4,
            String k5,
            Object v5,
            String k6,
            Object v6) {
        return ordered(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            details.put((String) pairs[index], pairs[index + 1]);
        }
        return Collections.unmodifiableMap(details);
    }
}
