package io.github.rkaushikdev.gussetline.offers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.taxes.Taxes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Base fare, taxes, surcharges, and the total the supplier claimed.
 *
 * <p>A supplier sends a total and some components, and the components do not always add up to the
 * total. Sometimes that is a real finding and sometimes it is a field that failed to parse, so the
 * breakdown keeps both what was sent ({@link #quotedTotal()}) and what the parts add to ({@link
 * #computedTotal()}), and never quietly replaces one with the other. A breakdown whose parts disagree
 * with its total is still a breakdown, with a {@link #discrepancy()} on it.
 *
 * @param base the base fare
 * @param taxes the taxes and carrier surcharges, in the order sent
 * @param quotedTotal the total the supplier claimed, if it claimed one
 */
public record PriceBreakdown(Money base, List<AppliedTax> taxes, Optional<Money> quotedTotal) {

    /**
     * Checks that everything is in the base fare's currency.
     *
     * @throws ValidationError naming the {@code base} and the {@code tax} currency when a tax is in
     *     another one, or when the quoted total is
     */
    public PriceBreakdown {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quotedTotal, "quotedTotal");
        taxes = List.copyOf(taxes);
        for (AppliedTax tax : taxes) {
            if (!tax.amount().currency().equals(base.currency())) {
                throw new ValidationError(
                        "a breakdown is in one currency",
                        Details.of("base", base.currency(), "tax", tax.amount().currency()));
            }
        }
        if (quotedTotal.isPresent() && !quotedTotal.get().currency().equals(base.currency())) {
            throw new ValidationError("the quoted total is in another currency");
        }
    }

    /** A base fare alone, with no taxes and no quoted total. */
    public PriceBreakdown(Money base) {
        this(base, List.of(), Optional.empty());
    }

    /** A base fare and its taxes, with no quoted total. */
    public PriceBreakdown(Money base, List<AppliedTax> taxes) {
        this(base, taxes, Optional.empty());
    }

    /** A base fare, its taxes and the total the supplier claimed. */
    public PriceBreakdown(Money base, List<AppliedTax> taxes, Money quotedTotal) {
        this(base, taxes, Optional.of(quotedTotal));
    }

    /** The base fare's currency, which is everything's. */
    public String currency() {
        return base.currency();
    }

    /** The government taxes, carrier surcharges left out. */
    public Money taxTotal() {
        return Taxes.governmentTotal(taxes, currency());
    }

    /** The carrier surcharges alone. */
    public Money surchargeTotal() {
        return Taxes.carrierTotal(taxes, currency());
    }

    /** What the parts add to: base, taxes and surcharges. */
    public Money computedTotal() {
        return Money.total(base, taxTotal(), surchargeTotal());
    }

    /** What the supplier said, falling back to what the parts add to. */
    public Money statedTotal() {
        return quotedTotal.orElseGet(this::computedTotal);
    }

    /** Quoted minus computed. Zero when the parts add up, which is not always. */
    public Money discrepancy() {
        return statedTotal().minus(computedTotal());
    }

    /** Whether the parts add up to what the supplier said. */
    public boolean addsUp() {
        return discrepancy().isZero();
    }

    /** The codes of the taxes, each once, sorted. */
    public List<String> taxCodes() {
        return taxes.stream().map(AppliedTax::code).distinct().sorted().toList();
    }

    /** The three parts: {@code 420.00 EUR + 70.83 EUR tax + 0 EUR yq}. */
    @Override
    public String toString() {
        return base + " + " + taxTotal() + " tax + " + surchargeTotal() + " yq";
    }
}
