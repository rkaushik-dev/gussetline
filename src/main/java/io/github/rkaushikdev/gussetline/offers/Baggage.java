package io.github.rkaushikdev.gussetline.offers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.Objects;
import java.util.Optional;

/**
 * What the fare includes in the hold. Pieces and weight are alternatives, not both: a supplier
 * states one, the other, or neither.
 *
 * @param pieces how many bags, when the allowance is stated in pieces
 * @param kilos how much weight, when the allowance is stated in kilograms
 */
public record Baggage(Optional<Integer> pieces, Optional<Integer> kilos) {

    /**
     * Checks that neither figure is negative.
     *
     * @throws ValidationError naming the {@code pieces} or the {@code kilos} when it is negative
     */
    public Baggage {
        Objects.requireNonNull(pieces, "pieces");
        Objects.requireNonNull(kilos, "kilos");
        if (pieces.isPresent() && pieces.get() < 0) {
            throw new ValidationError("a bag count is not negative", Details.of("pieces", pieces.get()));
        }
        if (kilos.isPresent() && kilos.get() < 0) {
            throw new ValidationError("an allowance is not negative", Details.of("kilos", kilos.get()));
        }
    }

    /** An allowance nobody stated. */
    public Baggage() {
        this(Optional.empty(), Optional.empty());
    }

    /** An allowance of a number of bags. */
    public static Baggage ofPieces(int count) {
        return new Baggage(Optional.of(count), Optional.empty());
    }

    /** An allowance of a weight in kilograms. */
    public static Baggage ofKilos(int weight) {
        return new Baggage(Optional.empty(), Optional.of(weight));
    }

    /** Whether a supplier said anything at all, nought pieces included. */
    public boolean isStated() {
        return pieces.isPresent() || kilos.isPresent();
    }

    /** The allowance as a ticket shows it: {@code 2PC}, {@code 23KG}, or {@code unstated}. */
    @Override
    public String toString() {
        if (pieces.isPresent()) {
            return pieces.get() + "PC";
        }
        if (kilos.isPresent()) {
            return kilos.get() + "KG";
        }
        return "unstated";
    }
}
