package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Optional;

/** What the desk should do about a held drop, in one word. */
public enum Verdict {
    REISSUE("reissue"),
    NOT_WORTH_IT("not_worth_it"),
    NOT_CHANGEABLE("not_changeable"),
    PRICED_HISTORICALLY("priced_historically"),
    RESIDUAL_FORFEITED("residual_forfeited"),
    NOTHING_OPEN("nothing_open");

    private final String value;

    Verdict(String value) {
        this.value = value;
    }

    /** The word the verdict is printed and counted under. */
    public String value() {
        return value;
    }

    /** Whether the desk should act on it. Only a reissue is. */
    public boolean isActionable() {
        return this == REISSUE;
    }

    /**
     * Why this verdict means the watch should stop looking, or empty when it should keep looking.
     *
     * <p>A fare that cannot be changed and one that reprices historically will never become actionable
     * however far the market falls, so the watch is spending a shopping request a week on nothing, and
     * a ticket with nothing open has nothing left to reprice. Not worth it is different: that is a
     * price, and prices move.
     */
    public Optional<Retired> retiresTheWatch() {
        return switch (this) {
            case NOT_CHANGEABLE -> Optional.of(Retired.NOT_CHANGEABLE);
            case PRICED_HISTORICALLY -> Optional.of(Retired.HISTORICAL);
            case NOTHING_OPEN -> Optional.of(Retired.TICKET_FINISHED);
            case REISSUE, NOT_WORTH_IT, RESIDUAL_FORFEITED -> Optional.empty();
        };
    }

    /**
     * The verdict printed under a word.
     *
     * @throws IllegalArgumentException when the word is no verdict
     */
    public static Verdict of(String value) {
        return Text.named(values(), Verdict::value, value, "Verdict");
    }
}
