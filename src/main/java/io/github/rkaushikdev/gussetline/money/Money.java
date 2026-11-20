package io.github.rkaushikdev.gussetline.money;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount in one currency. Immutable, exact, and never binary floating point.
 *
 * <p>A published fare is not a price. It is an amount in a currency, converted through the rate of
 * exchange into a neutral unit, added up, converted back and then rounded, and the rounding is not
 * one rule. Fares round up to a unit that is per currency and is not the minor unit ({@link
 * #roundFare}). Taxes round to the nearest minor unit ({@link #roundTax}). Refunds round down so we
 * never owe the passenger a fraction we cannot pay ({@link #roundRefund}). Getting any of the three
 * wrong shows up as a one unit discrepancy on every audit in that currency, which is exactly the
 * size of finding that gets dismissed as noise for a year.
 *
 * <p>Two amounts are equal when they are the same number in the same currency, whatever their
 * scale: {@code 12.5 EUR} equals {@code 12.50 EUR}. The amount keeps the scale it was given, so it
 * prints the way it was written. Arithmetic runs at {@link Numbers#CONTEXT}.
 *
 * @param amount the exact amount
 * @param currency the ISO code, upper case
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    /**
     * Normalises the code to upper case without surrounding white space.
     *
     * @throws ValidationError when the code is not a currency the desk sells in, or the neutral unit
     */
    public Money {
        Objects.requireNonNull(amount, "amount");
        currency = Text.strip(Objects.requireNonNull(currency, "currency")).toUpperCase(Locale.ROOT);
        Currency.of(currency);
    }

    /**
     * An amount written as decimal text, such as {@code "420.00"}.
     *
     * @throws NumberFormatException when the text is not a decimal
     */
    public static Money of(String amount, String code) {
        return new Money(Numbers.parseDecimal(amount), code);
    }

    /** A whole amount. */
    public static Money of(long amount, String code) {
        return new Money(BigDecimal.valueOf(amount), code);
    }

    /** An exact amount. */
    public static Money of(BigDecimal amount, String code) {
        return new Money(amount, code);
    }

    /** Nothing, in a currency. */
    public static Money zero(String code) {
        return new Money(BigDecimal.ZERO, code);
    }

    /** The sum. Refuses to mix currencies. */
    public Money plus(Money other) {
        same(other);
        return new Money(amount.add(other.amount, Numbers.CONTEXT), currency);
    }

    /** The difference. Refuses to mix currencies. */
    public Money minus(Money other) {
        same(other);
        return new Money(amount.subtract(other.amount, Numbers.CONTEXT), currency);
    }

    /** The same amount with the sign turned over. */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** The amount multiplied by a factor. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor, Numbers.CONTEXT), currency);
    }

    /** The amount multiplied by a whole factor. */
    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    /** Orders two amounts in the same currency. Refuses to compare across currencies. */
    @Override
    public int compareTo(Money other) {
        same(other);
        return amount.compareTo(other.amount);
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isAtMost(Money other) {
        return compareTo(other) <= 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isAtLeast(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** The amount without its sign. */
    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    /** The amount as text and the code, which is how the amount is stored and sent. */
    public List<String> asTuple() {
        return List.of(amount.toString(), currency);
    }

    /** Equal when the amounts are the same number, whatever their scale, in the same currency. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Money that && currency.equals(that.currency) && amount.compareTo(that.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    /** The amount as written, then the code: {@code 12.50 EUR}. */
    @Override
    public String toString() {
        return amount + " " + currency;
    }

    /** Round a constructed fare up to the currency's published fare unit. */
    public static Money roundFare(Money value) {
        BigDecimal unit = Currency.of(value.currency).fareUnit();
        BigDecimal steps = Numbers.divide(value.amount, unit).setScale(0, RoundingMode.CEILING);
        return new Money(steps.multiply(unit, Numbers.CONTEXT), value.currency);
    }

    /** Round a tax or fee to the nearest minor unit, halves away from zero. */
    public static Money roundTax(Money value) {
        Currency unit = Currency.of(value.currency);
        return new Money(value.amount.setScale(unit.exponent(), RoundingMode.HALF_UP), value.currency);
    }

    /** Round a refundable amount down, so the desk never promises a fraction. */
    public static Money roundRefund(Money value) {
        Currency unit = Currency.of(value.currency);
        return new Money(value.amount.setScale(unit.exponent(), RoundingMode.DOWN), value.currency);
    }

    /** Neutral units carry two decimals and the third is dropped, not rounded. */
    public static BigDecimal truncateNuc(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN);
    }

    /**
     * Add money that is already known to share a currency.
     *
     * @throws ValidationError when there is nothing to add, or the currencies differ
     */
    public static Money total(Money... amounts) {
        return total(List.of(amounts));
    }

    /**
     * Add money that is already known to share a currency.
     *
     * @throws ValidationError when there is nothing to add, or the currencies differ
     */
    public static Money total(List<Money> amounts) {
        if (amounts.isEmpty()) {
            throw new ValidationError("nothing to total");
        }
        Money running = amounts.get(0);
        for (Money item : amounts.subList(1, amounts.size())) {
            running = running.plus(item);
        }
        return running;
    }

    /**
     * Read {@code 123.45EUR} or {@code 123.45 EUR}, which is how suppliers write it.
     *
     * @throws ValidationError when the text is not an amount and a currency
     */
    public static Money parse(String text) {
        String raw = Text.strip(text).replace(" ", "");
        if (raw.length() < 4) {
            throw new ValidationError("not an amount", Details.of("text", text));
        }
        String code = raw.substring(raw.length() - 3);
        String digits = raw.substring(0, raw.length() - 3);
        BigDecimal value;
        try {
            value = Numbers.parseDecimal(digits);
        } catch (NumberFormatException refused) {
            throw new ValidationError("not an amount", Details.of("text", text));
        }
        return new Money(value, code);
    }

    /**
     * Take whatever a payload used for a number and make it exact.
     *
     * <p>Whole numbers and decimal text are taken; a flag is refused before it becomes a one, and a
     * binary floating point number is refused because a fare is not binary.
     *
     * @throws ValidationError for anything that is not an exact amount
     */
    public static BigDecimal coerceAmount(Object value) {
        if (value instanceof BigDecimal exact) {
            return exact;
        }
        if (value instanceof Boolean) {
            throw new ValidationError("boolean is not an amount", Details.of("value", value));
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger whole) {
            return new BigDecimal(whole);
        }
        if (value instanceof String text) {
            try {
                return Numbers.parseDecimal(text);
            } catch (NumberFormatException refused) {
                throw new ValidationError("not an amount", Details.of("value", value));
            }
        }
        if (value instanceof Double || value instanceof Float) {
            throw new ValidationError("float amounts are refused, send a string", Details.of("value", value));
        }
        throw new ValidationError("not an amount", Details.of("value", Text.repr(value)));
    }

    private void same(Money other) {
        if (!currency.equals(other.currency)) {
            throw new ValidationError("cannot mix currencies", Details.of("left", currency, "right", other.currency));
        }
    }
}
