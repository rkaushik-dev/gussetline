package io.github.rkaushikdev.gussetline.mileage;

import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the mileage check decided, and every figure it used.
 *
 * @param flown the ticketed point mileages of the sectors, added up
 * @param maximum the maximum permitted mileage of the fare component
 * @param deduction the extra mileage allowance taken off the flown figure
 * @param surchargePercent the surcharge step the component falls into, in percent
 * @param permitted whether the component is on the ladder at all
 * @param fallbackUsed whether a great circle stood in for a published mileage anywhere in it
 */
public record MileageResult(
        int flown, int maximum, int deduction, int surchargePercent, boolean permitted, boolean fallbackUsed) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A result that used published mileages throughout. */
    public MileageResult(int flown, int maximum, int deduction, int surchargePercent, boolean permitted) {
        this(flown, maximum, deduction, surchargePercent, permitted, false);
    }

    /** Flown mileage after the allowances come off, never below nought. */
    public int chargeable() {
        return Math.max(0, flown - deduction);
    }

    /**
     * Chargeable as a percentage of the maximum, rounded up. Nought when there is no maximum to
     * divide by.
     *
     * <p>Rounded up because the bands are inclusive at the top. A journey at a hundred and five
     * point one percent is in the ten percent band, and rounding to nearest would put it in the five
     * and undercharge.
     */
    public int percentage() {
        if (maximum <= 0) {
            return 0;
        }
        BigDecimal scaled = BigDecimal.valueOf(chargeable()).multiply(HUNDRED, Numbers.CONTEXT);
        BigDecimal raw = Numbers.divide(scaled, BigDecimal.valueOf(maximum));
        return raw.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** What the fare is multiplied by: {@code 1.1} for the ten percent step. */
    public BigDecimal multiplier() {
        return Numbers.divide(BigDecimal.valueOf(100L + surchargePercent), HUNDRED);
    }

    /** How the surcharge is written on a ticket: {@code 10M}, or {@code M} when nothing is owed. */
    public String band() {
        return surchargePercent != 0 ? surchargePercent + "M" : "M";
    }

    /**
     * The amount to add, not the total: the surcharge percentage of the neutral amount, to two
     * places with ties to the even neighbour. Zero when nothing is owed.
     */
    public BigDecimal surchargeOn(BigDecimal nuc) {
        if (surchargePercent == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal scaled = nuc.multiply(BigDecimal.valueOf(surchargePercent), Numbers.CONTEXT);
        return Numbers.divide(scaled, HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * The percentage and the band, or that it is off the ladder: {@code 84 percent of maximum, M}.
     */
    @Override
    public String toString() {
        if (!permitted) {
            return percentage() + " percent of maximum, over the ladder";
        }
        return percentage() + " percent of maximum, " + band();
    }
}
