package io.github.rkaushikdev.gussetline.audit;

import io.github.rkaushikdev.gussetline.money.Money;
import java.util.Objects;

/**
 * One tax code a supplier charged differently from the filing, in the offer's currency.
 *
 * @param code the tax code
 * @param ours what the filed taxes say is owed under the code, zero when nothing is
 * @param theirs what the supplier charged under the code, zero when it charged nothing
 */
public record TaxCheck(String code, Money ours, Money theirs) {

    public TaxCheck {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ours, "ours");
        Objects.requireNonNull(theirs, "theirs");
    }

    /** Theirs minus ours. Positive when the supplier charged more. */
    public Money difference() {
        return theirs.minus(ours);
    }

    /** Whether the supplier left out a tax the filing says is owed. */
    public boolean missing() {
        return theirs.isZero() && !ours.isZero();
    }

    /** Whether the supplier charged a tax nothing filed says is owed. */
    public boolean unfiled() {
        return ours.isZero() && !theirs.isZero();
    }

    /** {@code DE theirs 60.83 EUR, ours 70.83 EUR}, or that one side has none. */
    @Override
    public String toString() {
        if (missing()) {
            return code + " not charged, ours " + ours;
        }
        if (unfiled()) {
            return code + " charged at " + theirs + " and not filed";
        }
        return code + " theirs " + theirs + ", ours " + ours;
    }
}
