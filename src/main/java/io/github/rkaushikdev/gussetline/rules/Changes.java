package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;

/**
 * Category 31, voluntary changes, which is the arithmetic the desk lives on.
 *
 * <p>A cheaper fare has appeared for a ticket the customer already holds. Three filings decide
 * whether that is worth anything. The repricing basis ({@link RepricingBasis}) says which day's
 * tariff the new ticket is priced from. The residual handling ({@link Residual}) says what happens
 * when the new fare is lower. And the fee itself, from category 16, is subtracted whatever happens.
 * A {@link Reissue} reads all three together.
 */
public final class Changes {

    /**
     * What a travel credit is worth against cash. Credits expire, they are tied to one passenger, and
     * about a fifth of the ones this desk has issued were never used, so a recommendation that counts
     * them at face value overstates itself.
     */
    public static final BigDecimal CREDIT_DISCOUNT = new BigDecimal("0.80");

    private Changes() {}

    /**
     * The reissue of a ticket bought for {@code paid} onto {@code newFare}, for a change fee, under a
     * category 31 filing.
     *
     * @throws ValidationError when the amounts are in more than one currency, or one is negative
     */
    public static Reissue reissueFor(Money paid, Money newFare, Money penalty, VoluntaryChange rule) {
        return new Reissue(paid, newFare, penalty, rule);
    }

    /**
     * Whether today's tariff is even usable for this ticket.
     *
     * <p>A historical filing means the reissue prices off the day the ticket was first issued, so a fare that
     * dropped since then does not exist for this passenger and the watch should be retired rather
     * than kept firing. A fare that cannot be changed cannot be repriced from any day.
     */
    public static boolean pricedFromToday(VoluntaryChange rule) {
        return rule.permitted() && rule.basis() == RepricingBasis.CURRENT;
    }
}
