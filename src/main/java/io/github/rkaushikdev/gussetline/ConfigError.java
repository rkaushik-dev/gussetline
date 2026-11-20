package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** A setting is missing, unparseable or contradicts another setting. */
public class ConfigError extends GussetlineError {

    public ConfigError(String message) {
        super(message);
    }

    public ConfigError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "config.invalid";
    }
}
