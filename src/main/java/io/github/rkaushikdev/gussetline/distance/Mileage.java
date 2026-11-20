package io.github.rkaushikdev.gussetline.distance;

import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import java.util.Objects;
import java.util.Optional;

/**
 * One mileage reading, and where it came from.
 *
 * @param miles the statute miles
 * @param published whether the figure is a published ticketed point mileage; false means a great
 *     circle stood in for one
 * @param indicator the global indicator the reading was asked for, if one was
 */
public record Mileage(int miles, boolean published, Optional<GlobalIndicator> indicator) {

    public Mileage {
        Objects.requireNonNull(indicator, "indicator");
    }

    /** A reading asked for without an indicator. */
    public Mileage(int miles, boolean published) {
        this(miles, published, Optional.empty());
    }

    /** Whether a great circle stood in, which every surcharge computed from it has to say. */
    public boolean isFallback() {
        return !published;
    }
}
