package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.util.Text;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Fares, grouped by the load they came in on. */
public final class TariffRepository {

    private static final String IN_SALE = " and f.effective <= :on and f.discontinue >= :on";

    private final EntityManager session;

    /** The tariff, read and written through {@code session}. */
    public TariffRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * Write down that a tariff file has been taken in, before its fares are, so it has an id to file
     * them under.
     *
     * @param loadedAt when the file was taken in, in UTC
     * @param effective the first day the file's fares apply
     */
    public LoadRow openLoad(
            String carrier, String filename, String digest, LocalDateTime loadedAt, LocalDate effective) {
        LoadRow load = new LoadRow();
        load.setCarrier(carrier.toUpperCase(Locale.ROOT));
        load.setFilename(filename);
        load.setDigest(digest);
        load.setLoadedAt(loadedAt);
        load.setEffectiveFrom(effective);
        session.persist(load);
        session.flush();
        return load;
    }

    /** File fares under a load, which then counts every fare it holds. Returns that count. */
    public int addFares(LoadRow load, List<Fare> fares) {
        for (Fare fare : fares) {
            load.addFare(Mappers.fareToRow(fare, load.getId()));
        }
        load.setRowCount(load.getFares().size());
        return load.getRowCount();
    }

    /** Every fare filed in one market, cheapest first, whatever its sale window. */
    public List<Fare> search(String carrier, String origin, String destination) {
        return search(carrier, origin, destination, Optional.empty());
    }

    /** Every fare filed in one market and on sale on a day, cheapest first. */
    public List<Fare> search(String carrier, String origin, String destination, LocalDate on) {
        return search(carrier, origin, destination, Optional.of(on));
    }

    /** Every fare filed in one market, on sale on the day when one is given, cheapest first. */
    public List<Fare> search(String carrier, String origin, String destination, Optional<LocalDate> on) {
        TypedQuery<FareRow> query = session.createQuery(
                "select f from FareRow f where f.carrier = :carrier and f.origin = :origin"
                        + " and f.destination = :destination"
                        + (on.isPresent() ? IN_SALE : "")
                        + " order by f.amount",
                FareRow.class);
        query.setParameter("carrier", carrier.toUpperCase(Locale.ROOT));
        query.setParameter("origin", origin.toUpperCase(Locale.ROOT));
        query.setParameter("destination", destination.toUpperCase(Locale.ROOT));
        on.ifPresent(day -> query.setParameter("on", day));
        return query.getResultList().stream().map(Mappers::rowToFare).toList();
    }

    /** {@link #byBasis(String, String, Optional)} whatever the sale window. */
    public Optional<Fare> byBasis(String carrier, String fareBasis) {
        return byBasis(carrier, fareBasis, Optional.empty());
    }

    /** {@link #byBasis(String, String, Optional)} on sale on a day. */
    public Optional<Fare> byBasis(String carrier, String fareBasis, LocalDate on) {
        return byBasis(carrier, fareBasis, Optional.of(on));
    }

    /**
     * One fare by its basis, whatever market it is filed in, the latest filed first.
     *
     * <p>A ticket carries fare bases and never rule numbers, so getting from a ticket to its rule has
     * to come through here. The market is not part of the question: a fare basis is a carrier's own
     * name for a fare and the same one is not filed twice with different rules.
     *
     * @param on the day the fare has to be on sale, when one is given
     */
    public Optional<Fare> byBasis(String carrier, String fareBasis, Optional<LocalDate> on) {
        TypedQuery<FareRow> query = session.createQuery(
                "select f from FareRow f where f.carrier = :carrier and f.fareBasis = :basis"
                        + (on.isPresent() ? IN_SALE : "")
                        + " order by f.effective desc",
                FareRow.class);
        query.setParameter("carrier", carrier.toUpperCase(Locale.ROOT));
        query.setParameter("basis", Text.code(fareBasis));
        on.ifPresent(day -> query.setParameter("on", day));
        return Rows.first(query).map(Mappers::rowToFare);
    }

    /** Every load one carrier's files came in on, newest first. */
    public List<LoadRow> loadsFor(String carrier) {
        return session.createQuery(
                        "select l from LoadRow l where l.carrier = ?1 order by l.loadedAt desc", LoadRow.class)
                .setParameter(1, carrier.toUpperCase(Locale.ROOT))
                .getResultList();
    }

    /**
     * Every fare one load filed, rows and not fares, in the order of their fare bases.
     *
     * <p>Rows, because the caller comparing two filings wants the fields the tariff filed and not the
     * priceable object built out of them: a change of rule number does not survive the trip through
     * {@link Fare}.
     */
    public List<FareRow> faresIn(LoadRow load) {
        return session.createQuery("select f from FareRow f where f.loadId = ?1 order by f.fareBasis", FareRow.class)
                .setParameter(1, load.getId())
                .getResultList();
    }

    /** Mark a load as replaced. Its fares stay where they are. */
    public void retire(LoadRow load) {
        load.setRetired(true);
    }

    /** How many fares are filed, under every load. */
    public long countFares() {
        return Rows.count(session, FareRow.class);
    }
}
