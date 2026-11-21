package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Category 9: how many breaks under the stopover threshold the fare permits, where, and whether
 * they have to stay on one carrier.
 *
 * <p>The points are the same ones category 8 counts; a connection that runs long stops being a
 * transfer and becomes a stopover, with nothing in either rule changing (see {@link Stopovers}).
 * Points are compared as cities. Unlike category 8, the count is per direction unless filed
 * otherwise.
 *
 * @param permitted how many transfers the fare permits, when filed; absent means any number
 * @param atPoints the only cities a transfer may be made at, folded to cities; none filed means any
 * @param onlineOnly whether every sector of the component has to be marketed by one carrier
 * @param perDirection whether only the points inside the component being priced count, rather than
 *     every connection on the journey
 */
public record Transfers(Optional<Integer> permitted, List<String> atPoints, boolean onlineOnly, boolean perDirection)
        implements Provision {

    /**
     * Checks the allowance and folds the named points to cities.
     *
     * @throws ValidationError when the allowance is negative, or a named point is neither a city nor
     *     an airport
     */
    public Transfers {
        Objects.requireNonNull(permitted, "permitted");
        if (permitted.isPresent() && permitted.get() < 0) {
            throw new ValidationError("a transfer allowance is not negative");
        }
        atPoints = atPoints.stream().map(Geography::cityOf).toList();
    }

    /** Nothing filed. */
    public Transfers() {
        this(Optional.empty(), List.of(), false, true);
    }

    /** An allowance alone: that many transfers in the component, anywhere, on any carriers. */
    public Transfers(int permitted) {
        this(Optional.of(permitted), List.of(), false, true);
    }

    /** A builder starting from nothing filed, counting per direction. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.TRANSFERS;
    }

    /** Whether nothing is filed: no allowance, no named points, and no online condition. */
    public boolean isEmpty() {
        return permitted.isEmpty() && atPoints.isEmpty() && !onlineOnly;
    }

    /**
     * Fails on the first transfer at a city the fare does not name (naming it {@code at} and the
     * {@code permitted} cities), then on more transfers than the allowance (the number {@code taken}
     * and the number {@code permitted}), then on an online only filing whose component is marketed by
     * more than one carrier (the {@code carriers}, sorted).
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        List<String> taken = Breaks.counted(context.itinerary().connections(), context, perDirection);

        if (!atPoints.isEmpty()) {
            Optional<String> outside =
                    taken.stream().filter(point -> !atPoints.contains(point)).findFirst();
            if (outside.isPresent()) {
                return fails(
                        category(),
                        "transfer at " + outside.get() + " and the fare permits only " + String.join("/", atPoints),
                        Details.of("at", outside.get(), "permitted", atPoints));
            }
        }

        if (permitted.isPresent() && taken.size() > permitted.get()) {
            return fails(
                    category(),
                    taken.size() + " transfers and the fare permits " + permitted.get(),
                    Details.of("taken", taken.size(), "permitted", permitted.get()));
        }

        if (onlineOnly) {
            Set<String> carriers = context.component().segments().stream()
                    .map(Segment::marketingCarrier)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (carriers.size() > 1) {
                return fails(
                        category(),
                        "the fare permits online transfers only and this one is interline",
                        Details.of("carriers", List.copyOf(carriers)));
            }
        }

        return passes(category(), taken.size() + " transfers", Details.of("taken", taken.size()));
    }

    /** Collects a filing, starting from nothing filed and counting per direction. */
    public static final class Builder {

        private Optional<Integer> permitted = Optional.empty();
        private List<String> atPoints = List.of();
        private boolean onlineOnly;
        private boolean perDirection = true;

        private Builder() {}

        public Builder permitted(int value) {
            return permitted(Optional.of(value));
        }

        /** The allowance, or that none is filed. */
        public Builder permitted(Optional<Integer> value) {
            permitted = value;
            return this;
        }

        public Builder atPoints(List<String> value) {
            atPoints = List.copyOf(value);
            return this;
        }

        public Builder onlineOnly(boolean value) {
            onlineOnly = value;
            return this;
        }

        public Builder perDirection(boolean value) {
            perDirection = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when the allowance is negative, or a named point is unknown
         */
        public Transfers build() {
            return new Transfers(permitted, atPoints, onlineOnly, perDirection);
        }
    }
}
