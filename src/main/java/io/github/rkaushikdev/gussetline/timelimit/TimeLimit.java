package io.github.rkaushikdev.gussetline.timelimit;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * When a held booking has to be paid for, and which clock decided.
 *
 * <p>A booking is held before it is ticketed, and the hold has a deadline. Miss it and the airline
 * cancels the booking, the fare goes back to the market, and the passenger finds out at the
 * airport. The desk's whole reason for holding anything is to reissue at a better fare, so the
 * deadline is the one date it cannot get wrong.
 *
 * <p>Three separate clocks decide it, a fourth when the booking is made late, and the earliest of
 * them wins.
 *
 * <p>The rule's own hold, from category 5: so many days after the booking was made. That is the one
 * everybody knows about.
 *
 * <p>The advance purchase backstop, also category 5: so many days before departure, whatever the
 * hold says. A booking made four months out with a seven day hold and a twenty one day backstop is
 * ticketed within a week, not three months later.
 *
 * <p>The late booking rule, category 5 again: a booking made within so many days of departure is
 * held for less, often only until the end of the day it was made.
 *
 * <p>And the departure itself, which needs no rule: a ticket issued after the flight has gone is not
 * a ticket. It is the clock nobody files because nobody imagines needing to, and it is the one that
 * catches a same day booking.
 *
 * <p>The earliest wins, and which one it was is reported, because an agent asking why a booking has
 * to be paid for today needs the reason and not the date.
 *
 * @param at the moment the booking has to be paid for by
 * @param clock which clock set it
 * @param booked the day the booking was made
 * @param departure the day it flies
 */
public record TimeLimit(LocalDateTime at, Clock clock, LocalDate booked, LocalDate departure) {

    /**
     * What time of day a deadline falls at when the carrier does not say. Late enough that a whole
     * working day is available and early enough that the overnight batch has not run.
     */
    public static final int DEFAULT_HOUR = 23;

    private static final DateTimeFormatter WRITTEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    public TimeLimit {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(booked, "booked");
        Objects.requireNonNull(departure, "departure");
    }

    /**
     * The limit when no rule is filed: the departure itself.
     *
     * @throws ValidationError when the booking was made after the flight
     */
    public static TimeLimit limitFor(LocalDate booked, LocalDate departure) {
        return limitFor(booked, departure, TicketingTerms.none());
    }

    /**
     * The limit under a hold of so many days after booking, or the departure if that comes first.
     *
     * @throws ValidationError when the booking was made after the flight or the hold is negative
     */
    public static TimeLimit limitFor(LocalDate booked, LocalDate departure, int holdDays) {
        return limitFor(booked, departure, TicketingTerms.none().withHold(holdDays));
    }

    /**
     * The limit under a hold and an advance purchase backstop, or the departure, whichever comes
     * first.
     *
     * @throws ValidationError when the booking was made after the flight or either count is negative
     */
    public static TimeLimit limitFor(LocalDate booked, LocalDate departure, int holdDays, int ticketByDaysBefore) {
        return limitFor(
                booked, departure, TicketingTerms.none().withHold(holdDays).withBackstop(ticketByDaysBefore));
    }

    /**
     * The earliest of the clocks the terms file, and which one it was.
     *
     * <p>A backstop that falls before the booking was made falls on the booking day instead. A tie
     * goes to a filed clock: an agent asking why a booking is due today wants the rule that says so,
     * not the calendar fact that the flight is also today. Between filed clocks on the same day, the
     * hold is named, then the backstop, then the late booking rule.
     *
     * <p>The late booking rule is the one that catches a booking made a few days out on a fare with a
     * long hold: within so many days of departure the hold shrinks to the one the rule gives late
     * bookings, and a booking made outside that window never reads it at all.
     *
     * <p>Instant purchase is the other shape of category 5, where the ticket is issued on booking. It
     * beats everything, because a booking that was never held has no deadline to miss: it is either
     * paid for now or it does not exist. No count is looked at then.
     *
     * @throws ValidationError when the booking was made after the flight
     */
    public static TimeLimit limitFor(LocalDate booked, LocalDate departure, TicketingTerms terms) {
        if (departure.isBefore(booked)) {
            throw new ValidationError(
                    "a booking is made before it flies",
                    Details.of("booked", booked.toString(), "departure", departure.toString()));
        }
        int hour = terms.hour();
        if (terms.instant()) {
            return new TimeLimit(booked.atTime(hour, 0), Clock.IMMEDIATE, booked, departure);
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(departure, Clock.DEPARTURE));
        terms.holdDays().ifPresent(days -> candidates.add(new Candidate(booked.plusDays(days), Clock.HOLD)));
        terms.ticketByDaysBefore().ifPresent(days -> {
            LocalDate backstop = departure.minusDays(days);
            candidates.add(new Candidate(backstop.isBefore(booked) ? booked : backstop, Clock.ADVANCE_PURCHASE));
        });
        terms.lateBooking()
                .filter(late -> late.applies(ChronoUnit.DAYS.between(booked, departure)))
                .ifPresent(late ->
                        candidates.add(new Candidate(booked.plusDays(late.ticketWithinDays()), Clock.LATE_BOOKING)));

        Candidate earliest = candidates.stream()
                .min(Comparator.comparing(Candidate::day)
                        .thenComparing(candidate -> !candidate.clock().isFiled()))
                .orElseThrow();
        return new TimeLimit(earliest.day().atTime(hour, 0), earliest.clock(), booked, departure);
    }

    /** Whether this one has to be dealt with now rather than tomorrow: due within a day. */
    public static boolean urgent(TimeLimit limit, LocalDate today) {
        return urgent(limit, today, 1);
    }

    /** Whether this one is due within {@code within} days of today, or has already gone. */
    public static boolean urgent(TimeLimit limit, LocalDate today, int within) {
        return limit.daysLeft(today) <= within;
    }

    /**
     * The one that falls first, which is the only one worth chasing, or nothing for no limits.
     *
     * <p>A booking with three coupons has three of these and the desk works to the earliest.
     * Reporting them all is how an agent pays for the wrong one.
     */
    public static Optional<TimeLimit> soonest(List<TimeLimit> limits) {
        return limits.stream().min(Comparator.comparing(TimeLimit::at));
    }

    /** Every limit due within a day, soonest first. */
    public static List<TimeLimit> fallingBy(List<TimeLimit> limits, LocalDate today) {
        return fallingBy(limits, today, 1);
    }

    /** Every limit due within {@code within} days of today, soonest first. */
    public static List<TimeLimit> fallingBy(List<TimeLimit> limits, LocalDate today, int within) {
        return limits.stream()
                .filter(limit -> limit.daysLeft(today) <= within)
                .sorted(Comparator.comparing(TimeLimit::at))
                .toList();
    }

    /**
     * Whether the booking was paid for in time, at or before the limit.
     *
     * <p>Nothing ticketed at all is not survival. A booking with no ticket against it after its limit
     * has gone is a booking the airline has already cancelled, whatever the desk's own records still
     * say.
     */
    public static boolean survived(TimeLimit limit, Optional<LocalDateTime> ticketed) {
        return ticketed.map(issued -> !issued.isAfter(limit.at)).orElse(false);
    }

    /** The day it falls on. */
    public LocalDate on() {
        return at.toLocalDate();
    }

    /** Days from today until it falls. Negative once it has gone. */
    public int daysLeft(LocalDate today) {
        return Math.toIntExact(ChronoUnit.DAYS.between(today, on()));
    }

    /** Whether it has gone: today is after the day it fell on. */
    public boolean expiredOn(LocalDate today) {
        return today.isAfter(on());
    }

    /** Whether it falls today. */
    public boolean isToday(LocalDate today) {
        return on().equals(today);
    }

    /** The moment to the minute and the reason: {@code 2025-11-17 23:00 by the hold on the booking}. */
    @Override
    public String toString() {
        return WRITTEN.format(at) + " by " + clock.value();
    }

    /** One clock's answer, before the earliest is picked. */
    private record Candidate(LocalDate day, Clock clock) {}
}
