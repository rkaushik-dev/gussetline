package io.github.rkaushikdev.gussetline.surcharges;

/** How many times a filed surcharge is charged on one fare component. */
public enum Scope {
    /** Once for the whole component, however many sectors it has. */
    COMPONENT("component"),
    /** Once for each sector that matches the filing. */
    SECTOR("sector");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    /** The name the scope is filed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The scope filed under a name.
     *
     * @throws IllegalArgumentException when the name is no scope
     */
    public static Scope of(String value) {
        for (Scope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Scope");
    }
}
