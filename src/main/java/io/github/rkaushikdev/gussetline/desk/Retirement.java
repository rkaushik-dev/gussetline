package io.github.rkaushikdev.gussetline.desk;

import java.util.Objects;

/**
 * A watch a decision said will never become actionable, and why it should be closed.
 *
 * @param watch the watch to close
 * @param reason why
 */
public record Retirement(Watch watch, Retired reason) {

    public Retirement {
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(reason, "reason");
    }
}
