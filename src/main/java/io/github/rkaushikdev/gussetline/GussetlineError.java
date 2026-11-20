package io.github.rkaushikdev.gussetline;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The root of the error taxonomy: everything the desk refuses on purpose.
 *
 * <p>Each kind carries a short stable code, because the codes end up in the audit trail, in the
 * HTTP error body and in the reliability report, and renaming one of those means rewriting history
 * that has already been signed off.
 *
 * <p>The split that matters is between an error that says the input was wrong and an error that
 * says we could not answer. A supplier that times out is not a bad market and must not be reported
 * as one, or the coverage report quietly starts blaming the analyst for the network.
 *
 * <p>The details carry the specifics a message would otherwise bury in prose. They are kept in the
 * order they were given, which is the order an HTTP body writes them in.
 */
public class GussetlineError extends RuntimeException {

    private final String message;
    private final transient Map<String, Object> details;

    /** A refusal with nothing beside the sentence. */
    public GussetlineError(String message) {
        this(message, Map.of());
    }

    /** A refusal and the specifics behind it. Null values are kept. */
    public GussetlineError(String message, Map<String, ?> details) {
        super(rendered(message, details));
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** The stable code for this kind of refusal. Every kind overrides it. */
    public String code() {
        return "gussetline.error";
    }

    /** Whether trying the same call again could plausibly succeed. */
    public boolean retryable() {
        return false;
    }

    /** The sentence alone, without the details. */
    public String message() {
        return message;
    }

    /** The specifics behind the sentence, in the order given. Read only. */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * The shape the HTTP layer and the audit trail both write: code, message and a copy of the
     * details, so a caller that edits the map cannot reach back into the error.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code());
        body.put("message", message);
        body.put("details", new LinkedHashMap<>(details));
        return body;
    }

    /**
     * The message, then the details sorted by name in brackets, each value in its quoted form:
     * {@code no fare (carrier='LH' origin='FRA')}.
     */
    @Override
    public String toString() {
        return getMessage();
    }

    private static String rendered(String message, Map<String, ?> details) {
        if (details.isEmpty()) {
            return message;
        }
        String pairs = new TreeMap<String, Object>(details)
                .entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + Text.repr(entry.getValue()))
                        .collect(Collectors.joining(" "));
        return message + " (" + pairs + ")";
    }
}
