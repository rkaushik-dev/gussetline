package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The write would contradict a record that is already there. */
public class ConflictError extends GussetlineError {

    public ConflictError(String message) {
        super(message);
    }

    public ConflictError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "record.conflict";
    }
}
