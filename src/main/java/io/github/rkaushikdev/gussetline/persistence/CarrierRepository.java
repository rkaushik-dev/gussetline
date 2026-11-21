package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.carriers.Carrier;
import io.github.rkaushikdev.gussetline.persistence.models.CarrierRow;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The carriers the desk holds. */
public final class CarrierRepository {

    private final EntityManager session;

    /** The carriers, read and written through {@code session}. */
    public CarrierRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Keep a carrier. One already held is left as it is, and its row comes back. */
    public CarrierRow save(Carrier carrier) {
        CarrierRow row = session.find(CarrierRow.class, carrier.code());
        if (row == null) {
            row = Mappers.carrierToRow(carrier);
            session.persist(row);
        }
        return row;
    }

    /**
     * One carrier by its designator, in either case.
     *
     * @throws NotFoundError naming the {@code code} when no such carrier is held
     */
    public Carrier get(String code) {
        CarrierRow row = session.find(CarrierRow.class, code.toUpperCase(Locale.ROOT));
        if (row == null) {
            throw new NotFoundError("no such carrier", Details.of("code", code));
        }
        return Mappers.rowToCarrier(row);
    }

    /** Every carrier held, in the order of their designators. */
    public List<Carrier> all() {
        return session
                .createQuery("select c from CarrierRow c order by c.code", CarrierRow.class)
                .getResultList()
                .stream()
                .map(Mappers::rowToCarrier)
                .toList();
    }
}
