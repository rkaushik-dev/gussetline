package io.github.rkaushikdev.gussetline.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One thing wrong with a request, as an entry of the 422 refusal's {@code detail} list.
 *
 * @param type the kind of problem: {@code missing}, {@code string_too_short}, {@code int_parsing}
 * @param loc where it is: {@code query}, {@code path} or {@code body}, then the field names and list
 *     positions down to the value
 * @param msg the sentence a caller is shown
 * @param input the value that was refused as the caller sent it, {@code null} when there was none
 * @param ctx the figures the sentence names, such as {@code min_length}; empty when it names none
 */
record Problem(String type, List<Object> loc, String msg, Object input, Map<String, Object> ctx) {

    Problem {
        Objects.requireNonNull(type, "type");
        loc = Collections.unmodifiableList(new ArrayList<>(loc));
        Objects.requireNonNull(msg, "msg");
        ctx = Collections.unmodifiableMap(new LinkedHashMap<>(ctx));
    }

    Problem(String type, List<Object> loc, String msg, Object input) {
        this(type, loc, msg, input, Map.of());
    }

    /** A problem in the same place with the same words, but another value. */
    Problem withInput(Object value) {
        return new Problem(type, loc, msg, value, ctx);
    }

    /** The entry as the refusal writes it; {@code ctx} only when there is something in it. */
    Map<String, Object> asMap() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("loc", loc);
        entry.put("msg", msg);
        entry.put("input", input);
        if (!ctx.isEmpty()) {
            entry.put("ctx", ctx);
        }
        return entry;
    }
}
