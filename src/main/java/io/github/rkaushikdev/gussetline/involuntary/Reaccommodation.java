package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;
import java.util.Objects;

/**
 * What the carrier is offering, and what it may not offer.
 *
 * <p>A cancelled economy seat does not become a business one unless the carrier says so, and a
 * rerouting on another airline needs an agreement between the two.
 *
 * @param cause why the carrier is reaccommodating
 * @param sameCarrierOnly whether only the validating carrier's own flights may be offered
 * @param sameCabinOrBetter whether the cabin offered has to be at least the one booked
 * @param interlineAgreed the other carriers the validating carrier has an agreement with
 */
public record Reaccommodation(
        Cause cause, boolean sameCarrierOnly, boolean sameCabinOrBetter, List<String> interlineAgreed) {

    public Reaccommodation {
        Objects.requireNonNull(cause, "cause");
        interlineAgreed = List.copyOf(interlineAgreed);
    }

    /** The carrier's own flights only, in the same cabin or better. */
    public Reaccommodation(Cause cause) {
        this(cause, true, true, List.of());
    }

    /** A copy that may, or may not, go beyond the validating carrier's own flights. */
    public Reaccommodation withSameCarrierOnly(boolean only) {
        return new Reaccommodation(cause, only, sameCabinOrBetter, interlineAgreed);
    }

    /** A copy that does, or does not, hold the passenger to the cabin booked or better. */
    public Reaccommodation withSameCabinOrBetter(boolean held) {
        return new Reaccommodation(cause, sameCarrierOnly, held, interlineAgreed);
    }

    /** A copy with other agreed carriers. */
    public Reaccommodation withInterlineAgreed(List<String> carriers) {
        return new Reaccommodation(cause, sameCarrierOnly, sameCabinOrBetter, carriers);
    }

    /**
     * Whether this reaccommodation may be flown on a given carrier: always on the validating carrier,
     * and on another one only when not held to the same carrier and the two have an agreement. Codes
     * are compared ignoring case and surrounding white space.
     */
    public boolean permits(String carrier, String validating) {
        String wanted = Text.code(carrier);
        if (wanted.equals(Text.code(validating))) {
            return true;
        }
        if (sameCarrierOnly) {
            return false;
        }
        return interlineAgreed.stream().map(Text::code).anyMatch(wanted::equals);
    }

    /** Whether the cabin offered is one the carrier may reaccommodate into. */
    public boolean permitsCabin(Cabin was, Cabin now) {
        if (!sameCabinOrBetter) {
            return true;
        }
        return now.rank() >= was.rank();
    }

    /** {@code cancelled, same carrier} or {@code cancelled, interline agreed}. */
    @Override
    public String toString() {
        String where = sameCarrierOnly ? "same carrier" : "interline agreed";
        return cause.value() + ", " + where;
    }
}
