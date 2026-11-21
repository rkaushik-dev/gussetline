package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.persistence.models.QuoteRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** The calendar's quotes: one price per market, departure and day it was asked. */
public final class QuoteRepository {

    private final EntityManager session;

    /** The quotes, read and written through {@code session}. */
    public QuoteRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * Keep a quote. A quote for the same market and departure asked on the same day is not a second
     * row: the one already held takes the new amount and the new sold out flag, and comes back.
     */
    public QuoteRow save(QuoteRow row) {
        Optional<QuoteRow> existing = Rows.oneOrNone(session.createQuery(
                        "select q from QuoteRow q where q.origin = ?1 and q.destination = ?2"
                                + " and q.departure = ?3 and q.observedOn = ?4",
                        QuoteRow.class)
                .setParameter(1, row.getOrigin())
                .setParameter(2, row.getDestination())
                .setParameter(3, row.getDeparture())
                .setParameter(4, row.getObservedOn()));
        if (existing.isEmpty()) {
            session.persist(row);
            return row;
        }
        QuoteRow held = existing.get();
        held.setAmount(row.getAmount());
        held.setSoldOut(row.isSoldOut());
        return held;
    }

    /**
     * The cheapest quote for a market departing between two days, both included. A sold out day is
     * never the cheapest, whatever it says it costs.
     */
    public Optional<QuoteRow> cheapestBetween(String origin, String destination, LocalDate first, LocalDate last) {
        return Rows.first(session.createQuery(
                        "select q from QuoteRow q where q.origin = ?1 and q.destination = ?2"
                                + " and q.departure >= ?3 and q.departure <= ?4 and q.soldOut = false"
                                + " order by q.amount",
                        QuoteRow.class)
                .setParameter(1, origin.toUpperCase(Locale.ROOT))
                .setParameter(2, destination.toUpperCase(Locale.ROOT))
                .setParameter(3, first)
                .setParameter(4, last));
    }
}
