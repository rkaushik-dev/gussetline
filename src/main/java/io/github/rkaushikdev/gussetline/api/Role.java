package io.github.rkaushikdev.gussetline.api;

/**
 * What a key may do. Ordered, and the order is meaningful.
 *
 * <p>Three roles and they are not a hierarchy by accident. A reader can look at observations and
 * prices. An agent can additionally settle a recommendation, which is the only thing that moves money.
 * An operator can additionally see the supplier screen, which carries the quota and the cost per call
 * and is commercially sensitive in a way the fares are not.
 */
public enum Role {
    READER("reader", 1),
    AGENT("agent", 2),
    OPERATOR("operator", 3);

    private final String value;
    private final int rank;

    Role(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    /** The name a key is issued and configured under: {@code reader}, {@code agent}, {@code operator}. */
    public String value() {
        return value;
    }

    /** Where the role sits: a reader ranks lowest, an operator highest. */
    public int rank() {
        return rank;
    }

    /** Whether this role is at least the one being asked for. */
    public boolean covers(Role wanted) {
        return rank >= wanted.rank;
    }

    /**
     * The role a name stands for.
     *
     * @throws IllegalArgumentException when the name is no role
     */
    public static Role of(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Role");
    }
}
