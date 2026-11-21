package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The transaction a category 15 provision is checked against: where the ticket is sold, by whom, how
 * it is paid for, and when.
 *
 * <p>The country of sale is on the ticket, which is why the carrier finds out when a fare filed for
 * sale in one country is issued in another.
 *
 * @param country the country of sale, upper case
 * @param agency the selling agency, upper case, or empty when not given
 * @param payment how the ticket is paid for
 * @param on the day of the sale, when it is not the ticketing date
 */
public record Sale(String country, String agency, FormOfPayment payment, Optional<LocalDate> on) {

    /**
     * Normalises the country and the agency.
     *
     * @throws ValidationError when the country is not one the desk knows, naming the {@code country}
     */
    public Sale {
        country = Text.code(Objects.requireNonNull(country, "country"));
        if (!Geography.COUNTRIES.containsKey(country)) {
            throw new ValidationError("unknown country of sale", Details.of("country", country));
        }
        agency = Text.code(Objects.requireNonNull(agency, "agency"));
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(on, "on");
    }

    /** A sale in a country, by no named agency, paid for any way, on the ticketing date. */
    public Sale(String country) {
        this(country, "", FormOfPayment.ANY, Optional.empty());
    }

    /** A builder for a sale in a country, starting from {@link #Sale(String)}. */
    public static Builder builder(String country) {
        return new Builder(country);
    }

    /** Collects a sale. Every setter returns the builder. */
    public static final class Builder {

        private final String country;
        private String agency = "";
        private FormOfPayment payment = FormOfPayment.ANY;
        private Optional<LocalDate> on = Optional.empty();

        private Builder(String country) {
            this.country = country;
        }

        public Builder agency(String value) {
            agency = value;
            return this;
        }

        public Builder payment(FormOfPayment value) {
            payment = value;
            return this;
        }

        public Builder on(LocalDate value) {
            on = Optional.of(value);
            return this;
        }

        /**
         * The sale.
         *
         * @throws ValidationError when the country is unknown
         */
        public Sale build() {
            return new Sale(country, agency, payment, on);
        }
    }
}
