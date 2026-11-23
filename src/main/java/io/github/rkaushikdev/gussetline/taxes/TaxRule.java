package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * @param maxPerDirection the most times it is charged going out, and again coming home, when the
 *     country caps it; a one way is all one direction
 * @param exemptPassengers the passenger types spared it whatever the journey does, such as an infant
 *     on a lap that takes no seat
 * @param journeys whether it is owed on every journey, or only a domestic or an international one
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
        int transitHours,
        Optional<Integer> maxPerDirection,
        Set<PassengerType> exemptPassengers,
        Journeys journeys) {

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
        Objects.requireNonNull(maxPerDirection, "maxPerDirection");
        exemptPassengers = Set.copyOf(exemptPassengers);
        Objects.requireNonNull(journeys, "journeys");
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
        if (maxPerDirection.isPresent() && maxPerDirection.get() < 1) {
            throw new ValidationError(
                    "a cap allows at least one charge", Details.of("code", code, "cap", maxPerDirection.get()));
        }
    }

    /**
     * A rule filed by a country under a code, flat, charged once per ticket, sparing nobody, until
     * the builder says otherwise. The amount has to be set.
     */
    public static Builder builder(String code, String country) {
        return new Builder(code, country);
    }

    /** Whether a passenger of this type is spared the tax whatever the journey does. */
    public boolean spares(PassengerType type) {
        return exemptPassengers.contains(type);
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
                && transitHours == that.transitHours
                && maxPerDirection.equals(that.maxPerDirection)
                && exemptPassengers.equals(that.exemptPassengers)
                && journeys == that.journeys;
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
                transitHours,
                maxPerDirection,
                exemptPassengers,
                journeys);
    }

    /**
     * Collects a filing's fields. Only the amount has to be set; everything else starts where a plain
     * flat tax would be. Every setter returns the builder.
     */
    public static final class Builder {

        private final String code;
        private final String country;
        private String description = "";
        private Basis basis = Basis.FLAT;
        private BigDecimal amount;
        private String currency = "";
        private Applies applies = Applies.PER_TICKET;
        private boolean exemptTransit;
        private int transitHours = DEFAULT_TRANSIT_HOURS;
        private Optional<Integer> maxPerDirection = Optional.empty();
        private final EnumSet<PassengerType> exemptPassengers = EnumSet.noneOf(PassengerType.class);
        private Journeys journeys = Journeys.ANY;

        private Builder(String code, String country) {
            this.code = code;
            this.country = country;
        }

        public Builder description(String text) {
            this.description = Objects.requireNonNull(text, "description");
            return this;
        }

        /** An amount of money in the filing's own currency, written as decimal text. */
        public Builder flat(String value, String code) {
            return amount(Basis.FLAT, new BigDecimal(value), code);
        }

        /** A percentage of the base fare, written as decimal text, filed under a currency. */
        public Builder percentOfBase(String rate, String code) {
            return amount(Basis.PERCENT_OF_BASE, new BigDecimal(rate), code);
        }

        public Builder amount(Basis kind, BigDecimal value, String code) {
            this.basis = Objects.requireNonNull(kind, "basis");
            this.amount = Objects.requireNonNull(value, "amount");
            this.currency = Objects.requireNonNull(code, "currency");
            return this;
        }

        public Builder applies(Applies scope) {
            this.applies = Objects.requireNonNull(scope, "applies");
            return this;
        }

        /** Spare a connecting passenger, or stop sparing one, over the default break. */
        public Builder exemptTransit(boolean exempt) {
            this.exemptTransit = exempt;
            return this;
        }

        /** Spare a passenger connecting within this many hours. */
        public Builder exemptTransit(int hours) {
            this.exemptTransit = true;
            this.transitHours = hours;
            return this;
        }

        public Builder maxPerDirection(int cap) {
            this.maxPerDirection = Optional.of(cap);
            return this;
        }

        /** Spare these passenger types whatever the journey does. */
        public Builder spares(PassengerType... types) {
            exemptPassengers.addAll(List.of(types));
            return this;
        }

        /** Owe the tax only on the journeys named. */
        public Builder journeys(Journeys kind) {
            this.journeys = Objects.requireNonNull(kind, "journeys");
            return this;
        }

        /**
         * The filing.
         *
         * @throws NullPointerException when the amount was never set
         * @throws ValidationError for anything the filing itself refuses
         */
        public TaxRule build() {
            return new TaxRule(
                    code,
                    country,
                    description,
                    basis,
                    Objects.requireNonNull(amount, "amount"),
                    currency,
                    applies,
                    exemptTransit,
                    transitHours,
                    maxPerDirection,
                    exemptPassengers,
                    journeys);
        }
    }
}
