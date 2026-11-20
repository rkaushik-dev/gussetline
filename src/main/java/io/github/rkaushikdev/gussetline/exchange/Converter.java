package io.github.rkaushikdev.gussetline.exchange;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Currency;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns money into neutral units and back, with the right table each way.
 *
 * <p>A fare goes through the rate of exchange, which is published quarterly and is deliberately
 * stale. A tax goes through the bankers selling rate, which moves daily. Every conversion asks for
 * the date of ticketing, never today, so a reconstruction of a ticket sold in March uses the rate
 * that was in force in March.
 */
public final class Converter {

    private final RateTable roe;
    private final RateTable bankers;

    /** A converter whose rate of exchange also stands in for the bankers rate. */
    public Converter(RateTable roe) {
        this(roe, roe);
    }

    /** A converter with a rate of exchange for fares and a bankers rate for taxes. */
    public Converter(RateTable roe, RateTable bankers) {
        this.roe = Objects.requireNonNull(roe, "roe");
        this.bankers = Objects.requireNonNull(bankers, "bankers");
    }

    /** The rate of exchange table, which fares go through. */
    public RateTable roe() {
        return roe;
    }

    /** The bankers selling rate table, which taxes go through. */
    public RateTable bankers() {
        return bankers;
    }

    /**
     * A fare amount in neutral units, truncated to two decimals. An amount already in neutral units
     * is only truncated.
     *
     * @throws ValidationError when the table holds no rate for the currency on the date
     */
    public BigDecimal toNuc(Money amount, LocalDate on) {
        if (amount.currency().equals(Currency.NUC.code())) {
            return Money.truncateNuc(amount.amount());
        }
        BigDecimal rate = roe.rateFor(amount.currency(), on);
        return Money.truncateNuc(Numbers.divide(amount.amount(), rate));
    }

    /**
     * Neutral units in a selling currency, rounded up to its fare unit. Asked for neutral units, it
     * only truncates them to two decimals.
     *
     * @throws ValidationError when the code is no currency we sell in, or the table holds no rate
     *     for it on the date
     */
    public Money fromNuc(BigDecimal nuc, String code, LocalDate on) {
        Currency target = Currency.of(code);
        if (target.code().equals(Currency.NUC.code())) {
            return new Money(Money.truncateNuc(nuc), Currency.NUC.code());
        }
        BigDecimal rate = roe.rateFor(target.code(), on);
        return Money.roundFare(new Money(nuc.multiply(rate, Numbers.CONTEXT), target.code()));
    }

    /**
     * A fare in another currency, through neutral units, the long way on purpose. The trip
     * truncates once and rounds up once, so a fare converted into its own currency can move.
     */
    public Money convertFare(Money amount, String into, LocalDate on) {
        return fromNuc(toNuc(amount, on), into, on);
    }

    /**
     * A tax in another currency, on the bankers rate, rounded to the target's minor unit with halves
     * away from zero. The neutral amount in between keeps six decimals and drops the rest. A tax
     * already in the target currency comes back as the same object.
     *
     * @throws ValidationError when the bankers table holds no rate for either currency on the date
     */
    public Money convertTax(Money amount, String into, LocalDate on) {
        String key = into.toUpperCase(Locale.ROOT);
        if (amount.currency().equals(key)) {
            return amount;
        }
        BigDecimal source = bankers.rateFor(amount.currency(), on);
        BigDecimal targetRate = bankers.rateFor(key, on);
        BigDecimal neutral = Numbers.divide(amount.amount(), source).setScale(6, RoundingMode.DOWN);
        Currency target = Currency.of(into);
        BigDecimal value =
                neutral.multiply(targetRate, Numbers.CONTEXT).setScale(target.exponent(), RoundingMode.HALF_UP);
        return new Money(value, target.code());
    }
}
