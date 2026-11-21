package io.github.rkaushikdev.gussetline.collect;

/**
 * What to do with what a task's payload gave up.
 *
 * <p>A run that reads nine hundred offers and keeps none of them leaves the audit and the night with
 * nothing to read, so the run hands every harvest on. A plan or a dry run wants the counting and not
 * the writing, which is why a run may have no sink.
 */
@FunctionalInterface
public interface Sink {

    /**
     * Keep one answered task's harvest. Called once for each task the supplier answered and the
     * reader read, inside the run, after the schedule has learnt the price. Whatever it throws comes
     * out of the run.
     */
    void keep(Task task, Harvest harvest);
}
