package io.github.rkaushikdev.gussetline.load;

/**
 * What applying a rule file did to the rule store.
 *
 * @param loaded rules put in, the ones that replaced a rule held included
 * @param replaced rules that replaced the one held under their carrier, number and tariff, whole
 * @param deleted rules taken out
 * @param unknownDeletes withdrawals of rules the store did not hold, which are ignored
 * @param emptyRules rules filed with no conditions at all, which an unrestricted fare points at
 */
public record RulesApplied(int loaded, int replaced, int deleted, int unknownDeletes, int emptyRules) {

    /** {@code 1 rules loaded, 0 of them replacing one, 0 deleted, 0 with no conditions}. */
    @Override
    public String toString() {
        return loaded + " rules loaded, " + replaced + " of them replacing one, " + deleted + " deleted, " + emptyRules
                + " with no conditions";
    }
}
