package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.geography.Airport;
import io.github.rkaushikdev.gussetline.persistence.models.AirportRow;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The airports the desk holds, by code and by city. */
public final class AirportRepository {

    private final EntityManager session;

    /** The airports, read and written through {@code session}. */
    public AirportRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Keep an airport. One already held is left as it is, and its row comes back. */
    public AirportRow save(Airport airport) {
        AirportRow row = session.find(AirportRow.class, airport.code());
        if (row == null) {
            row = Mappers.airportToRow(airport);
            session.persist(row);
        }
        return row;
    }

    /**
     * One airport by its code, in either case.
     *
     * @throws NotFoundError naming the {@code code} when no such airport is held
     */
    public Airport get(String code) {
        AirportRow row = session.find(AirportRow.class, code.toUpperCase(Locale.ROOT));
        if (row == null) {
            throw new NotFoundError("no such airport", Details.of("code", code));
        }
        return Mappers.rowToAirport(row);
    }

    /** Every airport held in one city, in the order of their codes. */
    public List<Airport> inCity(String city) {
        return session
                .createQuery("select a from AirportRow a where a.city = ?1 order by a.code", AirportRow.class)
                .setParameter(1, city.toUpperCase(Locale.ROOT))
                .getResultList()
                .stream()
                .map(Mappers::rowToAirport)
                .toList();
    }

    /** How many airports are held. */
    public long count() {
        return Rows.count(session, AirportRow.class);
    }
}
