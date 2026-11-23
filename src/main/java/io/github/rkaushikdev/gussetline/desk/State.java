package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.util.Text;

/**
 * The states a recommendation moves through once the desk has told a customer about it.
 *
 * <p>It starts {@link #OPEN} and is settled exactly once: a person takes it or kills it, the clock
 * expires it, or a better fare on the same series supersedes it.
 */
public enum State {
    OPEN("open"),
    TAKEN("taken"),
    KILLED("killed"),
    EXPIRED("expired"),
    SUPERSEDED("superseded");

    private final String value;

    State(String value) {
        this.value = value;
    }

    /** The word the state is printed and stored under. */
    public String value() {
        return value;
    }

    /** Whether the recommendation has left the open state, for whatever reason. */
    public boolean isSettled() {
        return this != OPEN;
    }

    /** Whether this is the one state that saves money: somebody took it. */
    public boolean countsAsSaving() {
        return this == TAKEN;
    }

    /**
     * Whether a human settled this, which is what a take rate measures.
     *
     * <p>The clock and the collectors settle recommendations too, and counting those against the desk
     * means the take rate moves when nobody on the desk has done anything at all.
     */
    public boolean aPersonDecided() {
        return this == TAKEN || this == KILLED;
    }

    /**
     * The state stored under a word.
     *
     * @throws IllegalArgumentException when the word is no state
     */
    public static State of(String value) {
        return Text.named(values(), State::value, value, "State");
    }
}
