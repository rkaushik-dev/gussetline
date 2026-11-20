package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDate;

/**
 * Both budgets for one host, and the reason it last refused.
 *
 * <p>The quota is asked first, so a request the day cannot pay for never takes a token from the
 * bucket.
 */
public final class HostBudget {

    /** The reason given when the day's quota is spent. */
    public static final String QUOTA = "quota";

    /** The reason given when the bucket is empty. */
    public static final String RATE = "rate";

    private final String host;
    private final TokenBucket bucket;
    private final DailyQuota quota;
    private int refusals;
    private String lastReason = "";

    public HostBudget(String host, TokenBucket bucket, DailyQuota quota) {
        this.host = host;
        this.bucket = bucket;
        this.quota = quota;
    }

    public String host() {
        return host;
    }

    public TokenBucket bucket() {
        return bucket;
    }

    public DailyQuota quota() {
        return quota;
    }

    /** How many requests this budget has refused. */
    public int refusals() {
        return refusals;
    }

    /** Why it last refused, {@link #QUOTA} or {@link #RATE}, or empty when it never has. */
    public String lastReason() {
        return lastReason;
    }

    /** Whether one request may go out right now, spending a token and a request when it may. */
    public boolean allow(LocalDate today) {
        quota.rollTo(today);
        if (quota.exhausted()) {
            refuse(QUOTA);
            return false;
        }
        if (!bucket.take()) {
            refuse(RATE);
            return false;
        }
        quota.spend(today);
        return true;
    }

    /** Seconds to wait before a request may go, or zero when waiting will not help. */
    public double waitFor() {
        if (quota.exhausted()) {
            return 0.0;
        }
        return bucket.waitFor();
    }

    /** The host, then the bucket, then the quota. */
    @Override
    public String toString() {
        return host + " " + bucket + " " + quota;
    }

    private void refuse(String reason) {
        refusals++;
        lastReason = reason;
    }
}
