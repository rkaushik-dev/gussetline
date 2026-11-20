package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** Input did not describe a thing the desk can work with. */
public class ValidationError extends GussetlineError {

    public ValidationError(String message) {
        super(message);
    }

    public ValidationError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "input.invalid";
    }
}
