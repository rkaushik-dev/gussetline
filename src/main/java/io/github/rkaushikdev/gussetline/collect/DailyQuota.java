package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.BudgetExhausted;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * How many requests a supplier has sold us today.
 *
 * <p>The daily quota is commercial. Suppliers sell a number of shopping requests a day and the desk
 * pays for them, so a collector that burns the quota on a market nobody is watching has spent real
 * money. When it runs out the answer is not to wait, it is to stop until midnight, which is why the
 * quota and the {@link TokenBucket} are separate objects and not one with a flag.
 */
public final class DailyQuota {

    private final int limit;
    private int used;
    private LocalDate day;

    /**
     * A quota of {@code limit} requests a day, none of them used yet.
     *
     * @throws ValidationError when the limit is negative
     */
    public DailyQuota(int limit) {
        if (limit < 0) {
            throw new ValidationError("a quota is not negative", Details.of("limit", limit));
        }
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    /** The requests spent on the current day. */
    public int used() {
        return used;
    }

    /** The day the count is for, once a request has been made. */
    public Optional<LocalDate> day() {
        return Optional.ofNullable(day);
    }

    /** The requests left today, never below zero. */
    public int remaining() {
        return Math.max(0, limit - used);
    }

    /** Whether nothing is left today. */
    public boolean exhausted() {
        return remaining() == 0;
    }

    /** Start a new day. Idempotent, so it can be called on every request. */
    public void rollTo(LocalDate today) {
        if (!Objects.equals(day, today)) {
            day = today;
            used = 0;
        }
    }

    /** Spend one request. */
    public void spend(LocalDate today) {
        spend(today, 1);
    }

    /**
     * Spend requests on a day, rolling to it first.
     *
     * @throws BudgetExhausted when fewer are left than wanted, in which case nothing is spent
     */
    public void spend(LocalDate today, int count) {
        rollTo(today);
        if (count > remaining()) {
            throw new BudgetExhausted(
                    "the day's quota is spent", Details.of("limit", limit, "used", used, "wanted", count));
        }
        used += count;
    }

    /** {@code 2 of 5 used}. */
    @Override
    public String toString() {
        return used + " of " + limit + " used";
    }
}
