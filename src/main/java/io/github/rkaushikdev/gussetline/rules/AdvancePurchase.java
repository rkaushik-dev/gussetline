package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 5, advance reservation and ticketing, as it is filed.
 *
 * <p>The rule has two clocks and they do not measure the same thing. The reservation clock runs
 * backwards from departure and says how early the seat has to be held. The ticketing clock runs
 * forwards from the reservation and says how long the hold lasts, and it is also capped by a second
 * backwards deadline from departure.
 *
 * <p>So the ticketing deadline is whichever of those two dates comes first, and that is where the
 * interesting behaviour is. A fare filed as three days after reservation and seven days before
 * departure gives a passenger who books six months out three days to pay, and a passenger who books
 * eight days out one day, because the seven days before departure has become the binding one. Both
 * deadlines are in the same filing and a system that reads only one of them prices a fare that
 * cannot be ticketed.
 *
 * <p>Every period may be absent, and absent means unrestricted rather than zero. Both clocks run
 * from the journey's first departure.
 *
 * @param reservationDays how many days before departure the booking has to exist
 * @param ticketingDaysAfterReservation how long the hold lasts
 * @param ticketingDaysBeforeDeparture the backstop deadline, in days before departure
 * @param bookedOn when the booking was made, when known; otherwise the ticketing date stands in
 */
public record AdvancePurchase(
        Optional<Integer> reservationDays,
        Optional<Integer> ticketingDaysAfterReservation,
        Optional<Integer> ticketingDaysBeforeDeparture,
        Optional<LocalDate> bookedOn)
        implements Provision {

    /**
     * Checks the periods.
     *
     * @throws ValidationError when a period is negative, naming the {@code field}: {@code
     *     reservation_days}, {@code ticketing_days_after_reservation} or {@code
     *     ticketing_days_before_departure}
     */
    public AdvancePurchase {
        Objects.requireNonNull(bookedOn, "bookedOn");
        refuseNegative("reservation_days", reservationDays);
        refuseNegative("ticketing_days_after_reservation", ticketingDaysAfterReservation);
        refuseNegative("ticketing_days_before_departure", ticketingDaysBeforeDeparture);
    }

    /** Nothing filed. */
    public AdvancePurchase() {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A builder starting from nothing filed. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.ADVANCE_RESERVATION;
    }

    /** Whether no period is filed. The booking date alone is no condition. */
    public boolean isEmpty() {
        return reservationDays.isEmpty()
                && ticketingDaysAfterReservation.isEmpty()
                && ticketingDaysBeforeDeparture.isEmpty();
    }

    /** The last day the booking may be made, or empty when no reservation period is filed. */
    public Optional<LocalDate> latestReservation(LocalDate departure) {
        return reservationDays.map(departure::minusDays);
    }

    /** {@link #ticketingDeadline(LocalDate, Optional)} for a booking made on a known date. */
    public Optional<LocalDate> ticketingDeadline(LocalDate departure, LocalDate booked) {
        return ticketingDeadline(departure, Optional.of(booked));
    }

    /**
     * The earlier of the two deadlines, or empty when neither is filed. The hold period counts only
     * when the booking date is known.
     *
     * <p>Both are computed and then the earlier wins. Returning the first one found would be right
     * about half the time and the half it is wrong about is the late booking, which is the one that
     * matters.
     */
    public Optional<LocalDate> ticketingDeadline(LocalDate departure, Optional<LocalDate> booked) {
        List<LocalDate> candidates = new ArrayList<>();
        if (ticketingDaysAfterReservation.isPresent() && booked.isPresent()) {
            candidates.add(booked.get().plusDays(ticketingDaysAfterReservation.get()));
        }
        ticketingDaysBeforeDeparture.ifPresent(days -> candidates.add(departure.minusDays(days)));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Collections.min(candidates));
    }

    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        LocalDate departure = context.journeyDeparture();
        LocalDate booked = bookedOn.orElse(context.ticketing());

        Optional<LocalDate> latest = latestReservation(departure);
        if (latest.isPresent() && booked.isAfter(latest.get())) {
            int had = Math.toIntExact(ChronoUnit.DAYS.between(booked, departure));
            return fails(
                    category(),
                    "booking has to be " + reservationDays.get() + " days out and it is " + had,
                    Details.of("needed", reservationDays.get(), "had", had));
        }

        Optional<LocalDate> deadline = ticketingDeadline(departure, booked);
        if (deadline.isPresent() && context.ticketing().isAfter(deadline.get())) {
            return fails(
                    category(),
                    "ticketing deadline was " + deadline.get() + " and it was sold " + context.ticketing(),
                    Details.of(
                            "deadline",
                            deadline.get().toString(),
                            "sold",
                            context.ticketing().toString()));
        }

        return passes(
                category(),
                "advance purchase satisfied",
                Details.of("deadline", deadline.map(LocalDate::toString).orElse("none")));
    }

    private static void refuseNegative(String field, Optional<Integer> period) {
        Objects.requireNonNull(period, field);
        if (period.isPresent() && period.get() < 0) {
            throw new ValidationError("an advance purchase period is not negative", Details.of("field", field));
        }
    }

    /** Collects a filing, starting from nothing filed. Every setter returns the builder. */
    public static final class Builder {

        private Optional<Integer> reservationDays = Optional.empty();
        private Optional<Integer> ticketingDaysAfterReservation = Optional.empty();
        private Optional<Integer> ticketingDaysBeforeDeparture = Optional.empty();
        private Optional<LocalDate> bookedOn = Optional.empty();

        private Builder() {}

        public Builder reservationDays(int value) {
            return reservationDays(Optional.of(value));
        }

        /** The reservation period, or that none is filed. */
        public Builder reservationDays(Optional<Integer> value) {
            reservationDays = value;
            return this;
        }

        public Builder ticketingDaysAfterReservation(int value) {
            return ticketingDaysAfterReservation(Optional.of(value));
        }

        /** The hold period, or that none is filed. */
        public Builder ticketingDaysAfterReservation(Optional<Integer> value) {
            ticketingDaysAfterReservation = value;
            return this;
        }

        public Builder ticketingDaysBeforeDeparture(int value) {
            return ticketingDaysBeforeDeparture(Optional.of(value));
        }

        /** The backstop period, or that none is filed. */
        public Builder ticketingDaysBeforeDeparture(Optional<Integer> value) {
            ticketingDaysBeforeDeparture = value;
            return this;
        }

        public Builder bookedOn(LocalDate value) {
            return bookedOn(Optional.of(value));
        }

        /** The booking date, or that it is not known. */
        public Builder bookedOn(Optional<LocalDate> value) {
            bookedOn = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when a period is negative
         */
        public AdvancePurchase build() {
            return new AdvancePurchase(
                    reservationDays, ticketingDaysAfterReservation, ticketingDaysBeforeDeparture, bookedOn);
        }
    }
}
