package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 15: where and how the ticket may be sold.
 *
 * <p>This and category 1 are the reason a consolidator exists and also the reason it gets audited. A
 * private fare filed for sale in Germany only is not a fare a desk in London may issue, however good
 * the price is, and the carrier will find out because the country of sale is on the ticket. A ticket
 * sold in the wrong country cannot be fixed after the fact at all, so the desk wants that one before
 * the sale rather than in an audit three months later. That is why a restricted fare with no sale
 * details fails rather than passing.
 *
 * @param countries the only countries the fare may be sold in, upper case; none filed means any
 * @param excludedCountries the countries the fare may not be sold in, upper case
 * @param agencies the only agencies that may sell the fare, upper case; none filed means any
 * @param payment the form of payment the fare wants, or {@link FormOfPayment#ANY}
 * @param firstSale the first day the fare may be sold, when filed
 * @param lastSale the last day the fare may be sold, when filed
 * @param sale the transaction to check, when known
 */
public record SalesRestriction(
        List<String> countries,
        List<String> excludedCountries,
        List<String> agencies,
        FormOfPayment payment,
        Optional<LocalDate> firstSale,
        Optional<LocalDate> lastSale,
        Optional<Sale> sale)
        implements Provision {

    /**
     * Normalises the country and agency lists to upper case.
     *
     * @throws ValidationError when a country is not one the desk knows (naming the {@code field},
     *     {@code countries} or {@code excluded_countries}, and the {@code country}), or when the filing
     *     names both permitted and excluded countries
     */
    public SalesRestriction {
        countries = known("countries", countries);
        excludedCountries = known("excluded_countries", excludedCountries);
        agencies = agencies.stream().map(Text::code).toList();
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(firstSale, "firstSale");
        Objects.requireNonNull(lastSale, "lastSale");
        Objects.requireNonNull(sale, "sale");
        if (!countries.isEmpty() && !excludedCountries.isEmpty()) {
            throw new ValidationError("a filing names permitted countries or excluded ones, not both");
        }
    }

    /** Nothing filed. */
    public SalesRestriction() {
        this(List.of(), List.of(), List.of(), FormOfPayment.ANY, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.SALES_RESTRICTIONS;
    }

    /** Whether nothing is filed. The sale alone is no condition. */
    public boolean isEmpty() {
        return countries.isEmpty()
                && excludedCountries.isEmpty()
                && agencies.isEmpty()
                && payment == FormOfPayment.ANY
                && firstSale.isEmpty()
                && lastSale.isEmpty();
    }

    /**
     * Checks, in order, the country of sale against the permitted list (naming where it was {@code
     * sold_in} and the {@code permitted} countries) and the excluded one ({@code sold_in}), the
     * {@code agency}, the form of payment ({@code wanted} and {@code had}), and the sale window ({@code
     * first_sale} or {@code last_sale}), and fails on the first that does not hold. The sale date is
     * the sale's own, or the ticketing date.
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }
        if (sale.isEmpty()) {
            return fails(category(), "the fare restricts the sale and no sale details were given");
        }

        Sale made = sale.get();

        if (!countries.isEmpty() && !countries.contains(made.country())) {
            return fails(
                    category(),
                    "sold in " + made.country() + " and the fare is for sale in " + String.join("/", countries),
                    Details.of("sold_in", made.country(), "permitted", countries));
        }
        if (!excludedCountries.isEmpty() && excludedCountries.contains(made.country())) {
            return fails(
                    category(), "the fare is not for sale in " + made.country(), Details.of("sold_in", made.country()));
        }
        if (!agencies.isEmpty() && !agencies.contains(made.agency())) {
            String who = made.agency().isEmpty() ? "this agency" : made.agency();
            return fails(category(), who + " is not on the fare's list", Details.of("agency", made.agency()));
        }
        if (payment != FormOfPayment.ANY && made.payment() != payment) {
            return fails(
                    category(),
                    "the fare wants " + payment.value() + " and the sale was "
                            + made.payment().value(),
                    Details.of("wanted", payment.value(), "had", made.payment().value()));
        }

        LocalDate soldOn = made.on().orElse(context.ticketing());
        if (firstSale.isPresent() && soldOn.isBefore(firstSale.get())) {
            return fails(
                    category(),
                    "the fare goes on sale " + firstSale.get(),
                    Details.of("first_sale", firstSale.get().toString()));
        }
        if (lastSale.isPresent() && soldOn.isAfter(lastSale.get())) {
            return fails(
                    category(),
                    "the fare came off sale " + lastSale.get(),
                    Details.of("last_sale", lastSale.get().toString()));
        }

        return passes(category(), "sale in " + made.country() + " is permitted");
    }

    private static List<String> known(String field, List<String> codes) {
        List<String> upper = codes.stream().map(Text::code).toList();
        for (String code : upper) {
            if (!Geography.COUNTRIES.containsKey(code)) {
                throw new ValidationError("unknown country", Details.of("field", field, "country", code));
            }
        }
        return upper;
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private List<String> countries = List.of();
        private List<String> excludedCountries = List.of();
        private List<String> agencies = List.of();
        private FormOfPayment payment = FormOfPayment.ANY;
        private Optional<LocalDate> firstSale = Optional.empty();
        private Optional<LocalDate> lastSale = Optional.empty();
        private Optional<Sale> sale = Optional.empty();

        private Builder() {}

        public Builder countries(List<String> value) {
            countries = List.copyOf(value);
            return this;
        }

        public Builder excludedCountries(List<String> value) {
            excludedCountries = List.copyOf(value);
            return this;
        }

        public Builder agencies(List<String> value) {
            agencies = List.copyOf(value);
            return this;
        }

        public Builder payment(FormOfPayment value) {
            payment = value;
            return this;
        }

        public Builder firstSale(LocalDate value) {
            return firstSale(Optional.of(value));
        }

        /** The first day of sale, or that none is filed. */
        public Builder firstSale(Optional<LocalDate> value) {
            firstSale = value;
            return this;
        }

        public Builder lastSale(LocalDate value) {
            return lastSale(Optional.of(value));
        }

        /** The last day of sale, or that none is filed. */
        public Builder lastSale(Optional<LocalDate> value) {
            lastSale = value;
            return this;
        }

        public Builder sale(Sale value) {
            sale = Optional.of(value);
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when a country is unknown, or both country lists are filed
         */
        public SalesRestriction build() {
            return new SalesRestriction(countries, excludedCountries, agencies, payment, firstSale, lastSale, sale);
        }
    }
}
