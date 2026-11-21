package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One advertised price for one departure date. No flights in it.
 *
 * <p>A quote cannot be audited, repriced from the tariff or watched for a reissue. What it can do is
 * tell the scheduler which dates are worth spending a real shopping request on.
 *
 * @param origin where the market starts
 * @param destination where the market ends
 * @param departure the departure date the price is for
 * @param amount the advertised price; nothing, in the page's currency, for a sold out day
 * @param observedAt when the page was read
 * @param carrier the carrier advertising it, or empty text when the page did not say
 * @param seats how many seats the page said were left, if it said
 * @param soldOut whether the day was sold out, in which case the amount means nothing
 */
public record Quote(
        String origin,
        String destination,
        LocalDate departure,
        Money amount,
        LocalDateTime observedAt,
        String carrier,
        Optional<Integer> seats,
        boolean soldOut) {

    public Quote {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(seats, "seats");
    }

    /** A live quote with no carrier and no seat count. */
    public Quote(String origin, String destination, LocalDate departure, Money amount, LocalDateTime observedAt) {
        this(origin, destination, departure, amount, observedAt, "", Optional.empty(), false);
    }

    /** The origin and the destination, in that order. */
    public List<String> market() {
        return List.of(origin, destination);
    }

    /**
     * Whether this quote's amount is less than another's.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when the two are in different
     *     currencies
     */
    public boolean cheaperThan(Quote other) {
        return amount.isLessThan(other.amount);
    }

    /** {@code FRANYC 2025-07-04 420.00 EUR}, or {@code FRANYC 2025-07-04 sold out}. */
    @Override
    public String toString() {
        String day = origin + destination + " " + departure;
        return soldOut ? day + " sold out" : day + " " + amount;
    }
}
