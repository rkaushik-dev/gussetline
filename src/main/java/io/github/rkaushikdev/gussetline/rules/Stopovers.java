package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 8: how many breaks over the stopover threshold the fare permits, where, and what the
 * ones past the free allowance cost.
 *
 * <p>Categories 8 and 9 count the same points and count them differently, and which bucket a point
 * falls into is decided by a clock reading in the itinerary rather than by anything in the rule. A
 * fare that permits one stopover and four transfers is perfectly happy with a journey that has five
 * intermediate points, as long as four of them are under the ground time threshold, and it refuses
 * the identical routing if one connection runs long. The rules count, the itinerary decides what is
 * being counted, and neither of them can be checked without the other.
 *
 * <p>Charged stopovers are the second thing. A fare permits some number free and some number for
 * money, and the money is added to the component in neutral units, so a stopover charge is a fare
 * surcharge and not a fee. It goes on before the minimum checks like every other fare surcharge.
 *
 * <p>Points are compared as cities, so a stopover at Heathrow is a stopover at London.
 *
 * @param permitted how many stopovers the fare permits, when filed; absent means any number
 * @param free how many of them cost nothing
 * @param chargeNuc what each stopover past the free ones costs, in neutral units
 * @param atPoints the only cities a stopover may be made at, folded to cities; none filed means any
 * @param perDirection whether only the points inside the component being priced count, rather than
 *     every stopover on the journey
 */
public record Stopovers(
        Optional<Integer> permitted, int free, BigDecimal chargeNuc, List<String> atPoints, boolean perDirection)
        implements Provision {

    /**
     * Checks the counts and folds the named points to cities.
     *
     * @throws ValidationError when the allowance, the free count or the charge is negative, or a named
     *     point is neither a city nor an airport
     */
    public Stopovers {
        Objects.requireNonNull(permitted, "permitted");
        Objects.requireNonNull(chargeNuc, "chargeNuc");
        if (permitted.isPresent() && permitted.get() < 0) {
            throw new ValidationError("a stopover allowance is not negative");
        }
        if (free < 0) {
            throw new ValidationError("a free stopover count is not negative");
        }
        if (chargeNuc.signum() < 0) {
            throw new ValidationError("a stopover charge is not negative");
        }
        atPoints = atPoints.stream().map(Geography::cityOf).toList();
    }

    /** Nothing filed. */
    public Stopovers() {
        this(Optional.empty(), 0, BigDecimal.ZERO, List.of(), false);
    }

    /** An allowance alone: that many stopovers, anywhere, none of them charged. */
    public Stopovers(int permitted) {
        this(Optional.of(permitted), 0, BigDecimal.ZERO, List.of(), false);
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.STOPOVERS;
    }

    /** Whether nothing is filed: no allowance and no named points. */
    public boolean isEmpty() {
        return permitted.isEmpty() && atPoints.isEmpty();
    }

    /**
     * What the stopovers on this journey cost, over and above the free ones, in neutral units. Zero
     * when no charge is filed.
     */
    public BigDecimal chargeFor(Context context) {
        if (chargeNuc.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int chargeable = Math.max(0, taken(context).size() - free);
        return chargeNuc.multiply(BigDecimal.valueOf(chargeable), Numbers.CONTEXT);
    }

    /**
     * Fails on the first stopover at a city the fare does not name (naming it {@code at} and the
     * {@code permitted} cities), then on more stopovers than the allowance (the number {@code taken}
     * and the number {@code permitted}).
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        List<String> taken = taken(context);

        if (!atPoints.isEmpty()) {
            Optional<String> outside =
                    taken.stream().filter(point -> !atPoints.contains(point)).findFirst();
            if (outside.isPresent()) {
                return fails(
                        category(),
                        "stopover at " + outside.get() + " and the fare permits only " + String.join("/", atPoints),
                        Details.of("at", outside.get(), "permitted", atPoints));
            }
        }

        if (permitted.isPresent() && taken.size() > permitted.get()) {
            return fails(
                    category(),
                    taken.size() + " stopovers and the fare permits " + permitted.get(),
                    Details.of("taken", taken.size(), "permitted", permitted.get()));
        }

        return passes(
                category(),
                taken.size() + " stopovers, " + Math.max(0, taken.size() - free) + " chargeable",
                Details.of("taken", taken.size()));
    }

    /** The cities stopped over at that this filing counts, in journey order. */
    private List<String> taken(Context context) {
        return Breaks.counted(context.itinerary().stopovers(), context, perDirection);
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private Optional<Integer> permitted = Optional.empty();
        private int free;
        private BigDecimal chargeNuc = BigDecimal.ZERO;
        private List<String> atPoints = List.of();
        private boolean perDirection;

        private Builder() {}

        public Builder permitted(int value) {
            return permitted(Optional.of(value));
        }

        /** The allowance, or that none is filed. */
        public Builder permitted(Optional<Integer> value) {
            permitted = value;
            return this;
        }

        public Builder free(int value) {
            free = value;
            return this;
        }

        public Builder chargeNuc(BigDecimal value) {
            chargeNuc = value;
            return this;
        }

        public Builder atPoints(List<String> value) {
            atPoints = List.copyOf(value);
            return this;
        }

        public Builder perDirection(boolean value) {
            perDirection = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when a count or the charge is negative, or a named point is unknown
         */
        public Stopovers build() {
            return new Stopovers(permitted, free, chargeNuc, atPoints, perDirection);
        }
    }
}
