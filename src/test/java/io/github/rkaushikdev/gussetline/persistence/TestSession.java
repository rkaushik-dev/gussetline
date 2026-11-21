package io.github.rkaushikdev.gussetline.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.Status;

/**
 * One application managed entity manager with a transaction always open, in the shape the row tests
 * use: add rows, commit, read them back.
 *
 * <p>{@link #commit()} commits, begins the next transaction straight away, and expires every row
 * the session holds: each is read again from the database, into the same object, collections and
 * all. So a row read back after a commit says what the columns hold, not what was set on it, and it
 * is still the object the test added and can go on changing. A row whose key the database generates
 * is written as soon as it is added, so a duplicate of one is refused by {@link #add}, where any
 * other row is refused at the commit.
 */
public final class TestSession implements AutoCloseable {

    private final EntityManager entityManager;

    TestSession(EntityManager entityManager) {
        this.entityManager = entityManager;
        entityManager.getTransaction().begin();
    }

    /** The entity manager underneath, for anything the shorthand here does not cover. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /** Add rows to the session, each with whatever hangs off it. */
    public void add(Object... rows) {
        for (Object row : rows) {
            entityManager.persist(row);
        }
    }

    /** Delete a row, and whatever hangs off it. */
    public void delete(Object row) {
        entityManager.remove(row);
    }

    /** Write what the session holds without committing it. */
    public void flush() {
        entityManager.flush();
    }

    /** Commit, begin the next transaction, and read every row the session holds again. */
    public void commit() {
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.commit();
        transaction.begin();
        expireAll();
    }

    /** Read every row the session holds again from the database, into the same objects. */
    public void expireAll() {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        for (Map.Entry<Object, EntityEntry> held :
                session.getPersistenceContextInternal().reentrantSafeEntityEntries()) {
            if (held.getValue().getStatus() == Status.MANAGED && entityManager.contains(held.getKey())) {
                entityManager.refresh(held.getKey());
            }
        }
    }

    /** The row with this key, if there is one. */
    public <T> Optional<T> get(Class<T> type, Object key) {
        return Optional.ofNullable(entityManager.find(type, key));
    }

    /** Every row of a type, in the order of their keys. */
    public <T> List<T> all(Class<T> type) {
        EntityType<T> entity = entityManager.getMetamodel().entity(type);
        String key = entity.getSingularAttributes().stream()
                .filter(SingularAttribute::isId)
                .map(SingularAttribute::getName)
                .findFirst()
                .orElseThrow();
        return select("select r from " + entity.getName() + " r order by r." + key, type);
    }

    /** The one row of a type, refusing when there is not exactly one. */
    public <T> T one(Class<T> type) {
        return one("select r from " + entityName(type) + " r", type);
    }

    /** The one row a query finds, refusing when it does not find exactly one. */
    public <T> T one(String query, Class<T> type, Object... parameters) {
        List<T> rows = select(query, type, parameters);
        if (rows.size() != 1) {
            throw new IllegalStateException("expected one " + type.getSimpleName() + ", found " + rows.size());
        }
        return rows.get(0);
    }

    /** The rows a query in the persistence query language finds. */
    public <T> List<T> select(String query, Class<T> type, Object... parameters) {
        TypedQuery<T> typed = entityManager.createQuery(query, type);
        for (int position = 0; position < parameters.length; position++) {
            typed.setParameter(position + 1, parameters[position]);
        }
        return typed.getResultList();
    }

    /** How many rows of a type there are. */
    public long count(Class<?> type) {
        return entityManager
                .createQuery("select count(r) from " + entityName(type) + " r", Long.class)
                .getSingleResult();
    }

    /** Roll back whatever was not committed, and close. */
    @Override
    public void close() {
        EntityTransaction transaction = entityManager.getTransaction();
        if (transaction.isActive()) {
            transaction.rollback();
        }
        entityManager.close();
    }

    private String entityName(Class<?> type) {
        return entityManager.getMetamodel().entity(type).getName();
    }
}
