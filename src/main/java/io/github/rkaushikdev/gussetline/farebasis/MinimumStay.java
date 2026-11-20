package io.github.rkaushikdev.gussetline.farebasis;

import java.util.Objects;

/**
 * The shortest stay a fare basis code names, such as {@code MIN3D} for three days.
 *
 * @param count how many units
 * @param unit {@code "days"} or {@code "months"}
 */
public record MinimumStay(int count, String unit) {

    public MinimumStay {
        Objects.requireNonNull(unit, "unit");
    }
}
