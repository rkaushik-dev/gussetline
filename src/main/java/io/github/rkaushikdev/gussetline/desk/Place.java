package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One open recommendation and why it is where it is in the queue.
 *
 * @param recommendation the recommendation to work
 * @param score what it is worth chasing right now, to the cent
 * @param daysLeft days until it expires
 * @param daysOut days until the departure
 * @param fareMoved whether the series has been seen dearer since it was raised
 */
public record Place(Recommendation recommendation, BigDecimal score, int daysLeft, int daysOut, boolean fareMoved) {

    public Place {
        Objects.requireNonNull(recommendation, "recommendation");
        Objects.requireNonNull(score, "score");
    }

    /** What the recommendation is worth if it is taken. */
    public Money worth() {
        return recommendation.benefit();
    }

    /** Whether this one goes today whatever else is on the list: a day or less left, or three days out. */
    public boolean isUrgent() {
        return daysLeft <= 1 || daysOut <= 3;
    }

    /** What the fare being recommended includes, or empty when nothing was said. */
    public String baggage() {
        return recommendation.baggage();
    }

    /**
     * Why it is where it is, in a phrase: {@code expires today, departs within three days}, or {@code
     * no hurry}.
     */
    public String why() {
        List<String> parts = new ArrayList<>();
        if (daysLeft <= 1) {
            parts.add("expires today");
        } else if (daysLeft <= 2) {
            parts.add(daysLeft + " days left");
        }
        if (daysOut <= 3) {
            parts.add("departs within three days");
        }
        if (fareMoved) {
            parts.add("the market has been dearer since");
        }
        if (!baggage().isEmpty()) {
            // Said on the queue rather than left for the phone call, because
            // an agent who finds out about a smaller allowance halfway
            // through the call has already quoted the saving.
            parts.add("includes " + baggage());
        }
        return parts.isEmpty() ? "no hurry" : String.join(", ", parts);
    }

    /** {@code 220-1111111111 90.00 EUR score 90.00 (no hurry)}. */
    @Override
    public String toString() {
        return recommendation.ticketNumber() + " " + worth() + " score " + score + " (" + why() + ")";
    }
}
