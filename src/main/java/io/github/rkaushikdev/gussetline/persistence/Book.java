package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.desk.Observation;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.Session;

/**
 * The desk's open book: the recommendations still open and what a caller needs to rank them.
 *
 * <p>The night run, the queue command and the worklist endpoint all need the same three things: the
 * recommendations still open, the departure behind each of them, and the last few readings on their
 * series. Each writing that out alone drifts in the way that matters most: one loads the ticket
 * eagerly and another does not, and the second issues a query per row that nobody notices until the
 * book has two hundred rows in it.
 *
 * <p>Everything here loads what it hands back. A recommendation reaches its ticket through its watch,
 * and both come in the same query as the recommendation, so nothing handed out ever goes back to the
 * database for them. That is not a caller's decision to make.
 *
 * <p>The readings are optional and separate. The night run wants a handful per series and the queue
 * wants the same handful, but a savings report over a year wants none at all, and loading them for it
 * would be several thousand queries for something nobody reads.
 *
 * @param recommendations the open recommendations, the earliest raised first
 * @param departures the departure behind each series with an open recommendation, by series key
 * @param histories the latest readings of each series, oldest first, when they were asked for
 */
public record Book(
        List<Recommendation> recommendations,
        Map<String, LocalDate> departures,
        Map<String, List<Observation>> histories) {

    /**
     * How many readings per series a caller gets when it asks for them. The same handful everywhere,
     * because two callers reading different depths of history answer the market moved question
     * differently on the same data.
     */
    public static final int READINGS = 8;

    /**
     * The night run's read of one series: the latest readings, newest first.
     *
     * <p>The index is named and the order names the series before the stamp, so the database walks
     * the series index backwards and stops at the limit. Within one series the two orders are the
     * same. Left to itself the database picks the index on the series alone, which filters as well
     * and cannot order, and sorts every reading the series has ever had to hand back eight; on a table
     * that grows by millions of rows a year that is the difference between a night run that finishes
     * before the desk opens and one that does not.
     */
    static final String LATEST_READINGS = "SELECT * FROM observations USE INDEX (ix_observations_series_seen)"
            + " WHERE series_key = ?1 ORDER BY series_key DESC, last_seen DESC LIMIT " + READINGS;

    /** Copies everything, keeping the order of both maps. */
    public Book {
        recommendations = List.copyOf(recommendations);
        departures = Collections.unmodifiableMap(new LinkedHashMap<>(departures));
        Map<String, List<Observation>> read = new LinkedHashMap<>();
        histories.forEach((key, readings) -> read.put(key, List.copyOf(readings)));
        histories = Collections.unmodifiableMap(read);
    }

    /** Every series with an open recommendation, sorted. */
    public List<String> series() {
        return departures.keySet().stream().sorted().toList();
    }

    /** How many recommendations are open. */
    public int size() {
        return recommendations.size();
    }

    /** {@code 2 open over 1 series}. */
    @Override
    public String toString() {
        return recommendations.size() + " open over " + departures.size() + " series";
    }

    /** {@link #openBook(UnitOfWork, boolean)} without the readings. */
    public static Book openBook(UnitOfWork work) {
        return openBook(work, false);
    }

    /**
     * Every recommendation still open, the earliest raised first, with its ticket and watch already
     * in.
     *
     * @param withReadings whether the latest readings of each series come too; that costs a query per
     *     series and buys the market moved check, which the night run and the worklist want and a
     *     report that only adds up money does not
     */
    public static Book openBook(UnitOfWork work, boolean withReadings) {
        List<RecommendationRow> rows = work.session()
                .createQuery(
                        "select r from RecommendationRow r join fetch r.watch w join fetch w.ticket"
                                + " where r.state = 'open' order by r.raisedOn",
                        RecommendationRow.class)
                .getResultList();
        List<Recommendation> open = new ArrayList<>();
        Map<String, LocalDate> departures = new LinkedHashMap<>();
        for (RecommendationRow row : rows) {
            open.add(recommendationFrom(row));
            departures.put(row.getWatch().getSeriesKey(), row.getWatch().getDeparture());
        }
        Map<String, List<Observation>> histories = Map.of();
        if (withReadings) {
            histories = readingsFor(work, departures.keySet().stream().sorted().toList());
        }
        return new Book(open, departures, histories);
    }

    /**
     * The last few readings per series, oldest first, one entry per poll, at most {@link #READINGS} of
     * them. A series with no readings is there, with none.
     *
     * <p>Oldest first because everything that reads a history reads the tail of it, and one query per
     * series rather than one for all of them because the limit is per series and a single query would
     * have to window over them.
     *
     * <p>Per poll and not per row, which is the part that matters. A stored observation is a price:
     * the table is keyed on content, so a market that sat at the same number for three mornings is one
     * row with a sighting count of three, and handing back one reading tells a hysteresis the price has
     * been seen once. That is the difference between a drop that has held for three days and one seen
     * this morning, and every caller here wants the first reading. A row that says it was never seen
     * still counts once.
     */
    public static Map<String, List<Observation>> readingsFor(UnitOfWork work, List<String> series) {
        Map<String, List<Observation>> found = new LinkedHashMap<>();
        Session session = work.session().unwrap(Session.class);
        for (String key : series) {
            List<ObservationRow> newestFirst = session.createNativeQuery(LATEST_READINGS, ObservationRow.class)
                    .setParameter(1, key)
                    .getResultList();
            List<Observation> polls = new ArrayList<>();
            for (ObservationRow row : newestFirst.reversed()) {
                Observation reading = DeskMap.observationRowToReading(row);
                polls.addAll(Collections.nCopies(Math.max(row.getSightings(), 1), reading));
            }
            found.put(key, List.copyOf(polls.subList(Math.max(0, polls.size() - READINGS), polls.size())));
        }
        return Collections.unmodifiableMap(found);
    }

    /**
     * Every recommendation raised in a window, both days included, whatever became of it, the
     * earliest first.
     *
     * <p>On the day it was raised and not the day it was settled, because a recommendation belongs to
     * the month the desk found it in. A report keyed on the settlement moves rows between months every
     * time an agent works through a backlog.
     */
    public static List<Recommendation> raisedBetween(UnitOfWork work, LocalDate first, LocalDate last) {
        return work
                .session()
                .createQuery(
                        "select r from RecommendationRow r join fetch r.watch w join fetch w.ticket"
                                + " where r.raisedOn >= ?1 and r.raisedOn <= ?2 order by r.raisedOn",
                        RecommendationRow.class)
                .setParameter(1, first)
                .setParameter(2, last)
                .getResultList()
                .stream()
                .map(Book::recommendationFrom)
                .toList();
    }

    private static Recommendation recommendationFrom(RecommendationRow row) {
        return DeskMap.rowToRecommendation(
                row, row.getWatch().getTicket().getNumber(), row.getWatch().getSeriesKey());
    }
}
