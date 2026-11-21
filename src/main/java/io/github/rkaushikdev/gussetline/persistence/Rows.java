package io.github.rkaushikdev.gussetline.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

/**
 * The few ways a query's rows are read, written once for the repositories and for anybody else who
 * reads through a unit's session.
 */
public final class Rows {

    private Rows() {}

    /**
     * The one row a query finds, or nothing when it finds none.
     *
     * @throws NonUniqueResultException when it finds more than one
     */
    public static <T> Optional<T> oneOrNone(TypedQuery<T> query) {
        List<T> found = query.getResultList();
        if (found.size() > 1) {
            throw new NonUniqueResultException("expected at most one row, found " + found.size());
        }
        return found.stream().findFirst();
    }

    /** The first row a query finds in its own order, or nothing when it finds none. */
    public static <T> Optional<T> first(TypedQuery<T> query) {
        return query.setMaxResults(1).getResultList().stream().findFirst();
    }

    /** How many rows a table holds, the rows this session has written and not committed included. */
    static long count(EntityManager session, Class<?> type) {
        String entity = session.getMetamodel().entity(type).getName();
        return session.createQuery("select count(r) from " + entity + " r", Long.class)
                .getSingleResult();
    }
}
