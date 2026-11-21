package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 6: a number of nights, or a weekday the stay has to cross, or both, measured from the
 * arrival at the turnaround to the first departure home.
 *
 * <p>A weekday is not a number of days: a filing of Sunday wants the passenger away over a Sunday,
 * however short the trip. With both filed, both have to hold. A one way has no stay to measure and
 * passes.
 *
 * @param days the fewest nights, when filed
 * @param weekday the weekday the stay has to cross, as filed, when filed
 */
public record MinimumStay(Optional<Integer> days, Optional<String> weekday) implements Provision {

    /**
     * Checks what is filed.
     *
     * @throws ValidationError when the days are negative, or the weekday is not a day of the week
     */
    public MinimumStay {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(weekday, "weekday");
        if (days.isPresent() && days.get() < 0) {
            throw new ValidationError("a minimum stay is not negative", Details.of("days", days.get()));
        }
        weekday.ifPresent(Stay::weekdayIndex);
    }

    /** Nothing filed. */
    public MinimumStay() {
        this(Optional.empty(), Optional.empty());
    }

    /** A number of nights alone. */
    public MinimumStay(int days) {
        this(Optional.of(days), Optional.empty());
    }

    /** A weekday the stay has to cross, alone. */
    public MinimumStay(String weekday) {
        this(Optional.empty(), Optional.of(weekday));
    }

    /** A number of nights and a weekday, both of which have to hold. */
    public MinimumStay(int days, String weekday) {
        this(Optional.of(days), Optional.of(weekday));
    }

    @Override
    public Category category() {
        return Category.MINIMUM_STAY;
    }

    /** Whether nothing is filed. */
    public boolean isEmpty() {
        return days.isEmpty() && weekday.isEmpty();
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

        Optional<LocalDate> arrived = context.turnaroundArrival();
        if (arrived.isEmpty()) {
            return passes(category(), "no turnaround, no stay to measure");
        }
        int stayed = Math.toIntExact(ChronoUnit.DAYS.between(arrived.get(), home.get()));

        if (days.isPresent() && stayed < days.get()) {
            return fails(
                    category(),
                    "minimum stay is " + days.get() + " nights and the trip is " + stayed,
                    Details.of("needed", days.get(), "had", stayed));
        }

        if (weekday.isPresent()) {
            int wanted = Stay.weekdayIndex(weekday.get());
            if (!Stay.crossesWeekday(arrived.get(), home.get(), wanted)) {
                return fails(
                        category(),
                        "minimum stay wants a " + weekday.get() + " and the trip does not cross one",
                        Details.of("weekday", weekday.get()));
            }
        }

        return passes(category(), "stayed " + stayed + " nights", Details.of("nights", stayed));
    }
}
