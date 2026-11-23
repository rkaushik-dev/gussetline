package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.money.Money;
import java.util.Objects;

/**
 * A tax that was actually owed, and the point that made it owed.
 *
 * @param code the tax code
 * @param amount what is owed, already rounded
 * @param point the airport or city that made it owed; empty when a supplier did not say
 * @param description what the tax is, for a person; empty when nobody said
 * @param carrierImposed whether this is carrier money filed in the tax box rather than a tax
 */
public record AppliedTax(String code, Money amount, String point, String description, boolean carrierImposed) {

    public AppliedTax {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(description, "description");
    }

    /** A government tax with no description. */
    public AppliedTax(String code, Money amount, String point) {
        this(code, amount, point, "", false);
    }

    /** A tax or a carrier surcharge with no description, which is how a supplier sends one. */
    public AppliedTax(String code, Money amount, String point, boolean carrierImposed) {
        this(code, amount, point, "", carrierImposed);
    }

    /**
     * A tax as a supplier lists it: a code, an amount and perhaps a point. No supplier says whether
     * a code is carrier money, so that is read off the code ({@link Taxes#CARRIER_IMPOSED}), the same
     * way for every feed.
     */
    public static AppliedTax asSent(String code, Money amount, String point) {
        return new AppliedTax(code, amount, point, Taxes.CARRIER_IMPOSED.contains(code));
    }

    /** The same tax at the same point, owed as another amount, such as the same duty in euro. */
    public AppliedTax withAmount(Money restated) {
        return new AppliedTax(code, restated, point, description, carrierImposed);
    }

    /** The code and the amount: {@code DE 70.83 EUR}. */
    @Override
    public String toString() {
        return code + " " + amount;
    }
}
