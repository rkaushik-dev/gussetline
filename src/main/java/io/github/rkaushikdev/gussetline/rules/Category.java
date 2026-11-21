package io.github.rkaushikdev.gussetline.rules;

/**
 * The rule categories, under the tariff's own numbering.
 *
 * <p>The numbers are stable and they are what every filing, every printed rule and every stored
 * provision row carries. Do not renumber these.
 */
public enum Category {
    ELIGIBILITY(1),
    DAY_TIME(2),
    SEASONALITY(3),
    FLIGHT_APPLICATION(4),
    ADVANCE_RESERVATION(5),
    MINIMUM_STAY(6),
    MAXIMUM_STAY(7),
    STOPOVERS(8),
    TRANSFERS(9),
    COMBINABILITY(10),
    BLACKOUTS(11),
    SURCHARGES(12),
    ACCOMPANIED_TRAVEL(13),
    TRAVEL_RESTRICTIONS(14),
    SALES_RESTRICTIONS(15),
    PENALTIES(16),
    TICKET_ENDORSEMENT(18),
    CHILD_DISCOUNTS(19),
    VOLUNTARY_CHANGES(31),
    VOLUNTARY_REFUNDS(33);

    private final int value;

    Category(int value) {
        this.value = value;
    }

    /** The tariff's number for the category. */
    public int value() {
        return value;
    }

    /** The category the way the desk writes it: {@code cat 6}. */
    public String label() {
        return "cat " + value;
    }
}
