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
 * The other shape of category 5, where the ticket is issued on booking.
 *
 * <p>Filed as a number of hours rather than days, because it is usually one or twenty four and a day
 * granularity cannot tell those apart. The ticket has to be issued within the whole days the hours
 * make, and never less than one, of the booking. Zero hours means nothing is filed.
 *
 * @param hours the window the ticket has to be issued in
 * @param bookedAt when the booking was made, when known; otherwise the ticketing date stands in
 */
public record InstantPurchase(int hours, Optional<LocalDate> bookedAt) implements Provision {

    /**
     * Checks the window.
     *
     * @throws ValidationError when the hours are negative, naming them
     */
    public InstantPurchase {
        Objects.requireNonNull(bookedAt, "bookedAt");
        if (hours < 0) {
            throw new ValidationError("an instant purchase window is not negative", Details.of("hours", hours));
        }
    }

    /** Nothing filed. */
    public InstantPurchase() {
        this(0, Optional.empty());
    }

    /** A window with the booking date not known. */
    public InstantPurchase(int hours) {
        this(hours, Optional.empty());
    }

    /** A window for a booking made on a known date. */
    public InstantPurchase(int hours, LocalDate bookedAt) {
        this(hours, Optional.of(bookedAt));
    }

    @Override
    public Category category() {
        return Category.ADVANCE_RESERVATION;
    }

    @Override
    public Outcome evaluate(Context context) {
        if (hours == 0) {
            return silent(category());
        }
        LocalDate booked = bookedAt.orElse(context.ticketing());
        LocalDate deadline = booked.plusDays(Math.max(1, Math.floorDiv(hours, 24)));
        if (context.ticketing().isAfter(deadline)) {
            return fails(
                    category(),
                    "instant purchase within " + hours + " hours and it was later",
                    Details.of("hours", hours));
        }
        return passes(category(), "ticketed inside the " + hours + " hour window");
    }
}
