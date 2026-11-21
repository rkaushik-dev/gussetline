package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * What a voluntary reissue actually costs or returns.
 *
 * <p>Whether a cheaper fare is worth anything is not the price difference. It is the price
 * difference minus the change fee, and then only if the difference is collectable in the direction
 * that helps, which it usually is not. So a fare that has dropped three hundred euro can be worth
 * three hundred, or worth nothing, or worth a credit the customer will probably not use, and the
 * only way to tell is to read the repricing basis, the residual handling and the fee together.
 *
 * @param paid what the ticket held cost
 * @param newFare what the new fare costs
 * @param penalty the change fee
 * @param rule the category 31 filing the reissue is made under
 */
public record Reissue(Money paid, Money newFare, Money penalty, VoluntaryChange rule) {

    /**
     * Checks the amounts.
     *
     * @throws ValidationError when an amount is in another currency than the one paid, or is negative
     *     (naming the {@code amount})
     */
    public Reissue {
        Objects.requireNonNull(paid, "paid");
        Objects.requireNonNull(newFare, "newFare");
        Objects.requireNonNull(penalty, "penalty");
        Objects.requireNonNull(rule, "rule");
        for (Money amount : List.of(paid, newFare, penalty)) {
            if (!amount.currency().equals(paid.currency())) {
                throw new ValidationError("a reissue happens in one currency");
            }
            if (amount.isNegative()) {
                throw new ValidationError("a reissue amount is not negative", Details.of("amount", amount.toString()));
            }
        }
    }

    /** New fare minus old. Positive means the new one costs more. */
    public Money difference() {
        return newFare.minus(paid);
    }

    /**
     * What the passenger pays now. Never less than nothing.
     *
     * <p>A reissue to a dearer fare collects the difference plus the fee. A reissue to a cheaper one
     * still collects the fee, and whether the difference comes back is the residual question and not
     * this one.
     */
    public Money addCollect() {
        if (difference().isNegative()) {
            return penalty;
        }
        return difference().plus(penalty);
    }

    /** Cash back, which only a refundable residual produces: the drop less the fee, never below zero. */
    public Money refund() {
        return leftOver(Residual.REFUNDABLE);
    }

    /** A voucher, which only a retained residual produces: the drop less the fee, never below zero. */
    public Money credit() {
        return leftOver(Residual.RETAINED);
    }

    /**
     * What the reissue is worth to the customer, in comparable money.
     *
     * <p>Cash counts at face value and a credit at {@link Changes#CREDIT_DISCOUNT} of its face, to the
     * cent, half to even, because a credit that expires unused was never worth what it said. A
     * reissue that costs money and returns nothing is a negative benefit, the amount collected, and
     * the desk should not raise it. A fare that does not permit the change is worth nothing.
     */
    public Money benefit() {
        if (!rule.permitted()) {
            return Money.zero(paid.currency());
        }
        Money cash = refund();
        Money voucher = credit();
        if (!voucher.isZero()) {
            cash = cash.plus(new Money(
                    voucher.amount()
                            .multiply(Changes.CREDIT_DISCOUNT, Numbers.CONTEXT)
                            .setScale(2, RoundingMode.HALF_EVEN),
                    voucher.currency()));
        }
        if (addCollect().amount().signum() > 0 && cash.isZero()) {
            return addCollect().negate();
        }
        return cash;
    }

    /** Whether the change is permitted and leaves the customer better off. */
    public boolean worthDoing() {
        return rule.permitted() && benefit().amount().signum() > 0;
    }

    /** {@code worth 350 EUR}, {@code costs 170 EUR}, or {@code not permitted}. */
    @Override
    public String toString() {
        if (!rule.permitted()) {
            return "not permitted";
        }
        Money worth = benefit();
        if (worth.isNegative()) {
            return "costs " + worth.abs();
        }
        return "worth " + worth;
    }

    /** The drop less the fee, when the residual is handled the way asked about; otherwise nothing. */
    private Money leftOver(Residual handling) {
        Money nothing = Money.zero(paid.currency());
        if (!difference().isNegative() || rule.residual() != handling) {
            return nothing;
        }
        Money left = difference().abs().minus(penalty);
        return left.isNegative() ? nothing : left;
    }
}
