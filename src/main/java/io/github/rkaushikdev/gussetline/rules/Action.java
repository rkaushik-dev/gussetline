package io.github.rkaushikdev.gussetline.rules;

/** What a passenger does to a ticket that a category 16 fee is filed for. */
public enum Action {
    CHANGE("change"),
    CANCEL("cancel"),
    NO_SHOW("no_show");

    private final String value;

    Action(String value) {
        this.value = value;
    }

    /** The name the action is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The action stored under a name.
     *
     * @throws IllegalArgumentException when the name is no action
     */
    public static Action of(String value) {
        for (Action action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid Action");
    }
}
