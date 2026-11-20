package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.util.function.DoubleSupplier;

/**
 * The politeness budget for one host: a steady refill and a small burst.
 *
 * <p>That is the shape a supplier's own limiter has on the other side, so matching it means we are
 * throttled by our own code rather than by theirs. Being throttled by theirs costs a 429, and enough
 * 429s costs the account.
 *
 * <p>Time comes from the clock it is given, in seconds. A limiter that reads the wall clock cannot be
 * tested without sleeping and cannot be replayed at all.
 */
public final class TokenBucket {

    private final double rate;
    private final int burst;
    private final DoubleSupplier clock;
    private double tokens;
    private double last;

    /**
     * A full bucket of {@code burst} tokens that refills at {@code ratePerSecond}.
     *
     * @throws ValidationError when the rate is not positive or the burst is below one
     */
    public TokenBucket(double ratePerSecond, int burst, DoubleSupplier clock) {
        if (ratePerSecond <= 0) {
            throw new ValidationError("a refill rate is positive", Details.of("rate", ratePerSecond));
        }
        if (burst < 1) {
            throw new ValidationError("a bucket holds at least one token", Details.of("burst", burst));
        }
        this.rate = ratePerSecond;
        this.burst = burst;
        this.clock = clock;
        this.tokens = burst;
        this.last = clock.getAsDouble();
    }

    /** Tokens added per second. */
    public double rate() {
        return rate;
    }

    /** The most tokens the bucket holds. */
    public int burst() {
        return burst;
    }

    /** The tokens there are now, after the refill the clock says is due. */
    public double tokens() {
        refill();
        return tokens;
    }

    /** Take one token if it is there. */
    public boolean take() {
        return take(1);
    }

    /**
     * Take tokens if they are there. False means wait, not never.
     *
     * @throws ValidationError when the count is below one, or more than the whole bucket holds
     */
    public boolean take(int count) {
        if (count < 1) {
            throw new ValidationError("taking nothing is not a request", Details.of("count", count));
        }
        if (count > burst) {
            throw new ValidationError(
                    "a request larger than the whole bucket can never be served",
                    Details.of("count", count, "burst", burst));
        }
        refill();
        if (tokens < count) {
            return false;
        }
        tokens -= count;
        return true;
    }

    /** Seconds until one token exists. Zero when it already does. */
    public double waitFor() {
        return waitFor(1);
    }

    /** Seconds until the tokens exist. Zero when they already do. */
    public double waitFor(int count) {
        refill();
        if (tokens >= count) {
            return 0.0;
        }
        return (count - tokens) / rate;
    }

    /** The tokens to one place, the burst and the rate: {@code 5.0 of 5 tokens at 1.0/s}. */
    @Override
    public String toString() {
        return Numbers.fixed(tokens(), 1) + " of " + burst + " tokens at " + Numbers.floatText(rate) + "/s";
    }

    private void refill() {
        double now = clock.getAsDouble();
        double elapsed = Math.max(0.0, now - last);
        last = now;
        tokens = Math.min(burst, tokens + elapsed * rate);
    }
}
