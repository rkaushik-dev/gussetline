package io.github.rkaushikdev.gussetline.farebasis;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a fare basis after the slash. It discounts the fare, as {@code CH25} takes a quarter
 * off for a child, or it only names a deal, as a bare {@code IT} does.
 *
 * @param raw the designator as filed, in upper case
 * @param kind the two letters that say what it is
 * @param percent the share of the fare it takes off, when it takes any
 */
public record TicketDesignator(String raw, String kind, Optional<BigDecimal> percent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Two letters, then up to three digits in any script, which the decimal reader takes as well. */
    private static final Pattern SHAPE = Pattern.compile("([A-Z]{2})(\\d{1,3})?", Pattern.UNICODE_CHARACTER_CLASS);

    public TicketDesignator {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(percent, "percent");
    }

    /** A designator that names a deal and discounts nothing. */
    public TicketDesignator(String raw, String kind) {
        this(raw, kind, Optional.empty());
    }

    /**
     * Read {@code CH25}, {@code ID90} or a bare {@code IT}, ignoring case and surrounding white space.
     *
     * @throws ValidationError when the text is not two letters and an optional percentage, or the
     *     percentage is over a hundred
     */
    public static TicketDesignator parse(String text) {
        String raw = Text.code(text);
        Matcher match = SHAPE.matcher(raw);
        if (!match.matches()) {
            throw new ValidationError("not a ticket designator", Details.of("value", text));
        }
        String digits = match.group(2);
        if (digits == null) {
            return new TicketDesignator(raw, match.group(1));
        }
        BigDecimal percent = Numbers.parseDecimal(digits);
        if (percent.compareTo(HUNDRED) > 0) {
            throw new ValidationError("a discount over a hundred percent", Details.of("value", text));
        }
        return new TicketDesignator(raw, match.group(1), Optional.of(percent));
    }

    /** Whether it takes anything off the fare. */
    public boolean isDiscount() {
        return percent.isPresent();
    }

    /** Take the discount off. A designator with no percentage gives the amount back unchanged. */
    public BigDecimal applyTo(BigDecimal amount) {
        if (percent.isEmpty()) {
            return amount;
        }
        BigDecimal kept = HUNDRED.subtract(percent.get(), Numbers.CONTEXT);
        return Numbers.divide(amount.multiply(kept, Numbers.CONTEXT), HUNDRED);
    }

    /** The designator as filed. */
    @Override
    public String toString() {
        return raw;
    }
}
