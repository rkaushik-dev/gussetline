package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.GussetlineError;
import java.util.Map;

/** The key is good and does not cover this. */
public class Forbidden extends GussetlineError {

    public Forbidden(String message) {
        super(message);
    }

    public Forbidden(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "auth.forbidden";
    }
}
