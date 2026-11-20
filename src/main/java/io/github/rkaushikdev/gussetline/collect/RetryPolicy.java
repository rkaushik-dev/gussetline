package io.github.rkaushikdev.gussetline.collect;

import java.util.random.RandomGenerator;

/**
 * How hard to try a supplier, and how politely: how many attempts, and how long to back off
 * between them.
 *
 * <p>The back off doubles from a base, is capped, and is then jittered by a fraction either way.
 * Two collectors that both retry after exactly two seconds arrive together the second time as well,
 * and the supplier sees a burst rather than a queue.
 *
 * @param attempts how many calls at most, the first included
 * @param baseDelay seconds to wait after the first failure, before jitter
 * @param maxDelay the most seconds any one wait may be
 * @param jitter the fraction of a wait it may move either way
 */
public record RetryPolicy(int attempts, double baseDelay, double maxDelay, double jitter) {

    /**
     * Refuses a policy that would never call at all.
     *
     * @throws IllegalArgumentException when attempts is below one
     */
    public RetryPolicy {
        if (attempts < 1) {
            throw new IllegalArgumentException("a retry policy tries at least once");
        }
    }

    /** Three attempts, half a second doubling to at most thirty, moved a quarter either way. */
    public RetryPolicy() {
        this(3);
    }

    /** The usual back off with a number of attempts of its own. */
    public RetryPolicy(int attempts) {
        this(attempts, 0.5, 30.0, 0.25);
    }

    public RetryPolicy withAttempts(int value) {
        return new RetryPolicy(value, baseDelay, maxDelay, jitter);
    }

    public RetryPolicy withBaseDelay(double value) {
        return new RetryPolicy(attempts, value, maxDelay, jitter);
    }

    public RetryPolicy withMaxDelay(double value) {
        return new RetryPolicy(attempts, baseDelay, value, jitter);
    }

    public RetryPolicy withJitter(double value) {
        return new RetryPolicy(attempts, baseDelay, maxDelay, value);
    }

    /**
     * Seconds to wait after a failed attempt: the base doubled once for each attempt after the first,
     * capped at the most, then moved by up to the jitter fraction of itself either way, uniformly,
     * and never below zero.
     */
    public double delayFor(int attempt, RandomGenerator random) {
        double raw = Math.min(baseDelay * Math.pow(2, attempt - 1), maxDelay);
        double spread = raw * jitter;
        double low = -spread;
        double moved = low + (spread - low) * random.nextDouble();
        return Math.max(0.0, raw + moved);
    }
}
