package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The tariff cannot answer the question asked of it. */
public class TariffError extends GussetlineError {

    public TariffError(String message) {
        super(message);
    }

    public TariffError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "tariff.error";
    }
}
