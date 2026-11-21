package io.github.rkaushikdev.gussetline.load;

/**
 * What applying a category 10 file did to the combinability store.
 *
 * @param added records for a carrier and fare basis the store did not hold
 * @param replaced records that replaced the one held for their carrier and fare basis
 * @param deleted records taken out
 * @param unknownDeletes withdrawals of records the store did not hold, which are ignored
 * @param combineWithNothing records filed that permit no round trip, circle trip, open jaw or end on
 *     end combination, which the operator should hear about
 */
public record CombinabilityApplied(int added, int replaced, int deleted, int unknownDeletes, int combineWithNothing) {

    /** {@code 1 added, 0 replaced, 0 deleted, 0 that combine with nothing}. */
    @Override
    public String toString() {
        return added + " added, " + replaced + " replaced, " + deleted + " deleted, " + combineWithNothing
                + " that combine with nothing";
    }
}
