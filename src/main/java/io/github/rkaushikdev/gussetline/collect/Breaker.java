package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.function.DoubleSupplier;

/**
 * The circuit breaker for one supplier, which stops a dead supplier costing us the whole run.
 *
 * <p>A supplier that has failed twenty times in a row is not going to answer the twenty first, and
 * every one of those calls has a timeout on it. Ten markets against a dead host is ten times the
 * timeout before the run gets to the next supplier, and a nightly collection that should take four
 * minutes takes forty.
 *
 * <p>Three states and the middle one is the point. Closed is normal. Open means stop, and it opens
 * after a run of failures rather than after a rate, because a rate over a long window keeps a dying
 * host closed for hours. Half open is the recovery probe: after a cooling period exactly one call is
 * let through, and what it does decides whether the breaker closes again or goes straight back to
 * open for another cooling period.
 *
 * <p>Letting several through at once in half open is the mistake, because a host that is up but
 * struggling gets a burst at the worst possible moment and dies again, which reads as a host that
 * never recovers.
 *
 * <p>Time comes from the clock it is given, in seconds. A breaker is not safe to share between
 * threads on its own; whoever shares one guards it.
 */
public final class Breaker {

    /** Where a breaker stands. */
    public enum State {
        CLOSED("closed"),
        OPEN("open"),
        HALF_OPEN("half_open");

        private final String value;

        State(String value) {
            this.value = value;
        }

        /** The name the summary and the reports write. */
        public String value() {
            return value;
        }

        /**
         * The state a name stands for.
         *
         * @throws IllegalArgumentException when the name is no state
         */
        public static State of(String value) {
            for (State state : values()) {
                if (state.value.equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("'" + value + "' is not a valid State");
        }
    }

    /** How many failures in a row trip a breaker unless told otherwise. */
    public static final int THRESHOLD = 5;

    /** How many seconds an open breaker cools for unless told otherwise. */
    public static final double COOLDOWN = 60.0;

    private final String name;
    private final int threshold;
    private final double cooldown;
    private final DoubleSupplier clock;

    private State state = State.CLOSED;
    private int failures;
    private double openedAt;
    private int trips;
    private int probes;
    private boolean probeOut;

    /** A breaker with the usual threshold and cooldown, on a clock that never moves. */
    public Breaker(String name) {
        this(name, THRESHOLD, COOLDOWN, () -> 0.0);
    }

    /** A breaker on a clock that never moves. */
    public Breaker(String name, int threshold, double cooldown) {
        this(name, threshold, cooldown, () -> 0.0);
    }

    /**
     * A breaker that trips after {@code threshold} failures in a row and cools for {@code cooldown}
     * seconds on {@code clock}.
     *
     * @throws ValidationError when the threshold is below one or the cooldown is negative
     */
    public Breaker(String name, int threshold, double cooldown, DoubleSupplier clock) {
        if (threshold < 1) {
            throw new ValidationError("a breaker trips after at least one failure");
        }
        if (cooldown < 0) {
            throw new ValidationError("a cooldown is not negative");
        }
        this.name = name;
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public String name() {
        return name;
    }

    public int threshold() {
        return threshold;
    }

    /** The cooling period, in seconds. */
    public double cooldown() {
        return cooldown;
    }

    public State state() {
        return state;
    }

    /** The failures in the current run. A success clears it. */
    public int failures() {
        return failures;
    }

    /** When the breaker last opened, on its clock. */
    public double openedAt() {
        return openedAt;
    }

    /** How many times the breaker has opened. */
    public int trips() {
        return trips;
    }

    /** How many recovery probes it has let through. */
    public int probes() {
        return probes;
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    /** Whether a call may go out, moving the state if the clock says so. */
    public boolean allow() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (!cooled()) {
                return false;
            }
            state = State.HALF_OPEN;
            probeOut = false;
        }
        if (probeOut) {
            return false;
        }
        probeOut = true;
        probes++;
        return true;
    }

    /** A good answer. Closes a half open breaker and clears the run. */
    public void succeeded() {
        state = State.CLOSED;
        failures = 0;
        probeOut = false;
    }

    /** A bad answer. Trips on the threshold, or reopens after a probe. */
    public void failed() {
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        failures++;
        if (failures >= threshold) {
            open();
        }
    }

    /**
     * Throw rather than answer, for a call site that cannot check first.
     *
     * @throws SupplierUnavailable when no call may go out, naming the supplier and its trips
     */
    public void guard() {
        if (!allow()) {
            throw new SupplierUnavailable("the breaker is open", Details.of("supplier", name, "trips", trips));
        }
    }

    /** The supplier, the state and the run: {@code gds closed after 1 failures}. */
    @Override
    public String toString() {
        return name + " " + state.value() + " after " + failures + " failures";
    }

    private boolean cooled() {
        return clock.getAsDouble() - openedAt >= cooldown;
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.getAsDouble();
        trips++;
        probeOut = false;
    }
}
