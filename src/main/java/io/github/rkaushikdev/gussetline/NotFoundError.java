package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** A record the caller named does not exist. */
public class NotFoundError extends GussetlineError {

    public NotFoundError(String message) {
        super(message);
    }

    public NotFoundError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "record.not_found";
    }
}
