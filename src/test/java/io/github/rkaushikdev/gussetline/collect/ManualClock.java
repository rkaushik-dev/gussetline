package io.github.rkaushikdev.gussetline.collect;

import java.util.function.DoubleSupplier;

/** A clock in seconds that a test moves by hand, so nothing time based ever has to wait. */
final class ManualClock implements DoubleSupplier {

    private double now;

    ManualClock() {
        this(0.0);
    }

    ManualClock(double start) {
        this.now = start;
    }

    @Override
    public double getAsDouble() {
        return now;
    }

    void advance(double seconds) {
        now += seconds;
    }
}
