package io.github.rkaushikdev.gussetline.components;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One addition to a component, and what put it there.
 *
 * <p>A single running total was enough until the fare calculation line had to be written, and then
 * it was not: the line shows a Q surcharge in a different place from a mileage surcharge and does
 * not show a higher intermediate point finding as an addition at all. A total cannot be taken apart
 * afterwards, so the parts are kept.
 *
 * <p>Two charges are equal when they have the same reason and the same amount, whatever its scale.
 *
 * @param reason what added it, such as {@code 5M} or {@code Q}
 * @param nuc what it added, in neutral units
 */
public record Charge(String reason, BigDecimal nuc) {

    public Charge {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(nuc, "nuc");
    }

    /** Equal when the reasons match and the amounts are the same number, whatever their scale. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Charge that && reason.equals(that.reason) && nuc.compareTo(that.nuc) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reason, nuc.stripTrailingZeros());
    }

    /** The reason and the amount as written: {@code 5M 22.08}. */
    @Override
    public String toString() {
        return reason + " " + nuc;
    }
}
