package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The fare could not be built out of the components offered. */
public class ConstructionError extends GussetlineError {

    public ConstructionError(String message) {
        super(message);
    }

    public ConstructionError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "pricing.construction";
    }
}
