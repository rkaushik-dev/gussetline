package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** The desk's watches, read by what is still worth checking. */
public final class WatchRepository {

    /** What a watch retired because its departure went is said to have been retired for. */
    public static final String FLOWN = "departure has gone";

    private final EntityManager session;

    /** The watches, read and written through {@code session}. */
    public WatchRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Every watch still worth checking on a day: live, departing that day or later, closest first. */
    public List<WatchRow> live(LocalDate on) {
        return session.createQuery(
                        "select w from WatchRow w where w.active = true and w.departure >= ?1 order by w.departure",
                        WatchRow.class)
                .setParameter(1, on)
                .getResultList();
    }

    /** Every watch on one ticket. */
    public List<WatchRow> forTicket(String number) {
        return session.createQuery("select w from WatchRow w join w.ticket t where t.number = ?1", WatchRow.class)
                .setParameter(1, number)
                .getResultList();
    }

    /** Close the live watches whose departures are before a day, saying why. Returns how many. */
    public int retireFlown(LocalDate on) {
        List<WatchRow> rows = session.createQuery(
                        "select w from WatchRow w where w.active = true and w.departure < ?1", WatchRow.class)
                .setParameter(1, on)
                .getResultList();
        for (WatchRow row : rows) {
            row.setActive(false);
            row.setRetiredReason(FLOWN);
        }
        return rows.size();
    }
}
