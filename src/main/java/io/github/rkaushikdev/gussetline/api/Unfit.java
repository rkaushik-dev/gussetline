package io.github.rkaushikdev.gussetline.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A value a caller sent that does not read as the type the route takes, with the reason in the words
 * a request refusal carries: a short {@code type} such as {@code int_parsing}, the sentence, and the
 * figures the sentence names.
 */
final class Unfit extends RuntimeException {

    private final String type;
    private final transient Map<String, Object> context;

    Unfit(String type, String message) {
        this(type, message, Map.of());
    }

    Unfit(String type, String message, Map<String, ?> context) {
        super(message, null, false, false);
        this.type = type;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** The kind of refusal: {@code int_parsing}, {@code date_from_datetime_parsing}. */
    String type() {
        return type;
    }

    /** What the refusal names besides the sentence, such as the parser's own {@code error}. */
    Map<String, Object> context() {
        return context;
    }
}
