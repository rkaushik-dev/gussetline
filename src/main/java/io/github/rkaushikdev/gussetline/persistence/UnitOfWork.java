package io.github.rkaushikdev.gussetline.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.Objects;

/**
 * The unit of work, which owns the transaction so nothing else has to: one session, one transaction,
 * one exit, and every repository sharing them.
 *
 * <p>A caller opens one, uses the repositories hanging off it, and either commits or does not, and
 * closing it rolls back whatever nobody committed. Rolling back on the way out rather than committing
 * is the choice worth stating: a run that throws halfway should leave the database as it was, and the
 * other default has written half a collection more than once.
 *
 * <pre>{@code
 * try (UnitOfWork work = database.unit()) {
 *     work.airports().save(Geography.airport("FRA"));
 *     work.commit();
 * }
 * }</pre>
 *
 * <p>The repositories share the one entity manager, so what one of them writes the next can read
 * before anything is committed. A commit begins the next transaction straight away and leaves every
 * row the session holds as it was, so work can carry on in the same unit with the rows it already
 * has. A rollback forgets every row the session held, and begins again too.
 */
public final class UnitOfWork implements AutoCloseable {

    private final EntityManager session;
    private final AirportRepository airports;
    private final CarrierRepository carriers;
    private final TariffRepository tariff;
    private final ObservationRepository observations;
    private final WatchRepository watches;
    private final QuoteRepository quotes;
    private final RunRepository runs;
    private final CombinabilityRepository combinability;
    private final RuleRepository rules;
    private boolean committed;

    /**
     * A unit of work over a session nobody else uses, its transaction begun here. Closing the unit
     * closes the session.
     */
    public UnitOfWork(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
        this.airports = new AirportRepository(session);
        this.carriers = new CarrierRepository(session);
        this.tariff = new TariffRepository(session);
        this.observations = new ObservationRepository(session);
        this.watches = new WatchRepository(session);
        this.quotes = new QuoteRepository(session);
        this.runs = new RunRepository(session);
        this.combinability = new CombinabilityRepository(session);
        this.rules = new RuleRepository(session);
        session.getTransaction().begin();
    }

    /** The entity manager every repository of this unit shares. */
    public EntityManager session() {
        return session;
    }

    public AirportRepository airports() {
        return airports;
    }

    public CarrierRepository carriers() {
        return carriers;
    }

    public TariffRepository tariff() {
        return tariff;
    }

    public ObservationRepository observations() {
        return observations;
    }

    public WatchRepository watches() {
        return watches;
    }

    public QuoteRepository quotes() {
        return quotes;
    }

    public RunRepository runs() {
        return runs;
    }

    public CombinabilityRepository combinability() {
        return combinability;
    }

    public RuleRepository rules() {
        return rules;
    }

    /** Whether anything has been committed through this unit. */
    public boolean committed() {
        return committed;
    }

    /** Make the work so far stick, and begin the next transaction. */
    public void commit() {
        EntityTransaction transaction = session.getTransaction();
        transaction.commit();
        committed = true;
        transaction.begin();
    }

    /** Undo the work since the last commit, forget every row the session held, and begin again. */
    public void rollback() {
        EntityTransaction transaction = session.getTransaction();
        if (transaction.isActive()) {
            transaction.rollback();
        }
        session.clear();
        transaction.begin();
    }

    /** Write what the session holds without committing it, so a query sees it. */
    public void flush() {
        session.flush();
    }

    /** Roll back whatever was not committed, and close the session. Closing twice does nothing more. */
    @Override
    public void close() {
        if (!session.isOpen()) {
            return;
        }
        try {
            EntityTransaction transaction = session.getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
        } finally {
            session.close();
        }
    }
}
