package io.github.rkaushikdev.gussetline.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one provision decided, in the words the desk will read.
 *
 * <p>The details carry the specifics the sentence would otherwise bury: the sector that refused, the
 * nights needed and the nights had. They ride along to the trace unchanged.
 *
 * @param category the category that decided
 * @param answer whether the provision passed, failed or did not apply
 * @param message the sentence the desk reads
 * @param details the specifics behind the sentence, in the order given; null values are kept
 */
public record Outcome(Category category, Answer answer, String message, Map<String, Object> details) {

    /** Keeps a read only copy of the details, in their order. */
    public Outcome {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(message, "message");
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** An outcome with no details. */
    public Outcome(Category category, Answer answer, String message) {
        this(category, answer, message, Map.of());
    }

    /** The provision is satisfied, with nothing further to say. */
    public static Outcome passes(Category category, String message) {
        return passes(category, message, Map.of());
    }

    /** The provision is satisfied, and these are the readings it was satisfied with. */
    public static Outcome passes(Category category, String message, Map<String, ?> details) {
        return new Outcome(category, Answer.PASSES, message, new LinkedHashMap<>(details));
    }

    /** The provision refuses the fare, with nothing further to say. */
    public static Outcome fails(Category category, String message) {
        return fails(category, message, Map.of());
    }

    /** The provision refuses the fare, and these are the readings it refused on. */
    public static Outcome fails(Category category, String message, Map<String, ?> details) {
        return new Outcome(category, Answer.FAILS, message, new LinkedHashMap<>(details));
    }

    /** The provision does not apply because nothing is filed in it. */
    public static Outcome silent(Category category) {
        return silent(category, "no provision filed");
    }

    /** The provision does not apply, for the reason given. */
    public static Outcome silent(Category category, String message) {
        return new Outcome(category, Answer.NOT_APPLICABLE, message);
    }

    /** Whether the provision refused the fare. */
    public boolean failed() {
        return answer == Answer.FAILS;
    }

    /** Whether the provision had anything to say, passing or failing. */
    public boolean applied() {
        return answer != Answer.NOT_APPLICABLE;
    }

    /** The category, the answer and the sentence: {@code cat 6 fails, wants seven}. */
    @Override
    public String toString() {
        return category.label() + " " + answer.value() + ", " + message;
    }
}
