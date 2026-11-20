package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * What the schedule remembers about one market and departure date: when it was last polled, the
 * price it found, and how many polls in a row have found that same price.
 *
 * <p>The market and the departure are fixed, because they are what the schedule files the series
 * under. The history changes with every poll ({@link #record}).
 */
public final class Series {

    /**
     * How the schedule files a series, in order of market and then departure.
     *
     * @param market the market's key, {@code FRA-NYC}
     * @param departure the departure date in ISO 8601, {@code 2025-06-04}
     */
    public record Key(String market, String departure) implements Comparable<Key> {

        private static final Comparator<Key> ORDER =
                Comparator.comparing(Key::market).thenComparing(Key::departure);

        public Key {
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(departure, "departure");
        }

        @Override
        public int compareTo(Key other) {
            return ORDER.compare(this, other);
        }
    }

    private final Market market;
    private final LocalDate departure;
    private LocalDate lastPolled;
    private int quietPolls;
    private String lastAmount;

    /** A series nobody has polled yet. */
    public Series(Market market, LocalDate departure) {
        this(market, departure, Optional.empty(), 0, "");
    }

    /**
     * A series with history already behind it.
     *
     * @param lastPolled the day it was last polled, if it ever was
     * @param quietPolls how many polls in a row have found the last price again
     * @param lastAmount the last price a poll found, as text, or empty when none ever did
     */
    public Series(
            Market market, LocalDate departure, Optional<LocalDate> lastPolled, int quietPolls, String lastAmount) {
        this.market = Objects.requireNonNull(market, "market");
        this.departure = Objects.requireNonNull(departure, "departure");
        this.lastPolled = lastPolled.orElse(null);
        this.quietPolls = quietPolls;
        this.lastAmount = Objects.requireNonNull(lastAmount, "lastAmount");
    }

    public Market market() {
        return market;
    }

    public LocalDate departure() {
        return departure;
    }

    /** The day it was last polled, or empty when it never has been. */
    public Optional<LocalDate> lastPolled() {
        return Optional.ofNullable(lastPolled);
    }

    /**
     * Carry over the day of the last poll, so the cadence picks up where it left off after a
     * restart.
     */
    public void setLastPolled(LocalDate day) {
        lastPolled = Objects.requireNonNull(day, "day");
    }

    /** How many polls in a row have found the last price again. A move resets it. */
    public int quietPolls() {
        return quietPolls;
    }

    /** The last price a poll found, as text, or empty when none ever did. */
    public String lastAmount() {
        return lastAmount;
    }

    /** What the schedule files this series under. */
    public Key key() {
        return new Key(market.key(), departure.toString());
    }

    /** Days from {@code today} to the departure; negative once it has gone. */
    public int daysOut(LocalDate today) {
        return Math.toIntExact(ChronoUnit.DAYS.between(today, departure));
    }

    /**
     * Whether this series wants polling today: never polled, or polled at least an interval ago
     * ({@link Schedule#intervalFor}).
     *
     * <p>A departure that has gone is never due again, which is the only rule here that saves real
     * money: nine thousand itineraries a year means about twenty five expiring every day. A departure
     * today is still due.
     */
    public boolean dueOn(LocalDate today) {
        int out = daysOut(today);
        if (out < 0) {
            return false;
        }
        if (lastPolled == null) {
            return true;
        }
        return ChronoUnit.DAYS.between(lastPolled, today) >= Schedule.intervalFor(out, quietPolls);
    }

    /**
     * Note a poll, and whether it found the price had moved.
     *
     * <p>Finding the last price again adds a quiet poll. Finding another price resets the run. A poll
     * that found no price at all, given as empty text, also resets the run but keeps the last price.
     */
    public void record(LocalDate today, String amount) {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isEmpty() && amount.equals(lastAmount)) {
            quietPolls++;
        } else {
            quietPolls = 0;
        }
        if (!amount.isEmpty()) {
            lastAmount = amount;
        }
        lastPolled = today;
    }

    /** The market, the departure and the quiet run: {@code FRA-NYC 2025-06-04 quiet 1}. */
    @Override
    public String toString() {
        return market + " " + departure + " quiet " + quietPolls;
    }
}
