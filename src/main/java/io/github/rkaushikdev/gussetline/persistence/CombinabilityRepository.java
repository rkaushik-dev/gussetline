package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.persistence.models.CombinabilityRow;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 10, which is the one lookup the price ladder cannot skip.
 *
 * <p>A fare with no record here combines with nothing, so an empty table turns every round trip into
 * a refusal. That is the right default and it is also the reason this is read for a whole carrier at
 * once rather than one fare basis at a time: a component that refuses is exactly the one somebody is
 * about to ask about, and answering with a second round of queries under them is how a price screen
 * takes four seconds.
 */
public final class CombinabilityRepository {

    private final EntityManager session;

    /** The combinability records, read and written through {@code session}. */
    public CombinabilityRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** {@link #save(Combinability, Optional)} for a record filed under no load. */
    public CombinabilityRow save(Combinability record) {
        return save(record, Optional.empty());
    }

    /** {@link #save(Combinability, Optional)} for a record filed under a load. */
    public CombinabilityRow save(Combinability record, int loadId) {
        return save(record, Optional.of(loadId));
    }

    /**
     * Keep a record. A carrier and fare basis already held is not a second row: the row held takes
     * the record's eight flags, keeps the load it came in on, and comes back.
     *
     * @param loadId the load a new record is filed under, when there is one
     */
    public CombinabilityRow save(Combinability record, Optional<Integer> loadId) {
        Optional<CombinabilityRow> existing = find(record.carrier(), record.fareBasis());
        if (existing.isEmpty()) {
            CombinabilityRow row = Mappers.combinabilityToRow(record, loadId);
            session.persist(row);
            return row;
        }
        CombinabilityRow row = existing.get();
        row.setRoundTrip(record.roundTrip());
        row.setCircleTrip(record.circleTrip());
        row.setOpenJaw(record.openJaw());
        row.setDoubleOpenJaw(record.doubleOpenJaw());
        row.setEndOnEnd(record.endOnEnd());
        row.setSameCarrierOnly(record.sameCarrierOnly());
        row.setSameRuleOnly(record.sameRuleOnly());
        row.setSideTrip(record.sideTrip());
        return row;
    }

    /** Keep every record, filed under no load. Returns how many were given. */
    public int saveAll(List<Combinability> records) {
        return saveAll(records, Optional.empty());
    }

    /** Keep every record, filed under a load when one is given. Returns how many were given. */
    public int saveAll(List<Combinability> records, Optional<Integer> loadId) {
        for (Combinability record : records) {
            save(record, loadId);
        }
        return records.size();
    }

    /** The record held for a carrier and fare basis, either in either case, or nothing. */
    public Optional<Combinability> get(String carrier, String fareBasis) {
        return find(carrier.toUpperCase(Locale.ROOT), fareBasis.toUpperCase(Locale.ROOT))
                .map(Mappers::rowToCombinability);
    }

    /**
     * Everything filed for a set of carriers, as the store the engine reads, in one query. No carriers
     * at all is an empty store, and asks the database nothing.
     */
    public CombinabilityStore storeFor(List<String> carriers) {
        CombinabilityStore store = new CombinabilityStore();
        if (carriers.isEmpty()) {
            return store;
        }
        List<String> codes =
                carriers.stream().map(code -> code.toUpperCase(Locale.ROOT)).toList();
        session.createQuery("select c from CombinabilityRow c where c.carrier in ?1", CombinabilityRow.class)
                .setParameter(1, codes)
                .getResultList()
                .forEach(row -> store.add(Mappers.rowToCombinability(row)));
        return store;
    }

    /**
     * Drop a withdrawn record. Says whether it was there.
     *
     * <p>Deleted rather than blanked. A row with every flag off looks exactly like a fare the carrier
     * filed as uncombinable, and the two need different answers when somebody asks why a round trip
     * refused.
     */
    public boolean delete(String carrier, String fareBasis) {
        Optional<CombinabilityRow> row = find(carrier.toUpperCase(Locale.ROOT), fareBasis.toUpperCase(Locale.ROOT));
        row.ifPresent(session::remove);
        return row.isPresent();
    }

    /** How many records are held, over every carrier. */
    public long count() {
        return Rows.count(session, CombinabilityRow.class);
    }

    private Optional<CombinabilityRow> find(String carrier, String fareBasis) {
        return Rows.oneOrNone(session.createQuery(
                        "select c from CombinabilityRow c where c.carrier = ?1 and c.fareBasis = ?2",
                        CombinabilityRow.class)
                .setParameter(1, carrier)
                .setParameter(2, fareBasis));
    }
}
