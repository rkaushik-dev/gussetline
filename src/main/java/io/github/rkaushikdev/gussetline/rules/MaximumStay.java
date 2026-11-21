package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 7: how long the passenger may be away, in days or months from the start of the journey,
 * or from the arrival at the turnaround when filed that way.
 *
 * <p>The limit is the last date the first departure home may be on. A one way has no return to
 * measure and passes.
 *
 * @param days the most days, when filed
 * @param months the most calendar months, when filed
 * @param fromTurnaround whether the limit runs from the arrival at the turnaround rather than from
 *     the departure from origin
 */
public record MaximumStay(Optional<Integer> days, Optional<Integer> months, boolean fromTurnaround)
        implements Provision {

    /**
     * Checks what is filed.
     *
     * @throws ValidationError when the days or the months are filed and not positive
     */
    public MaximumStay {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(months, "months");
        if (days.isPresent() && days.get() <= 0) {
            throw new ValidationError("a maximum stay is positive", Details.of("days", days.get()));
        }
        if (months.isPresent() && months.get() <= 0) {
            throw new ValidationError("a maximum stay is positive", Details.of("months", months.get()));
        }
    }

    /** Nothing filed. */
    public MaximumStay() {
        this(Optional.empty(), Optional.empty(), false);
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.MAXIMUM_STAY;
    }

    /** Whether nothing is filed. */
    public boolean isEmpty() {
        return days.isEmpty() && months.isEmpty();
    }

    /**
     * The last date travel may still be under way, counting from a start date.
     *
     * <p>Months land on the same day number, or on the last day of a month too short to have it, so
     * a month from the thirty first of January is the twenty eighth of February. With both days and
     * months filed the later of the two limits holds. With neither, the start itself.
     */
    public LocalDate limitFrom(LocalDate start) {
        LocalDate latest = start;
        if (days.isPresent()) {
            latest = start.plusDays(days.get());
        }
        if (months.isPresent()) {
            LocalDate candidate = start.plusMonths(months.get());
            latest = days.isPresent() && latest.isAfter(candidate) ? latest : candidate;
        }
        return latest;
    }

    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        Optional<LocalDate> home = context.returnDeparture();
        if (home.isEmpty()) {
            return passes(category(), "one way, no stay to measure");
        }
        LocalDate start = context.journeyDeparture();
        if (fromTurnaround) {
            Optional<LocalDate> arrived = context.turnaroundArrival();
            if (arrived.isEmpty()) {
                return passes(category(), "no turnaround, no stay to measure");
            }
            start = arrived.get();
        }

        LocalDate latest = limitFrom(start);
        if (home.get().isAfter(latest)) {
            return fails(
                    category(),
                    "maximum stay ends " + latest + " and the return is " + home.get(),
                    Details.of(
                            "latest", latest.toString(), "returned", home.get().toString()));
        }
        return passes(category(), "return is inside " + latest);
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private Optional<Integer> days = Optional.empty();
        private Optional<Integer> months = Optional.empty();
        private boolean fromTurnaround;

        private Builder() {}

        public Builder days(int value) {
            return days(Optional.of(value));
        }

        /** The most days, or that none are filed. */
        public Builder days(Optional<Integer> value) {
            days = value;
            return this;
        }

        public Builder months(int value) {
            return months(Optional.of(value));
        }

        /** The most months, or that none are filed. */
        public Builder months(Optional<Integer> value) {
            months = value;
            return this;
        }

        public Builder fromTurnaround(boolean value) {
            fromTurnaround = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when the days or the months are filed and not positive
         */
        public MaximumStay build() {
            return new MaximumStay(days, months, fromTurnaround);
        }
    }
}
