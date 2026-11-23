package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One filed tax, and the conditions under which it is owed.
 *
 * <p>Two rules are equal when every field is, the amount compared as a number whatever its scale.
 *
 * @param code the two or three character tax code, upper case
 * @param country the country that files it, upper case; carrier surcharges file under a placeholder
 * @param description what the tax is, for a person
 * @param basis whether the amount is money or a percentage of the base fare
 * @param amount the filed amount, never negative, and at most a hundred for a percentage
 * @param currency the currency of a flat amount
 * @param applies how many times it is charged
 * @param exemptTransit whether a connecting passenger is spared it
 * @param transitHours the longest break a passenger is still connecting through for this tax, in
 *     hours; read in place of the journey's stopover line, so a country can spare a longer wait than
 *     the fare does or a shorter one
 */
public record TaxRule(
        String code,
        String country,
        String description,
        Basis basis,
        BigDecimal amount,
        String currency,
        Applies applies,
        boolean exemptTransit,
        int transitHours) {

    /** The break a transit exemption allows unless the filing says otherwise, in hours. */
    public static final int DEFAULT_TRANSIT_HOURS = 24;

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,3}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Normalises the code and the country to upper case without surrounding white space.
     *
     * @throws ValidationError naming the {@code code} when it is not two or three letters or digits,
     *     when the amount is negative, when a percentage is over a hundred, or when the transit break
     *     is negative
     */
    public TaxRule {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(applies, "applies");
        code = Text.code(code);
        country = Text.code(country);
        if (!CODE.matcher(code).matches()) {
            throw new ValidationError("not a tax code", Details.of("code", code));
        }
        if (amount.signum() < 0) {
            throw new ValidationError("a tax is not negative", Details.of("code", code));
        }
        if (basis == Basis.PERCENT_OF_BASE && amount.compareTo(HUNDRED) > 0) {
            throw new ValidationError("a percentage tax over a hundred", Details.of("code", code));
        }
        if (transitHours < 0) {
            throw new ValidationError("a transit break is not negative", Details.of("code", code));
        }
    }

    /** A rule with no transit exemption. */
    public TaxRule(
            String code,
            String country,
            String description,
            Basis basis,
            BigDecimal amount,
            String currency,
            Applies applies) {
        this(code, country, description, basis, amount, currency, applies, false);
    }

    /** A rule, exempting connecting passengers or not, over the default transit break. */
    public TaxRule(
            String code,
            String country,
            String description,
            Basis basis,
            BigDecimal amount,
            String currency,
            Applies applies,
            boolean exemptTransit) {
        this(code, country, description, basis, amount, currency, applies, exemptTransit, DEFAULT_TRANSIT_HOURS);
    }

    /**
     * Whether this is money the carrier keeps rather than a tax: a {@linkplain Taxes#CARRIER_IMPOSED
     * YQ or YR} code filed in the tax box.
     */
    public boolean isCarrierImposed() {
        return Taxes.CARRIER_IMPOSED.contains(code);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TaxRule that
                && code.equals(that.code)
                && country.equals(that.country)
                && description.equals(that.description)
                && basis == that.basis
                && amount.compareTo(that.amount) == 0
                && currency.equals(that.currency)
                && applies == that.applies
                && exemptTransit == that.exemptTransit
                && transitHours == that.transitHours;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                code,
                country,
                description,
                basis,
                amount.stripTrailingZeros(),
                currency,
                applies,
                exemptTransit,
                transitHours);
    }
}
