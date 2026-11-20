package io.github.rkaushikdev.gussetline.discounts;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One filed discount and the passenger it is for.
 *
 * @param carrier the carrier that filed it, normalised to upper case
 * @param kind the passenger type it is for
 * @param percent the share of the fare it takes off, from nothing to everything
 * @param designator the ticket designator it is filed under, or empty
 * @param minAge the youngest age it covers, when it has a lower bound
 * @param maxAge the oldest age it covers, when it has an upper bound
 * @param accompanied whether the filing is for an accompanied passenger
 */
public record Discount(
        String carrier,
        PassengerType kind,
        BigDecimal percent,
        String designator,
        Optional<Integer> minAge,
        Optional<Integer> maxAge,
        boolean accompanied) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Normalises the carrier and checks the percentage and the age range.
     *
     * @throws ValidationError when the carrier is not a designator, the percentage is below zero or
     *     above a hundred, or the age range closes before it opens
     */
    public Discount {
        carrier = Carriers.normaliseCode(carrier);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(designator, "designator");
        Objects.requireNonNull(minAge, "minAge");
        Objects.requireNonNull(maxAge, "maxAge");
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new ValidationError(
                    "a discount is between nothing and everything", Details.of("percent", percent.toString()));
        }
        if (minAge.isPresent() && maxAge.isPresent() && minAge.get() > maxAge.get()) {
            throw new ValidationError("the age range closes before it opens");
        }
    }

    /** A discount with no designator, no age range and no accompaniment. */
    public Discount(String carrier, PassengerType kind, BigDecimal percent) {
        this(carrier, kind, percent, "");
    }

    /** A discount filed under a designator, with no age range and no accompaniment. */
    public Discount(String carrier, PassengerType kind, BigDecimal percent, String designator) {
        this(carrier, kind, percent, designator, Optional.empty(), Optional.empty(), false);
    }

    /** The same discount with a lower age bound. */
    public Discount withMinAge(int age) {
        return new Discount(carrier, kind, percent, designator, Optional.of(age), maxAge, accompanied);
    }

    /** The same discount with an upper age bound. */
    public Discount withMaxAge(int age) {
        return new Discount(carrier, kind, percent, designator, minAge, Optional.of(age), accompanied);
    }

    /** What the fare is multiplied by: three quarters for a twenty five percent discount. */
    public BigDecimal multiplier() {
        return Numbers.divide(HUNDRED.subtract(percent, Numbers.CONTEXT), HUNDRED);
    }

    /**
     * Whether this filing applies to this passenger on this date.
     *
     * <p>The age is read on the date of departure and not on the date of booking. A discount with an
     * age range and a passenger with no birth date do not match. Assuming the passenger qualifies is
     * how a desk sells a child fare to an adult and eats the difference on an audit.
     */
    public boolean covers(Passenger passenger, LocalDate departure) {
        if (passenger.kind() != kind) {
            return false;
        }
        if (minAge.isEmpty() && maxAge.isEmpty()) {
            return true;
        }
        Optional<Integer> age = passenger.ageOn(departure);
        if (age.isEmpty()) {
            return false;
        }
        if (minAge.isPresent() && age.get() < minAge.get()) {
            return false;
        }
        return !(maxAge.isPresent() && age.get() > maxAge.get());
    }

    /** Take the discount off an amount in neutral units, to the cent with halves away from zero. */
    public BigDecimal applyTo(BigDecimal nuc) {
        return nuc.multiply(multiplier(), Numbers.CONTEXT).setScale(2, RoundingMode.HALF_UP);
    }

    /** The designator it is filed under, or the passenger code when it has none. */
    public String name() {
        return designator.isEmpty() ? kind.value() : designator;
    }

    /** The carrier, the name and the percentage: {@code LH CH25 25 percent}. */
    @Override
    public String toString() {
        return carrier + " " + name() + " " + percent + " percent";
    }
}
