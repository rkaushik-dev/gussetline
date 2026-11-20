package io.github.rkaushikdev.gussetline.discounts;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;
import java.util.Optional;

/**
 * One traveller, and the birth date their age is read from.
 *
 * @param kind the passenger type they travel as
 * @param born the date of birth, when one was given
 */
public record Passenger(PassengerType kind, Optional<LocalDate> born) {

    public Passenger {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(born, "born");
    }

    /** A traveller with no date of birth given. */
    public Passenger(PassengerType kind) {
        this(kind, Optional.empty());
    }

    /** A traveller born on a date. */
    public Passenger(PassengerType kind, LocalDate born) {
        this(kind, Optional.of(born));
    }

    /**
     * Whole years on a date, or empty when no birth date was given. A year is complete on the
     * birthday itself; a date before the birth gives a negative age.
     */
    public Optional<Integer> ageOn(LocalDate when) {
        return born.map(birth -> {
            int years = when.getYear() - birth.getYear();
            return MonthDay.from(when).isBefore(MonthDay.from(birth)) ? years - 1 : years;
        });
    }

    /** The passenger type's code, such as {@code CHD}. */
    @Override
    public String toString() {
        return kind.value();
    }
}
