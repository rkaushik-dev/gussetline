package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.discounts.Passenger;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 1: which passengers the fare is for.
 *
 * <p>Category 1 is about the passenger and category 15 ({@link SalesRestriction}) is about the
 * transaction, and they fail differently. A passenger who does not qualify can sometimes be swapped
 * for one who does; a ticket sold in the wrong country cannot be fixed after the fact at all.
 *
 * <p>An age is the passenger's age on the journey's first departure. An age condition with no date
 * of birth fails rather than assuming one.
 *
 * @param passengerTypes the passenger types the fare is for; none filed means any
 * @param minAge the youngest the passenger may be, when filed
 * @param maxAge the oldest the passenger may be, when filed
 * @param residency the country the passenger has to be resident of, upper case, when filed
 * @param membership the membership the fare wants, when filed
 * @param holdsMembership whether the passenger holds the membership
 * @param residentOf the country the passenger is resident of, upper case, when stated
 */
public record Eligibility(
        List<PassengerType> passengerTypes,
        Optional<Integer> minAge,
        Optional<Integer> maxAge,
        Optional<String> residency,
        Optional<String> membership,
        boolean holdsMembership,
        Optional<String> residentOf)
        implements Provision {

    /**
     * Normalises the two countries to upper case.
     *
     * @throws ValidationError when either country is not one the desk knows, naming the {@code field}
     *     ({@code residency} or {@code resident_of}) and the {@code country}
     */
    public Eligibility {
        passengerTypes = List.copyOf(passengerTypes);
        Objects.requireNonNull(minAge, "minAge");
        Objects.requireNonNull(maxAge, "maxAge");
        Objects.requireNonNull(membership, "membership");
        residency = country("residency", residency);
        residentOf = country("resident_of", residentOf);
    }

    /** Nothing filed. */
    public Eligibility() {
        this(
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty());
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.ELIGIBILITY;
    }

    /**
     * Whether nothing is filed: no passenger types, ages, residency or membership. A stated residence
     * or a held membership alone is no condition.
     */
    public boolean isEmpty() {
        return passengerTypes.isEmpty()
                && minAge.isEmpty()
                && maxAge.isEmpty()
                && residency.isEmpty()
                && membership.isEmpty();
    }

    /**
     * Checks, in order, the passenger type (naming the types {@code wanted} and the one it {@code
     * had}), the ages (naming the {@code age}), the residency ({@code wanted} and {@code had}, which is
     * {@code unstated} when no residence is given) and the membership (naming the {@code
     * membership}), and fails on the first that does not hold.
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        Passenger passenger = context.passenger();

        if (!passengerTypes.isEmpty() && !passengerTypes.contains(passenger.kind())) {
            List<String> wanted =
                    passengerTypes.stream().map(PassengerType::value).toList();
            return fails(
                    category(),
                    "the fare is for " + String.join("/", wanted) + " and this is a "
                            + passenger.kind().value(),
                    Details.of("wanted", wanted, "had", passenger.kind().value()));
        }

        if (minAge.isPresent() || maxAge.isPresent()) {
            Optional<Integer> age = passenger.ageOn(context.journeyDeparture());
            if (age.isEmpty()) {
                return fails(category(), "the fare has an age condition and no date of birth was given");
            }
            if (minAge.isPresent() && age.get() < minAge.get()) {
                return fails(category(), "the fare wants " + minAge.get() + " and up", Details.of("age", age.get()));
            }
            if (maxAge.isPresent() && age.get() > maxAge.get()) {
                return fails(category(), "the fare wants up to " + maxAge.get(), Details.of("age", age.get()));
            }
        }

        if (residency.isPresent() && !residency.equals(residentOf)) {
            return fails(
                    category(),
                    "the fare is for residents of " + residency.get(),
                    Details.of("wanted", residency.get(), "had", residentOf.orElse("unstated")));
        }

        if (membership.isPresent() && !holdsMembership) {
            return fails(
                    category(),
                    "the fare wants " + membership.get() + " membership",
                    Details.of("membership", membership.get()));
        }

        return passes(category(), "the passenger qualifies");
    }

    private static Optional<String> country(String field, Optional<String> code) {
        Objects.requireNonNull(code, field);
        return code.map(given -> {
            String upper = Text.code(given);
            if (!Geography.COUNTRIES.containsKey(upper)) {
                throw new ValidationError("unknown country", Details.of("field", field, "country", upper));
            }
            return upper;
        });
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private List<PassengerType> passengerTypes = List.of();
        private Optional<Integer> minAge = Optional.empty();
        private Optional<Integer> maxAge = Optional.empty();
        private Optional<String> residency = Optional.empty();
        private Optional<String> membership = Optional.empty();
        private boolean holdsMembership;
        private Optional<String> residentOf = Optional.empty();

        private Builder() {}

        public Builder passengerTypes(List<PassengerType> value) {
            passengerTypes = List.copyOf(value);
            return this;
        }

        public Builder minAge(int value) {
            minAge = Optional.of(value);
            return this;
        }

        public Builder maxAge(int value) {
            maxAge = Optional.of(value);
            return this;
        }

        public Builder residency(String value) {
            residency = Optional.of(value);
            return this;
        }

        public Builder membership(String value) {
            membership = Optional.of(value);
            return this;
        }

        public Builder holdsMembership(boolean value) {
            holdsMembership = value;
            return this;
        }

        public Builder residentOf(String value) {
            residentOf = Optional.of(value);
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when either country is unknown
         */
        public Eligibility build() {
            return new Eligibility(passengerTypes, minAge, maxAge, residency, membership, holdsMembership, residentOf);
        }
    }
}
