package io.github.rkaushikdev.gussetline.trace;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One recorded decision.
 *
 * @param stage the part of the price it belongs to
 * @param verdict whether it passed, refused or only noted something
 * @param message the sentence a report prints
 * @param details the specifics, in the order given; null values are kept
 */
public record Step(Stage stage, Verdict verdict, String message, Map<String, Object> details) {

    public Step {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** A step with no details. */
    public Step(Stage stage, Verdict verdict, String message) {
        this(stage, verdict, message, Map.of());
    }

    /** Whether this step refused. */
    public boolean failed() {
        return verdict == Verdict.FAIL;
    }

    /** The shape the audit stores: stage, verdict, message and a copy of the details. */
    public Map<String, Object> asMap() {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("stage", stage.value());
        shape.put("verdict", verdict.value());
        shape.put("message", message);
        shape.put("details", new LinkedHashMap<>(details));
        return shape;
    }

    /**
     * The step as one line: a mark ({@code ok}, {@code no} or blank), the stage padded to thirteen,
     * the message, then the details sorted by name.
     */
    @Override
    public String toString() {
        String mark =
                switch (verdict) {
                    case PASS -> "ok";
                    case FAIL -> "no";
                    case INFO -> "  ";
                };
        String rendered = "";
        if (!details.isEmpty()) {
            rendered = " "
                    + new TreeMap<>(details)
                            .entrySet().stream()
                                    .map(entry -> entry.getKey() + "=" + Text.plain(entry.getValue()))
                                    .collect(Collectors.joining(" "));
        }
        return String.format(Locale.ROOT, "%s %-13s %s%s", mark, stage.value(), message, rendered);
    }
}
