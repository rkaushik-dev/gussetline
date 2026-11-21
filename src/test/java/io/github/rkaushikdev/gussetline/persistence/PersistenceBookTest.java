package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.desk.Observation;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceBookTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2025, 12, 20);
    private static final String SERIES = "FRA-NYC-2025-12-20-Y";
    private static final LocalDate RAISED = LocalDate.of(2025, 11, 20);
    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 11, 20, 6, 0);

    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
    }

    private static WatchRow aWatch(UnitOfWork work) {
        return aWatch(work, SERIES, "220-1234567890");
    }

    private static WatchRow aWatch(UnitOfWork work, String series, String number) {
        TicketRow ticket = new TicketRow();
        ticket.setNumber(number);
        ticket.setRecordLocator("ABC123");
        ticket.setPassengerName("MUELLER/ANNA");
        ticket.setOrigin("FRA");
        ticket.setDestination("JFK");
        ticket.setDeparture(DEPARTURE);
        ticket.setIssuedOn(LocalDate.of(2025, 2, 14));
        ticket.setValidatingCarrier("LH");
        ticket.setBaseAmount(new BigDecimal("840.00"));
        ticket.setBaseCurrency("EUR");
        ticket.setTotalAmount(new BigDecimal("1090.83"));
        CouponRow coupon = new CouponRow();
        coupon.setPosition(0);
        coupon.setCarrier("LH");
        coupon.setFlightNumber("400");
        coupon.setOrigin("FRA");
        coupon.setDestination("JFK");
        coupon.setDeparture(DEPARTURE.atTime(10, 25));
        coupon.setBookingClass("K");
        coupon.setFareBasis("KLWAP7RT");
        ticket.addCoupon(coupon);
        WatchRow watch = new WatchRow();
        watch.setSeriesKey(series);
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(DEPARTURE);
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        ticket.addWatch(watch);
        work.session().persist(ticket);
        work.flush();
        return watch;
    }

    private static void aRecommendation(UnitOfWork work, WatchRow watch) {
        aRecommendation(work, watch, RAISED, "open");
    }

    private static void aRecommendation(UnitOfWork work, WatchRow watch, String state) {
        aRecommendation(work, watch, RAISED, state);
    }

    private static void aRecommendation(UnitOfWork work, WatchRow watch, LocalDate raised, String state) {
        RecommendationRow row = new RecommendationRow();
        row.setWatchId(watch.getId());
        row.setRaisedOn(raised);
        row.setState(state);
        row.setPaidAmount(new BigDecimal("840.00"));
        row.setNewFareAmount(new BigDecimal("700.00"));
        row.setPenaltyAmount(new BigDecimal("50.00"));
        row.setBenefitAmount(new BigDecimal("90.00"));
        row.setCurrency("EUR");
        row.setResidual("refundable");
        work.session().persist(row);
    }

    private static void aReading(UnitOfWork work, int sightings) {
        aReading(work, "700.00", SEEN, sightings);
    }

    private static void aReading(UnitOfWork work, String amount, LocalDateTime seen, int sightings) {
        String fingerprint = SERIES + "-" + amount;
        fingerprint = (fingerprint + "0".repeat(Math.max(0, 32 - fingerprint.length()))).substring(0, 32);
        ObservationRow row = new ObservationRow();
        row.setSeriesKey(SERIES);
        row.setSource("gds");
        row.setFingerprint(fingerprint);
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(DEPARTURE);
        row.setCabin("Y");
        row.setBaseAmount(new BigDecimal(amount));
        row.setBaseCurrency("EUR");
        row.setTaxAmount(new BigDecimal("0.00"));
        row.setSurchargeAmount(new BigDecimal("0.00"));
        row.setTotalAmount(new BigDecimal(amount));
        row.setFirstSeen(seen);
        row.setLastSeen(seen);
        row.setSightings(sightings);
        work.session().persist(row);
    }

    @Test
    void anEmptyDeskHasAnEmptyBook() {
        Book book;
        try (UnitOfWork work = database.unit()) {
            book = Book.openBook(work);
        }
        assertThat(book.size()).isZero();
        assertThat(book.departures()).isEmpty();
        assertThat(book).hasToString("0 open over 0 series");
    }

    @Test
    void anOpenRecommendationIsInTheBookWithItsDeparture() {
        try (UnitOfWork work = database.unit()) {
            aRecommendation(work, aWatch(work));
            work.commit();
        }

        Book book;
        try (UnitOfWork work = database.unit()) {
            book = Book.openBook(work);
        }
        assertThat(book.size()).isEqualTo(1);
        assertThat(book.departures()).isEqualTo(Map.of(SERIES, DEPARTURE));
        assertThat(book.recommendations().get(0).ticketNumber()).isEqualTo("220-1234567890");
    }

    @Test
    void aSettledRecommendationIsNotInTheBook() {
        try (UnitOfWork work = database.unit()) {
            aRecommendation(work, aWatch(work), "taken");
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.openBook(work).size()).isZero();
        }
    }

    @Test
    void theTicketComesLoadedSoACallerNeverQueriesPerRow() {
        // A caller that gets a row without it either trips over a detached instance or quietly issues
        // a query per row.
        try (UnitOfWork work = database.unit()) {
            aRecommendation(work, aWatch(work));
            work.commit();
        }

        Book book;
        try (UnitOfWork work = database.unit()) {
            book = Book.openBook(work);
        }
        assertThat(book.recommendations().get(0).seriesKey()).isEqualTo(SERIES);
    }

    @Test
    void theReadingsAreLeftOutUnlessTheyAreAskedFor() {
        try (UnitOfWork work = database.unit()) {
            aRecommendation(work, aWatch(work));
            aReading(work, 1);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.openBook(work).histories()).isEmpty();
            assertThat(Book.openBook(work, true).histories()).isNotEmpty();
        }
    }

    @Test
    void aReadingSeenOnThreeMorningsCountsThreeTimes() {
        // The difference between a drop that has held for three days and one seen this morning.
        try (UnitOfWork work = database.unit()) {
            aWatch(work);
            aReading(work, 3);
            work.commit();
        }

        Map<String, List<Observation>> found;
        try (UnitOfWork work = database.unit()) {
            found = Book.readingsFor(work, List.of(SERIES));
        }
        assertThat(found.get(SERIES)).hasSize(3);
        assertThat(found.get(SERIES)).allMatch(reading -> reading.amount().equals(Money.of("700.00", "EUR")));
    }

    @Test
    void aReadingSeenOnceCountsOnce() {
        try (UnitOfWork work = database.unit()) {
            aWatch(work);
            aReading(work, 1);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.readingsFor(work, List.of(SERIES)).get(SERIES)).hasSize(1);
        }
    }

    @Test
    void aSightingCountOfNothingStillCountsOnce() {
        try (UnitOfWork work = database.unit()) {
            aWatch(work);
            aReading(work, 0);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.readingsFor(work, List.of(SERIES)).get(SERIES)).hasSize(1);
        }
    }

    @Test
    void theReadingsComeBackOldestFirst() {
        try (UnitOfWork work = database.unit()) {
            aWatch(work);
            aReading(work, "840.00", LocalDateTime.of(2025, 11, 18, 6, 0), 1);
            aReading(work, "700.00", LocalDateTime.of(2025, 11, 20, 6, 0), 1);
            work.commit();
        }

        Map<String, List<Observation>> found;
        try (UnitOfWork work = database.unit()) {
            found = Book.readingsFor(work, List.of(SERIES));
        }
        assertThat(found.get(SERIES))
                .extracting(reading -> reading.amount().toString())
                .containsExactly("840.00 EUR", "700.00 EUR");
    }

    @Test
    void theReadingsAreCappedHoweverManyPollsThereWere() {
        try (UnitOfWork work = database.unit()) {
            aWatch(work);
            aReading(work, 40);
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.readingsFor(work, List.of(SERIES)).get(SERIES)).hasSize(Book.READINGS);
        }
    }

    @Test
    void aSeriesWithNoReadingsComesBackEmptyRatherThanMissing() {
        Map<String, List<Observation>> found;
        try (UnitOfWork work = database.unit()) {
            found = Book.readingsFor(work, List.of(SERIES));
        }
        assertThat(found).isEqualTo(Map.of(SERIES, List.of()));
    }

    @Test
    void askingForNoSeriesAsksNothing() {
        try (UnitOfWork work = database.unit()) {
            assertThat(Book.readingsFor(work, List.of())).isEmpty();
        }
    }

    @Test
    void theWindowIsKeyedOnTheDayItWasRaised() {
        try (UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, LocalDate.of(2025, 10, 1), "taken");
            aRecommendation(work, watch, LocalDate.of(2025, 11, 20), "taken");
            work.commit();
        }

        List<Recommendation> found;
        try (UnitOfWork work = database.unit()) {
            found = Book.raisedBetween(work, LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30));
        }
        assertThat(found).hasSize(1);
        assertThat(found.get(0).raisedOn()).isEqualTo(LocalDate.of(2025, 11, 20));
    }

    @Test
    void theWindowTakesEveryStateAndNotOnlyTheOpenOnes() {
        try (UnitOfWork work = database.unit()) {
            WatchRow watch = aWatch(work);
            aRecommendation(work, watch, "open");
            aRecommendation(work, watch, "taken");
            aRecommendation(work, watch, "expired");
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(Book.raisedBetween(work, LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30)))
                    .hasSize(3);
        }
    }

    @Test
    void aWindowWithNothingInItIsEmpty() {
        try (UnitOfWork work = database.unit()) {
            assertThat(Book.raisedBetween(work, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)))
                    .isEmpty();
        }
    }

    @Test
    void twoSeriesAreTwoEntriesInTheBook() {
        try (UnitOfWork work = database.unit()) {
            aRecommendation(work, aWatch(work));
            aRecommendation(work, aWatch(work, "MUC-CHI-2025-12-20-Y", "220-9999999999"));
            work.commit();
        }

        Book book;
        try (UnitOfWork work = database.unit()) {
            book = Book.openBook(work);
        }
        assertThat(book.size()).isEqualTo(2);
        assertThat(book.series()).containsExactly("FRA-NYC-2025-12-20-Y", "MUC-CHI-2025-12-20-Y");
    }
}
