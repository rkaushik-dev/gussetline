package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.util.Text;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the collectors write, and the only place with an upsert in it.
 *
 * <p>Saving an offer that has been seen before is not an insert, it is a stamp move on the row that
 * is already there, and getting that wrong is the difference between a table that grows by a
 * million rows a year and one that grows by nine.
 *
 * <p>Whatever reads offers back reads their sectors and taxes with them, a batch of rows at a time,
 * so an offer handed out is whole and reading a day of them is a handful of queries and not one per
 * row.
 */
public final class ObservationRepository {

    private final EntityManager session;

    /** The observations, read and written through {@code session}. */
    public ObservationRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * What saving one offer did: the row it is kept in, and whether that row had to be created.
     *
     * <p>The caller is told rather than left to work it out from the row, because whether a row is new
     * is not something the row itself says.
     *
     * @param row the row holding the offer
     * @param created whether the offer had not been seen in its series before
     */
    public record Upsert(ObservationRow row, boolean created) {

        public Upsert {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * What saving a batch of offers did.
     *
     * @param fresh how many had not been seen before
     * @param repeats how many had, and moved a stamp
     */
    public record Saved(int fresh, int repeats) {}

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Insert a new offer, or move the stamp on one seen before. Returns the row either way. */
    public ObservationRow save(Offer offer) {
        return upsert(offer).row();
    }

    /**
     * Insert a new offer, or count one more sighting of one seen before in its series.
     *
     * <p>A repeat keeps its row: the sighting moves its first or last stamp outwards, never inwards,
     * and the seat count is what the latest sighting said.
     */
    public Upsert upsert(Offer offer) {
        ObservationRow row = Mappers.offerToRow(offer);
        Optional<ObservationRow> existing = Rows.oneOrNone(session.createQuery(
                        "select o from ObservationRow o where o.seriesKey = ?1 and o.fingerprint = ?2",
                        ObservationRow.class)
                .setParameter(1, row.getSeriesKey())
                .setParameter(2, row.getFingerprint()));
        if (existing.isEmpty()) {
            row.setSightings(1);
            session.persist(row);
            return new Upsert(row, true);
        }
        ObservationRow seen = existing.get();
        LocalDateTime when = offer.observedAt();
        if (when.isAfter(seen.getLastSeen())) {
            seen.setLastSeen(when);
        }
        if (when.isBefore(seen.getFirstSeen())) {
            seen.setFirstSeen(when);
        }
        seen.setSightings(seen.getSightings() + 1);
        seen.setSeats(offer.seats().orElse(null));
        return new Upsert(seen, false);
    }

    /** Save every offer in turn. Returns how many were new and how many were repeats. */
    public Saved saveAll(List<Offer> offers) {
        int fresh = 0;
        int repeats = 0;
        for (Offer offer : offers) {
            if (upsert(offer).created()) {
                fresh++;
            } else {
                repeats++;
            }
        }
        return new Saved(fresh, repeats);
    }

    /** Every offer held in one series, cheapest first. */
    public List<Offer> series(String seriesKey) {
        return session
                .createQuery(
                        "select o from ObservationRow o where o.seriesKey = ?1 order by o.totalAmount",
                        ObservationRow.class)
                .setParameter(1, seriesKey)
                .getResultList()
                .stream()
                .map(Mappers::rowToOffer)
                .toList();
    }

    /** The cheapest offer held in one series, or nothing when the series has none. */
    public Optional<Offer> cheapestIn(String seriesKey) {
        return series(seriesKey).stream().findFirst();
    }

    /** Every row last seen at or after a moment, oldest sighting first. */
    public List<ObservationRow> seenSince(LocalDateTime when) {
        return session.createQuery(
                        "select o from ObservationRow o where o.lastSeen >= ?1 order by o.lastSeen",
                        ObservationRow.class)
                .setParameter(1, when)
                .getResultList();
    }

    /** {@link #offersSince(LocalDateTime, Optional, Optional)} from every source, as many as there are. */
    public List<Offer> offersSince(LocalDateTime when) {
        return offersSince(when, Optional.empty(), Optional.empty());
    }

    /**
     * Offers rather than rows, last seen at or after a moment, oldest sighting first.
     *
     * <p>{@link #seenSince} hands back rows, which is right for a count and wrong for anything that
     * reads them: an offer is only whole once its sectors and its taxes are loaded. The audit reads
     * through here.
     *
     * @param source only offers from this source, matched without regard to case or surrounding
     *     white space, when one is given
     * @param limit at most this many, when a limit is given
     */
    public List<Offer> offersSince(LocalDateTime when, Optional<String> source, Optional<Integer> limit) {
        TypedQuery<ObservationRow> query = session.createQuery(
                "select o from ObservationRow o where o.lastSeen >= :when"
                        + (source.isPresent() ? " and o.source = :source" : "")
                        + " order by o.lastSeen",
                ObservationRow.class);
        query.setParameter("when", when);
        source.ifPresent(name -> query.setParameter("source", Text.strip(name).toLowerCase(Locale.ROOT)));
        limit.ifPresent(query::setMaxResults);
        return query.getResultList().stream().map(Mappers::rowToOffer).toList();
    }

    /**
     * Drop the readings for departures that have gone, sectors and taxes with them. Returns how many.
     *
     * <p>Keyed on the departure and not on when the row was written. A row written in March about a
     * December departure is still worth reading in November, and one written yesterday about a
     * departure last week is not: nobody will ever ask what that fare was.
     */
    public int prune(LocalDate before) {
        List<ObservationRow> rows = session.createQuery(
                        "select o from ObservationRow o where o.departure < ?1", ObservationRow.class)
                .setParameter(1, before)
                .getResultList();
        rows.forEach(session::remove);
        return rows.size();
    }

    /** How many observations are held. */
    public long count() {
        return Rows.count(session, ObservationRow.class);
    }
}
