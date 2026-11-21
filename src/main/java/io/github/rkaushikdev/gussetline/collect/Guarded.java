package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A limiter and a breaker board that several workers may share, and one door to the schedule and the
 * sink.
 *
 * <p>Everything shared is locked, and the two things that decide whether a call goes out at all are
 * locked together. A token bucket is a read, a subtraction and a write, and six workers doing that at
 * once spend a budget of a thousand about eleven hundred times. A breaker is worse: the whole point of
 * it is that the fifth failure stops the sixth call, and six workers each holding their own count
 * never reach five.
 *
 * <p>What an answered task keeps, the price the schedule learns and the harvest the sink takes, goes
 * through one at a time under a lock of its own, so a slow write never holds up the budget checks.
 *
 * <p>Wraps rather than changes them, because the things being guarded are used unguarded everywhere
 * else and a lock inside them would cost every single threaded caller for the benefit of this one.
 */
final class Guarded implements Collector.Shared {

    private final Limiter limiter;
    private final BreakerBoard breakers;
    private final Object deciding = new Object();
    private final Object keeping = new Object();

    Guarded(Limiter limiter, BreakerBoard breakers) {
        this.limiter = limiter;
        this.breakers = breakers;
    }

    /** Whether one request may go out to a host right now, spending from its budget when it may. */
    boolean allow(String host, LocalDate today) {
        return refusal(host, today).isEmpty();
    }

    /**
     * Nothing when one request may go out to a host right now, spending from its budget; otherwise
     * why not, {@link HostBudget#QUOTA} or {@link HostBudget#RATE}. The answer and its reason are read
     * together, so the reason is this refusal's and not another worker's.
     */
    Optional<String> refusal(String host, LocalDate today) {
        synchronized (deciding) {
            if (limiter.allow(host, today)) {
                return Optional.empty();
            }
            return Optional.of(limiter.budgetFor(host).lastReason());
        }
    }

    /** Whether the supplier's breaker lets a call out. */
    boolean permits(String supplier) {
        synchronized (deciding) {
            return breakers.allow(supplier);
        }
    }

    @Override
    public void failed(String supplier) {
        synchronized (deciding) {
            breakers.failed(supplier);
        }
    }

    @Override
    public void succeeded(String supplier) {
        synchronized (deciding) {
            breakers.succeeded(supplier);
        }
    }

    @Override
    public void keep(Task task, LocalDate today, Harvest harvest, Schedule schedule, Sink sink) {
        synchronized (keeping) {
            Collector.keep(task, today, harvest, schedule, sink);
        }
    }
}
