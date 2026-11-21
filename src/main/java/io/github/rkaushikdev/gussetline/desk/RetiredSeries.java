package io.github.rkaushikdev.gussetline.desk;

import java.util.Objects;

/**
 * A watch a night's run closed, named by the series it was reading, and why.
 *
 * @param seriesKey the series the watch was reading
 * @param reason why it was closed
 */
public record RetiredSeries(String seriesKey, Retired reason) {

    public RetiredSeries {
        Objects.requireNonNull(seriesKey, "seriesKey");
        Objects.requireNonNull(reason, "reason");
    }
}
