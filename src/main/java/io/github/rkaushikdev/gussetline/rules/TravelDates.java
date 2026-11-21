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
 * Category 14: when travel has to start, and when it has to be over, read against the two ends of
 * the whole journey rather than of the component.
 *
 * <p>The departure window is checked before the completion date, so a journey wrong on both is
 * refused for its start.
 *
 * @param firstDeparture the earliest the journey may start, when filed
 * @param lastDeparture the latest the journey may start, when filed
 * @param completeBy the date the journey has to be over by, when filed
 */
public record TravelDates(
        Optional<LocalDate> firstDeparture, Optional<LocalDate> lastDeparture, Optional<LocalDate> completeBy)
        implements Provision {

    /**
     * Checks the departure window.
     *
     * @throws ValidationError when the window closes before it opens
     */
    public TravelDates {
        Objects.requireNonNull(firstDeparture, "firstDeparture");
        Objects.requireNonNull(lastDeparture, "lastDeparture");
        Objects.requireNonNull(completeBy, "completeBy");
        if (firstDeparture.isPresent()
                && lastDeparture.isPresent()
                && lastDeparture.get().isBefore(firstDeparture.get())) {
            throw new ValidationError("the departure window closes before it opens");
        }
    }

    /** Nothing filed. */
    public TravelDates() {
        this(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.TRAVEL_RESTRICTIONS;
    }

    /** Whether nothing is filed. */
    public boolean isEmpty() {
        return firstDeparture.isEmpty() && lastDeparture.isEmpty() && completeBy.isEmpty();
    }

    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        LocalDate out = context.journeyDeparture();
        if (firstDeparture.isPresent() && out.isBefore(firstDeparture.get())) {
            return fails(
                    category(),
                    "travel may not start before " + firstDeparture.get(),
                    Details.of("earliest", firstDeparture.get().toString(), "departure", out.toString()));
        }
        if (lastDeparture.isPresent() && out.isAfter(lastDeparture.get())) {
            return fails(
                    category(),
                    "travel may not start after " + lastDeparture.get(),
                    Details.of("latest", lastDeparture.get().toString(), "departure", out.toString()));
        }

        if (completeBy.isPresent()) {
            LocalDate done = context.itinerary().arrival().toLocalDate();
            if (done.isAfter(completeBy.get())) {
                return fails(
                        category(),
                        "travel has to be over by " + completeBy.get() + " and it ends " + done,
                        Details.of("complete_by", completeBy.get().toString(), "ends", done.toString()));
            }
        }

        return passes(category(), "inside the filed travel dates");
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private Optional<LocalDate> firstDeparture = Optional.empty();
        private Optional<LocalDate> lastDeparture = Optional.empty();
        private Optional<LocalDate> completeBy = Optional.empty();

        private Builder() {}

        public Builder firstDeparture(LocalDate value) {
            return firstDeparture(Optional.of(value));
        }

        /** The earliest start, or that none is filed. */
        public Builder firstDeparture(Optional<LocalDate> value) {
            firstDeparture = value;
            return this;
        }

        public Builder lastDeparture(LocalDate value) {
            return lastDeparture(Optional.of(value));
        }

        /** The latest start, or that none is filed. */
        public Builder lastDeparture(Optional<LocalDate> value) {
            lastDeparture = value;
            return this;
        }

        public Builder completeBy(LocalDate value) {
            return completeBy(Optional.of(value));
        }

        /** The completion date, or that none is filed. */
        public Builder completeBy(Optional<LocalDate> value) {
            completeBy = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when the departure window closes before it opens
         */
        public TravelDates build() {
            return new TravelDates(firstDeparture, lastDeparture, completeBy);
        }
    }
}
