package io.github.rkaushikdev.gussetline.taxes;

/**
 * Which journeys a filed tax is owed on at all, read before anything else about it.
 *
 * <p>The United States charges a percentage of the fare on a ticket that stays inside the country
 * and a flat amount at each border on one that leaves it, under the same code. Filing both and
 * charging both is the double count every multi country audit finds.
 */
public enum Journeys {
    /** Every journey that reaches the rule. */
    ANY,
    /** Only a journey that never crosses a border. */
    DOMESTIC,
    /** Only a journey with at least one element that crosses a border. */
    INTERNATIONAL;

    /** Whether a journey, international or not, is one the rule is owed on. */
    public boolean reads(boolean international) {
        return switch (this) {
            case ANY -> true;
            case DOMESTIC -> !international;
            case INTERNATIONAL -> international;
        };
    }
}
