package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.reissue.TaxReissue;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What the carrier did to a ticket and what is known about it, which is everything an involuntary
 * reissue and its compensation are worked out from.
 *
 * <p>Start from {@link #of(Cause)} and add what is known with the {@code with} methods. What is not
 * known stays out: no journey means no compensation is assessed at all, which is different from
 * none being owed.
 *
 * @param cause why the carrier is reaccommodating
 * @param taxes what the reissue does to the taxes, when that was worked out
 * @param booked the cabin booked
 * @param flown the cabin offered, when the reissue moves the passenger
 * @param cabinDifference what the two cabins differ by, priced above this module
 * @param journey the journey as booked, which the compensation band is read from
 * @param lateAtDestination how much later than scheduled the passenger reached the destination
 * @param daysNotice how many days before departure a cancellation or schedule change was notified
 * @param extraordinary whether the carrier can show circumstances it could not have avoided
 */
public record Disruption(
        Cause cause,
        Optional<TaxReissue> taxes,
        Cabin booked,
        Optional<Cabin> flown,
        Optional<Money> cabinDifference,
        Optional<Itinerary> journey,
        Duration lateAtDestination,
        int daysNotice,
        boolean extraordinary) {

    /**
     * Checks the lateness and the notice.
     *
     * @throws ValidationError when either is negative
     */
    public Disruption {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(taxes, "taxes");
        Objects.requireNonNull(booked, "booked");
        Objects.requireNonNull(flown, "flown");
        Objects.requireNonNull(cabinDifference, "cabinDifference");
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(lateAtDestination, "lateAtDestination");
        if (lateAtDestination.isNegative()) {
            throw new ValidationError("lateness is not negative", Details.of("late", lateAtDestination.toString()));
        }
        if (daysNotice < 0) {
            throw new ValidationError("notice is not negative", Details.of("days", daysNotice));
        }
    }

    /** A disruption with nothing known but its cause: economy booked, no move, on the day. */
    public static Disruption of(Cause cause) {
        return new Disruption(
                cause,
                Optional.empty(),
                Cabin.ECONOMY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ZERO,
                0,
                false);
    }

    /** A copy with the taxes worked out. */
    public Disruption withTaxes(TaxReissue report) {
        return new Disruption(
                cause,
                Optional.of(report),
                booked,
                flown,
                cabinDifference,
                journey,
                lateAtDestination,
                daysNotice,
                extraordinary);
    }

    /** A copy that moves the passenger from one cabin to another. */
    public Disruption withMove(Cabin was, Cabin now) {
        return new Disruption(
                cause,
                taxes,
                was,
                Optional.of(now),
                cabinDifference,
                journey,
                lateAtDestination,
                daysNotice,
                extraordinary);
    }

    /** A copy with what the two cabins differ by. */
    public Disruption withCabinDifference(Money difference) {
        return new Disruption(
                cause,
                taxes,
                booked,
                flown,
                Optional.of(difference),
                journey,
                lateAtDestination,
                daysNotice,
                extraordinary);
    }

    /** A copy with the journey as booked, so compensation can be assessed. */
    public Disruption withJourney(Itinerary booked) {
        return new Disruption(
                cause,
                taxes,
                this.booked,
                flown,
                cabinDifference,
                Optional.of(booked),
                lateAtDestination,
                daysNotice,
                extraordinary);
    }

    /** A copy with how late the passenger reached the destination. */
    public Disruption withLateness(Duration late) {
        return new Disruption(cause, taxes, booked, flown, cabinDifference, journey, late, daysNotice, extraordinary);
    }

    /** A copy notified this many days before departure. */
    public Disruption withNotice(int days) {
        return new Disruption(
                cause, taxes, booked, flown, cabinDifference, journey, lateAtDestination, days, extraordinary);
    }

    /** A copy the carrier says, or does not say, was beyond its control. */
    public Disruption withExtraordinary(boolean beyondControl) {
        return new Disruption(
                cause, taxes, booked, flown, cabinDifference, journey, lateAtDestination, daysNotice, beyondControl);
    }
}
