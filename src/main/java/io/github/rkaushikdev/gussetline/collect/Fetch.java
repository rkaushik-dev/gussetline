package io.github.rkaushikdev.gussetline.collect;

/**
 * How a collector asks a supplier about a task.
 *
 * <p>Passed in rather than built in. That is what lets a whole day of collection run in a test in a
 * second, and it is also what lets the desk replay a day out of the archive against a fixed reader
 * without touching a supplier.
 */
@FunctionalInterface
public interface Fetch {

    /**
     * The supplier's answer, whatever its status.
     *
     * @throws io.github.rkaushikdev.gussetline.GussetlineError when no answer could be had at all; the
     *     run classifies it ({@link Registry#classify}) and carries on
     */
    Response fetch(Supplier supplier, Task task);
}
