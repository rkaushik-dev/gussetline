package io.github.rkaushikdev.gussetline.timelimit;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.Objects;
import java.util.Optional;

/**
 * What a fare's category 5 says about when a held booking has to be ticketed: every clock it files,
 * and the hour a deadline falls at.
 *
 * <p>Start from {@link #none()}, which files nothing and leaves only the departure, and add what the
 * rule says with the {@code with} methods. Each count is checked when it is set.
 *
 * @param holdDays days after booking the hold lasts, when the rule has one
 * @param ticketByDaysBefore days before departure the ticket has to be issued, when the rule has a
 *     backstop
 * @param lateBooking the shorter hold a booking made close to departure gets, when the rule has one
 * @param instant whether the ticket is issued on booking
 * @param hour the hour of the day a deadline falls at
 */
public record TicketingTerms(
        Optional<Integer> holdDays,
        Optional<Integer> ticketByDaysBefore,
        Optional<LateBooking> lateBooking,
        boolean instant,
        int hour) {

    /**
     * Checks every count and the hour.
     *
     * @throws ValidationError when a count is negative, naming the clock, or the hour is not one of
     *     the day's
     */
    public TicketingTerms {
        Objects.requireNonNull(holdDays, "holdDays");
        Objects.requireNonNull(ticketByDaysBefore, "ticketByDaysBefore");
        Objects.requireNonNull(lateBooking, "lateBooking");
        holdDays.ifPresent(days -> refuseNegative(days, "hold"));
        ticketByDaysBefore.ifPresent(days -> refuseNegative(days, "backstop"));
        if (hour < 0 || hour > 23) {
            throw new ValidationError("that is not an hour of the day", Details.of("hour", hour));
        }
    }

    /** Nothing filed: the departure alone, at {@link TimeLimit#DEFAULT_HOUR}. */
    public static TicketingTerms none() {
        return new TicketingTerms(Optional.empty(), Optional.empty(), Optional.empty(), false, TimeLimit.DEFAULT_HOUR);
    }

    /** A copy with a hold of so many days after booking. */
    public TicketingTerms withHold(int days) {
        return new TicketingTerms(Optional.of(days), ticketByDaysBefore, lateBooking, instant, hour);
    }

    /** A copy with a backstop of so many days before departure. */
    public TicketingTerms withBackstop(int days) {
        return new TicketingTerms(holdDays, Optional.of(days), lateBooking, instant, hour);
    }

    /**
     * A copy where a booking made within {@code withinDays} of departure has to be ticketed within
     * {@code ticketWithinDays} of booking.
     */
    public TicketingTerms withLateBooking(int withinDays, int ticketWithinDays) {
        return new TicketingTerms(
                holdDays,
                ticketByDaysBefore,
                Optional.of(new LateBooking(withinDays, ticketWithinDays)),
                instant,
                hour);
    }

    /** A copy ticketed on booking, or not. */
    public TicketingTerms withInstant(boolean onBooking) {
        return new TicketingTerms(holdDays, ticketByDaysBefore, lateBooking, onBooking, hour);
    }

    /** A copy whose deadlines fall at another hour. */
    public TicketingTerms at(int deadlineHour) {
        return new TicketingTerms(holdDays, ticketByDaysBefore, lateBooking, instant, deadlineHour);
    }

    private static void refuseNegative(int days, String what) {
        if (days < 0) {
            throw new ValidationError("a " + what + " is not negative", Details.of("days", days));
        }
    }

    /**
     * The shorter hold for a booking made close to departure: within {@code withinDays} of the
     * flight, ticket within {@code ticketWithinDays} of booking. Nought means the same day.
     *
     * @param withinDays how close to departure a booking has to be made for this to apply
     * @param ticketWithinDays how many days after booking the ticket has to be issued then
     */
    public record LateBooking(int withinDays, int ticketWithinDays) {

        /**
         * Checks both counts.
         *
         * @throws ValidationError when either is negative
         */
        public LateBooking {
            refuseNegative(withinDays, "late booking window");
            refuseNegative(ticketWithinDays, "late booking hold");
        }

        /** Whether a booking made this many days before departure is a late one. */
        public boolean applies(long daysOut) {
            return daysOut <= withinDays;
        }
    }
}
