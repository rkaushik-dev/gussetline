package io.github.rkaushikdev.gussetline.load;

/**
 * What applying a fare filing file did to the tariff store.
 *
 * @param added fares put in, a change for a fare the store did not hold included
 * @param changed fares that replaced the one they amend
 * @param deleted fares taken out
 * @param unknownDeletes deletes for fares the store did not hold, which are ignored
 * @param changedAsAdds changes for fares the store did not hold, which went in as adds and are
 *     counted in {@code added} as well
 */
public record FaresApplied(int added, int changed, int deleted, int unknownDeletes, int changedAsAdds) {

    /** How many fares the file put in, replaced or took out. */
    public int touched() {
        return added + changed + deleted;
    }

    /** {@code 1 added, 0 changed, 0 deleted, 1 deletes for fares we do not hold}. */
    @Override
    public String toString() {
        return added + " added, " + changed + " changed, " + deleted + " deleted, " + unknownDeletes
                + " deletes for fares we do not hold";
    }
}
