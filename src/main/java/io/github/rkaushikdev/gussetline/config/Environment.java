package io.github.rkaushikdev.gussetline.config;

import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one setting at a time out of an environment, under the {@link Settings#PREFIX}.
 *
 * <p>A variable that is not set reads as the fallback. A variable that is set and cannot be read
 * is refused with a {@link ConfigError} naming the variable and what it held, because "not a
 * number" on its own sends somebody through seventeen of them.
 */
final class Environment {

    private final Map<String, String> values;

    Environment(Map<String, String> values) {
        this.values = values;
    }

    /** The text as set, untouched, or the fallback. */
    String text(String name, String fallback) {
        String raw = values.get(Settings.PREFIX + name);
        return raw == null ? fallback : raw;
    }

    /** A whole number, white space at the ends and underscores between digits allowed. */
    int wholeNumber(String name, int fallback) {
        String raw = values.get(Settings.PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Numbers.parseInt(raw);
        } catch (NumberFormatException refused) {
            throw new ConfigError("not a whole number", Details.of("setting", Settings.PREFIX + name, "value", raw));
        }
    }

    /** A number with or without a fraction, including {@code inf} and {@code nan}. */
    double number(String name, double fallback) {
        String raw = values.get(Settings.PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Numbers.parseFloat(raw);
        } catch (NumberFormatException refused) {
            throw new ConfigError("not a number", Details.of("setting", Settings.PREFIX + name, "value", raw));
        }
    }

    /** An exact amount, kept at the scale it was written in. */
    BigDecimal amount(String name, BigDecimal fallback) {
        String raw = values.get(Settings.PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Numbers.parseDecimal(raw);
        } catch (NumberFormatException refused) {
            throw new ConfigError("not an amount", Details.of("setting", Settings.PREFIX + name, "value", raw));
        }
    }

    /**
     * A yes or a no: {@code 1}, {@code yes}, {@code true} or {@code on}, and {@code 0}, {@code no},
     * {@code false} or {@code off}, in any case and with white space at the ends.
     */
    boolean flag(String name, boolean fallback) {
        String raw = values.get(Settings.PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        String said = Text.strip(raw).toLowerCase(Locale.ROOT);
        return switch (said) {
            case "1", "yes", "true", "on" -> true;
            case "0", "no", "false", "off" -> false;
            default ->
                throw new ConfigError("not a yes or no", Details.of("setting", Settings.PREFIX + name, "value", said));
        };
    }
}
