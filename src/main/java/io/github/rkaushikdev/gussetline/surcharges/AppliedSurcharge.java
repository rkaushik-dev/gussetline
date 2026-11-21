package io.github.rkaushikdev.gussetline.surcharges;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A surcharge that was charged on a component, and how many times.
 *
 * <p>Two charges are equal when they come from the same filing the same number of times and add the
 * same amount, whatever its scale.
 *
 * @param surcharge the filing that was charged
 * @param times how many times it was charged: once for a component filing, once per matching sector
 *     for a sector filing
 * @param nuc what it added altogether, in neutral units
 */
public record AppliedSurcharge(QSurcharge surcharge, int times, BigDecimal nuc) {

    public AppliedSurcharge {
        Objects.requireNonNull(surcharge, "surcharge");
        Objects.requireNonNull(nuc, "nuc");
    }

    /** The note the filing is charged under. */
    public String reason() {
        return surcharge.reason();
    }

    /** Equal when the filing and the count match and the amounts are the same number. */
    @Override
    public boolean equals(Object other) {
        return other instanceof AppliedSurcharge that
                && surcharge.equals(that.surcharge)
                && times == that.times
                && nuc.compareTo(that.nuc) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(surcharge, times, nuc.stripTrailingZeros());
    }

    /**
     * The reason and the amount, and the count when it is more than one: {@code Q 30.00 NUC}, {@code
     * Q 60.00 NUC over 2 sectors}.
     */
    @Override
    public String toString() {
        if (times == 1) {
            return reason() + " " + nuc + " NUC";
        }
        return reason() + " " + nuc + " NUC over " + times + " sectors";
    }
}
