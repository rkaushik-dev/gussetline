package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.persistence.models.RunResultRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Collection runs, which are read far more often than they are written.
 *
 * <p>Every report that says anything about the collectors is built out of these rows and out of
 * nothing else, so a run is written whole or not at all: a half saved run is a hole in the
 * reliability figures and in the coverage figures at once, and neither report can tell that is what
 * it is looking at.
 *
 * <p>Reading pulls the results in with the runs, a batch of runs at a time. A reliability report over
 * a month touches thirty runs and a few thousand results, and loading those one run at a time turns
 * one query into three thousand.
 */
public final class RunRepository {

    /** How many runs a caller gets when it does not say. */
    public static final int DEFAULT_LIMIT = 30;

    private final EntityManager session;

    /** The runs, read and written through {@code session}. */
    public RunRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Write a whole run that has not said it finished, results and all. */
    public RunRow record(RunReport report) {
        return record(report, Optional.empty());
    }

    /** Write a whole run, finished at a time, results and all. */
    public RunRow record(RunReport report, LocalDateTime finished) {
        return record(report, Optional.of(finished));
    }

    /** Write a whole run, results and all, finished if it says when. */
    public RunRow record(RunReport report, Optional<LocalDateTime> finished) {
        RunRow row = Mappers.runToRow(report, finished);
        session.persist(row);
        session.flush();
        return row;
    }

    /**
     * Write down that a run has begun, before it has done anything.
     *
     * <p>Committed by the caller straight away, on purpose. A night run is hours long, and a row
     * written only at the end means a machine that reboots at three leaves no trace that the desk
     * polled anything at all. The morning check then reads a desk that has not collected since
     * yesterday and cannot tell that from one that tried and died.
     */
    public RunRow open(LocalDateTime started) {
        RunRow row = new RunRow();
        row.setStartedAt(started);
        session.persist(row);
        session.flush();
        return row;
    }

    /**
     * Fill in what a run turned out to be, and stamp it finished. Results already on the row are
     * replaced by the report's.
     *
     * <p>The results arrive here rather than as they happen. They are cheap to hold and expensive to
     * write one at a time, and a run that dies has already lost the thing worth keeping, which is the
     * offers.
     */
    public RunRow close(RunRow row, RunReport report, LocalDateTime finished) {
        row.setFinishedAt(finished);
        row.setTasks(report.results().size());
        row.setCalls(report.calls());
        row.setOffers(report.offers());
        row.setSkippedBreaker(report.skippedBreaker());
        row.setSkippedBudget(report.skippedBudget());
        row.setResults(report.results().stream().map(Mappers::resultToRow).toList());
        session.flush();
        return row;
    }

    /** {@link #unfinished(int)} for the {@link #DEFAULT_LIMIT} newest. */
    public List<RunRow> unfinished() {
        return unfinished(DEFAULT_LIMIT);
    }

    /**
     * Runs that started and never said they were done, newest first.
     *
     * <p>Rows rather than reports, because the reading anybody wants from one of these is when it
     * started, and a report built from a run with no results in it says nothing.
     */
    public List<RunRow> unfinished(int limit) {
        return session.createQuery(
                        "select r from RunRow r where r.finishedAt is null order by r.startedAt desc", RunRow.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * One run, rebuilt as the report it was.
     *
     * @throws NotFoundError naming the {@code run} when there is no such run
     */
    public RunReport get(int identifier) {
        return Mappers.rowToRun(row(identifier));
    }

    /**
     * The row itself, for the caller that is going to write to it.
     *
     * @throws NotFoundError naming the {@code run} when there is no such run
     */
    public RunRow row(int identifier) {
        RunRow row = session.find(RunRow.class, identifier);
        if (row == null) {
            throw new NotFoundError("no such run", Details.of("run", identifier));
        }
        return row;
    }

    /** {@link #recent(int)} for the {@link #DEFAULT_LIMIT} newest. */
    public List<RunReport> recent() {
        return recent(DEFAULT_LIMIT);
    }

    /** The newest runs first, which is the order every report wants. */
    public List<RunReport> recent(int limit) {
        return session
                .createQuery("select r from RunRow r order by r.startedAt desc", RunRow.class)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(Mappers::rowToRun)
                .toList();
    }

    /** Every run from a moment onwards, oldest first. */
    public List<RunReport> since(LocalDateTime first) {
        return session
                .createQuery("select r from RunRow r where r.startedAt >= ?1 order by r.startedAt", RunRow.class)
                .setParameter(1, first)
                .getResultList()
                .stream()
                .map(Mappers::rowToRun)
                .toList();
    }

    /** How many runs are held. */
    public long count() {
        return Rows.count(session, RunRow.class);
    }

    /** How many results are held, over every run. */
    public long countResults() {
        return Rows.count(session, RunResultRow.class);
    }

    /**
     * Drop the runs that started before a moment. Returns how many went.
     *
     * <p>Results go with them through the cascade rather than through a second delete, because a
     * result whose run has gone is unreadable: it has no start date on it and every reading here is
     * measured against one.
     */
    public int prune(LocalDateTime before) {
        List<RunRow> rows = session.createQuery("select r from RunRow r where r.startedAt < ?1", RunRow.class)
                .setParameter(1, before)
                .getResultList();
        rows.forEach(session::remove);
        return rows.size();
    }
}
