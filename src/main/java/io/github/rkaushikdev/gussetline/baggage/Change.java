package io.github.rkaushikdev.gussetline.baggage;

import io.github.rkaushikdev.gussetline.offers.Baggage;
import java.util.Objects;

/**
 * One comparison of two allowances, and what it is worth if it goes the wrong way.
 *
 * @param was the allowance on the fare held
 * @param now the allowance on the fare offered instead
 * @param verdict what the change does to the passenger
 * @param lostPieces how many checked pieces the passenger loses, on a {@link Verdict#WORSE} in pieces
 * @param lostKilos how many kilos the passenger loses, on a {@link Verdict#WORSE} in weight
 */
public record Change(Baggage was, Baggage now, Verdict verdict, int lostPieces, int lostKilos) {

    public Change {
        Objects.requireNonNull(was, "was");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(verdict, "verdict");
    }

    /** A comparison that loses nothing. */
    public Change(Baggage was, Baggage now, Verdict verdict) {
        this(was, now, verdict, 0, 0);
    }

    /** Whether the desk has to say something about it before quoting. */
    public boolean worthSaying() {
        return verdict.costsThePassenger();
    }

    /** The change in the words the desk reads: {@code 1 fewer checked pieces}. */
    public String why() {
        return switch (verdict) {
            case UNKNOWN -> "one of the fares does not state an allowance";
            case NOT_COMPARABLE -> was + " and " + now + " are not the same kind of allowance";
            case WORSE -> lostPieces != 0 ? lostPieces + " fewer checked pieces" : lostKilos + " kilos less";
            case BETTER -> "a larger allowance";
            case SAME -> "the same allowance";
        };
    }

    /** Both allowances and why: {@code 2PC to 1PC: 1 fewer checked pieces}. */
    @Override
    public String toString() {
        return was + " to " + now + ": " + why();
    }
}
