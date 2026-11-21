package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One reading of a series, which is all a watch ever sees.
 *
 * <p>The baggage is what the fare includes, which is not part of the price and changes what the price
 * is worth. It is carried rather than compared: the desk knows what a supplier is offering and does not
 * yet know what the ticket in hand allows, so an agent on the phone is the one who can tell.
 *
 * @param seenOn the day the reading was taken
 * @param amount the price read
 * @param fingerprint the fingerprint of the offer read; empty when there was none
 * @param source the supplier that answered; empty when not known
 * @param baggage the hold allowance the offer came with
 */
public record Observation(LocalDate seenOn, Money amount, String fingerprint, String source, Baggage baggage) {

    public Observation {
        Objects.requireNonNull(seenOn, "seenOn");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(baggage, "baggage");
    }

    /** A price seen on a day, from no offer in particular. */
    public Observation(LocalDate seenOn, Money amount) {
        this(seenOn, amount, "", "", new Baggage());
    }

    /** A price seen on a day, from an offer with a fingerprint. */
    public Observation(LocalDate seenOn, Money amount, String fingerprint) {
        this(seenOn, amount, fingerprint, "", new Baggage());
    }

    /** {@code 2025-06-20 790.00 EUR}, then the allowance when one was stated: {@code ... 1PC}. */
    @Override
    public String toString() {
        if (baggage.isStated()) {
            return seenOn + " " + amount + " " + baggage;
        }
        return seenOn + " " + amount;
    }
}
