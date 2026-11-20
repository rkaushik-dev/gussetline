package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** Two components that must join are not allowed to. */
public class CombinabilityError extends ConstructionError {

    public CombinabilityError(String message) {
        super(message);
    }

    public CombinabilityError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "pricing.combinability";
    }
}
