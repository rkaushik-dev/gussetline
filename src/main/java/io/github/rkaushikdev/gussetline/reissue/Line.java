package io.github.rkaushikdev.gussetline.reissue;

import io.github.rkaushikdev.gussetline.money.Money;
import java.util.Objects;

/**
 * One tax code at one point, and what the reissue did with it.
 *
 * @param code the tax code, upper case
 * @param point the point that made the tax owed, upper case
 * @param was what the old ticket carried for it; nothing when it carried none
 * @param now what the new ticket carries for it; nothing when it carries none
 * @param treatment what the reissue did with the line
 * @param carrierImposed whether this is a carrier surcharge filed in the tax box rather than a tax
 */
public record Line(String code, String point, Money was, Money now, Treatment treatment, boolean carrierImposed) {

    public Line {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(was, "was");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(treatment, "treatment");
    }

    /** A government tax line. */
    public Line(String code, String point, Money was, Money now, Treatment treatment) {
        this(code, point, was, now, treatment, false);
    }

    /** New less old. Positive means the line went up. */
    public Money difference() {
        return now.minus(was);
    }

    /** What the passenger pays for this line: the rise when it is collected, otherwise nothing. */
    public Money collected() {
        if (treatment != Treatment.COLLECTED) {
            return Money.zero(was.currency());
        }
        return difference();
    }

    /** What comes back for this line: the fall when it is refunded, otherwise nothing. */
    public Money refunded() {
        if (treatment != Treatment.REFUNDED) {
            return Money.zero(was.currency());
        }
        return was.minus(now);
    }

    /** {@code DE at FRA collected 32.50 EUR to 40.00 EUR}. */
    @Override
    public String toString() {
        return code + " at " + point + " " + treatment.value() + " " + was + " to " + now;
    }
}
