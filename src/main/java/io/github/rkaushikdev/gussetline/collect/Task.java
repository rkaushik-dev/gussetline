package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * One poll to make, and why it is worth making.
 *
 * @param supplier the name of the supplier to ask
 * @param market the market to ask about
 * @param departure the departure date to ask about
 * @param daysOut how far off the departure was when the poll was planned
 * @param priority where it came in the plan, nought first
 * @param requestedAt when somebody asked for it, if it was asked for rather than planned
 */
public record Task(
        String supplier,
        Market market,
        LocalDate departure,
        int daysOut,
        int priority,
        Optional<LocalDateTime> requestedAt) {

    public Task {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** A planned poll at the head of its plan. */
    public Task(String supplier, Market market, LocalDate departure, int daysOut) {
        this(supplier, market, departure, daysOut, 0);
    }

    /** A planned poll at a place in its plan. */
    public Task(String supplier, Market market, LocalDate departure, int daysOut, int priority) {
        this(supplier, market, departure, daysOut, priority, Optional.empty());
    }

    /** The supplier, the market and the departure: {@code gds FRA-NYC 2025-06-04}. */
    @Override
    public String toString() {
        return supplier + " " + market + " " + departure;
    }
}
