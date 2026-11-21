package io.github.rkaushikdev.gussetline.selection;

import io.github.rkaushikdev.gussetline.mileage.MileageResult;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One fare that survived every check on a component, and what it costs after the mileage ladder.
 *
 * <p>Two candidates are equal when they hold the same fare at the same neutral amount, whatever its
 * scale, with the same mileage reading.
 *
 * @param fare the published fare
 * @param baseNuc its filed amount in neutral units, truncated, with no band on it
 * @param mileage what the mileage check said about the component
 */
public record Candidate(Fare fare, BigDecimal baseNuc, MileageResult mileage) {

    public Candidate {
        Objects.requireNonNull(fare, "fare");
        Objects.requireNonNull(baseNuc, "baseNuc");
        Objects.requireNonNull(mileage, "mileage");
    }

    /** The mileage surcharge on the base, to two places. Zero when nothing is owed. */
    public BigDecimal surcharge() {
        return mileage.surchargeOn(baseNuc);
    }

    /** The base and the mileage surcharge, which is the number candidates are compared on. */
    public BigDecimal totalNuc() {
        return baseNuc.add(surcharge(), Numbers.CONTEXT);
    }

    /** How the mileage surcharge is written on a ticket: {@code 5M}, or {@code M}. */
    public String band() {
        return mileage.band();
    }

    /** Equal when the fare and the mileage reading match and the amounts are the same number. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Candidate that
                && fare.equals(that.fare)
                && baseNuc.compareTo(that.baseNuc) == 0
                && mileage.equals(that.mileage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fare, baseNuc.stripTrailingZeros(), mileage);
    }

    /** The fare basis, the total and the band: {@code KLWAP7RT 420.00 NUC M}. */
    @Override
    public String toString() {
        return fare.fareBasis() + " " + totalNuc() + " NUC " + band();
    }
}
