package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The queries the desk runs often, checked against the plan the database picks.
 *
 * <p>Every index in the schema was added for a query somebody had in mind, and none of them says
 * which. A year later the query has been rewritten, the index is dead weight on every insert, and the
 * query it was supposed to serve is a full scan that nobody notices because the test database has
 * nine rows in it.
 *
 * <p>So this asks the database what it would actually do. {@code EXPLAIN} writes the query back with
 * the index it chose named in a comment after each table, or {@code tableScan} when it would read
 * every row, and {@code index sorted} when the index hands the rows back in the order asked for, so
 * no sort follows. That is the only check here: the hot queries use an index and it is the one they
 * were given.
 *
 * <p>The numbers matter more than they look. The observations table grows by several million rows a
 * year and the night run reads it once per watch, so a scan there is the difference between a run
 * that finishes before the desk opens and one that does not. The fares table is small and read
 * constantly, which is the other shape of the same problem.
 *
 * <p>This is not a benchmark. Timings on a laptop under a test runner say nothing, and a test that
 * fails when the machine is busy is a test somebody deletes. The plan is a fact about the query and
 * it does not move.
 */
class QueryPlansTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 12, 1);

    /** The series the night run's read of the readings is asked for. */
    private static final String SERIES = "FRA-NYC-2026-01-01-Y";

    private Database database;

    @BeforeEach
    void stocked() {
        database = TestDatabase.database();
        try (UnitOfWork work = database.unit()) {
            stock(work.session());
            work.commit();
        }
    }

    /** Enough rows that the planner has something to think about. */
    private static void stock(EntityManager session) {
        LoadRow load = new LoadRow();
        load.setCarrier("LH");
        load.setFilename("plans.dat");
        load.setDigest("0".repeat(32));
        load.setLoadedAt(LocalDateTime.of(2025, 1, 1, 6, 0));
        load.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        session.persist(load);
        session.flush();

        for (int index = 0; index < 200; index++) {
            FareRow fare = new FareRow();
            fare.setLoadId(load.getId());
            fare.setCarrier(index % 2 == 1 ? "LH" : "UA");
            fare.setOrigin("FRA");
            fare.setDestination(index % 3 == 0 ? "CHI" : "NYC");
            fare.setFareBasis(String.format(Locale.ROOT, "K%04d", index));
            fare.setAmount(new BigDecimal("420.00"));
            fare.setCurrency("EUR");
            fare.setOwrt("2");
            fare.setGlobalIndicator("AT");
            fare.setRuleNumber("0100");
            fare.setRoutingNumber("0011");
            fare.setTariffNumber("1");
            fare.setCabin("Y");
            fare.setFareType("published");
            fare.setEffective(LocalDate.of(2025, 1, 1));
            fare.setDiscontinue(LocalDate.of(2025, 12, 31));
            fare.setFirstTravel(LocalDate.of(2025, 1, 1));
            fare.setLastTravel(LocalDate.of(2025, 12, 31));
            session.persist(fare);
        }

        for (int index = 0; index < 200; index++) {
            int month = index % 9 + 1;
            ObservationRow observation = new ObservationRow();
            observation.setSeriesKey("FRA-NYC-2026-0" + month + "-01-Y");
            observation.setSource("gds");
            observation.setFingerprint(String.format(Locale.ROOT, "%032d", index));
            observation.setOrigin("FRA");
            observation.setDestination("JFK");
            observation.setDeparture(LocalDate.of(2026, month, 1));
            observation.setCabin("Y");
            observation.setBaseAmount(new BigDecimal("700.00"));
            observation.setBaseCurrency("EUR");
            observation.setTaxAmount(new BigDecimal("0.00"));
            observation.setSurchargeAmount(new BigDecimal("0.00"));
            observation.setTotalAmount(new BigDecimal("700.00"));
            observation.setFirstSeen(LocalDateTime.of(2025, 11, 1, 6, 0));
            observation.setLastSeen(LocalDateTime.of(2025, 11, index % 28 + 1, 6, 0));
            session.persist(observation);
        }
    }

    /** What the database says it would do with a query, lower case, to look for a name in. */
    private String planFor(String query, Object... parameters) {
        return text("EXPLAIN " + query, parameters).get(0);
    }

    /** The one column a statement reads, row by row, lower case. */
    private List<String> text(String statement, Object... parameters) {
        try (UnitOfWork work = database.unit()) {
            NativeQuery<String> query = work.session().unwrap(Session.class).createNativeQuery(statement, String.class);
            for (int position = 0; position < parameters.length; position++) {
                query.setParameter(position + 1, parameters[position]);
            }
            return query.getResultList().stream()
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .toList();
        }
    }

    @Test
    void theNightRunReadsASeriesByItsOwnIndex() {
        // Several million rows a year, read once per watch. A scan here is the difference between a
        // run that finishes before the desk opens and one that does not.
        // The query names its index, since left to itself the database takes the one on the series
        // alone, which cannot order (Book.LATEST_READINGS). This holds the night run to the index.
        String plan = planFor(Book.LATEST_READINGS, SERIES);
        assertThat(plan).as(plan).contains("ix_observations_series_seen");
        assertThat(plan).as(plan).doesNotContain("observations.tablescan");
    }

    @Test
    void theMarketLookupUsesTheMarketIndex() {
        String plan = planFor("SELECT * FROM observations WHERE origin = 'FRA' AND destination = 'JFK'"
                + " AND departure = DATE '2026-01-01'");
        assertThat(plan).as(plan).contains("ix_observations_market");
    }

    @Test
    void aFareLookupUsesTheIndexItWasGiven() {
        // Small and read constantly, which is the other shape of the same problem: a scan is cheap
        // once and not cheap on every component.
        String plan = planFor("SELECT * FROM fares WHERE carrier = 'LH' AND origin = 'FRA' AND destination = 'NYC'");
        assertThat(plan).as(plan).contains("ix_fares");
        assertThat(plan).as(plan).doesNotContain("fares.tablescan");
    }

    @Test
    void aFareLookupWithASaleWindowUsesTheWiderIndex() {
        String plan = planFor("SELECT * FROM fares WHERE carrier = 'LH' AND origin = 'FRA' AND destination = 'NYC'"
                + " AND effective <= DATE '" + TODAY + "' AND discontinue >= DATE '" + TODAY + "'");
        assertThat(plan).as(plan).contains("ix_fares_lookup");
    }

    @Test
    void aFareByBasisStillUsesAnIndex() {
        // Added for the night run, which gets from a ticket's fare basis to its rule and has no
        // market to narrow on.
        String plan = planFor("SELECT * FROM fares WHERE carrier = 'LH' AND fare_basis = 'K0001'");
        assertThat(plan).as(plan).contains("ix_fares_basis");
        assertThat(plan).as(plan).doesNotContain("fares.tablescan");
    }

    @Test
    void theOpenBookUsesTheStateIndex() {
        String plan = planFor("SELECT * FROM recommendations WHERE state = 'open'");
        assertThat(plan).as(plan).contains("ix_recommendations_state");
    }

    @Test
    void theLiveWatchesUseTheLiveIndex() {
        String plan = planFor("SELECT * FROM watches WHERE active AND departure >= DATE '" + TODAY + "'");
        assertThat(plan).as(plan).contains("ix_watches_live");
    }

    @Test
    void theSeriesIndexCoversTheOrderingAndNotOnlyTheFilter() {
        // An index that filters but does not order leaves a sort behind it, and the night run's query
        // is a filter plus an order plus a limit.
        String plan = planFor(Book.LATEST_READINGS, SERIES);
        assertThat(plan).as(plan).contains("ix_observations_series_seen");
        assertThat(plan).as(plan).contains("index sorted");
    }

    @Test
    void everyIndexInTheSchemaIsNamedAfterWhatItIsFor() {
        // A named index can be looked for in a plan. One the database names for itself, to back a
        // constraint, cannot, and the whole class above is looking for names. The migrations' own
        // history table is bookkeeping and not the desk's schema.
        List<String> names = text("SELECT index_name FROM information_schema.indexes WHERE table_schema = 'PUBLIC'"
                + " AND NOT is_generated AND table_name <> 'flyway_schema_history'");
        assertThat(names).contains("ix_observations_series_seen", "ix_fares_basis");
        assertThat(names).allMatch(name -> name.startsWith("ix_") || name.startsWith("uq_") || name.startsWith("pk_"));
    }
}
