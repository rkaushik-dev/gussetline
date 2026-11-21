package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.carriers.Carrier;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Airport;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.QuoteRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceRepositoriesTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 12, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2025, 6, 14, 9, 0);
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 12);
    private static final LocalDate NEW_YEAR = LocalDate.of(2025, 1, 1);

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static Offer anOffer() {
        return anOffer("840.00", SEEN);
    }

    private static Offer anOffer(String amount) {
        return anOffer(amount, SEEN);
    }

    private static Offer anOffer(String amount, LocalDateTime observedAt) {
        return new Offer(
                "gds",
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                new PriceBreakdown(Money.of(amount, "EUR")),
                List.of("KLWAP7RT"),
                observedAt);
    }

    private static Fare inSale(String amount, String basis, LocalDate effective, LocalDate discontinue) {
        return fare("FRA", "JFK", amount).toBuilder()
                .fareBasis(basis)
                .effective(effective)
                .discontinue(discontinue)
                .build();
    }

    @Test
    void anAirportSavesOnceAndReadsBack() {
        AirportRepository repo = new AirportRepository(session.entityManager());
        repo.save(Geography.airport("FRA"));
        repo.save(Geography.airport("FRA"));
        session.commit();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.get("fra").name()).isEqualTo("Frankfurt am Main");
    }

    @Test
    void anAirportWeDoNotHoldIsANotFound() {
        AirportRepository repo = new AirportRepository(session.entityManager());
        assertThatThrownBy(() -> repo.get("QQQ")).isInstanceOf(NotFoundError.class);
    }

    @Test
    void theAirportsOfACityComeBackSorted() {
        AirportRepository repo = new AirportRepository(session.entityManager());
        for (String code : List.of("LHR", "LGW", "STN")) {
            repo.save(Geography.airport(code));
        }
        session.commit();
        assertThat(repo.inCity("LON")).extracting(Airport::code).containsExactly("LGW", "LHR", "STN");
    }

    @Test
    void aCarrierSavesAndReadsBack() {
        CarrierRepository repo = new CarrierRepository(session.entityManager());
        repo.save(Carriers.carrier("LH"));
        repo.save(Carriers.carrier("BA"));
        session.commit();
        assertThat(repo.all()).extracting(Carrier::code).containsExactly("BA", "LH");
        assertThat(repo.get("lh").name()).isEqualTo("Lufthansa");
    }

    @Test
    void aCarrierWeDoNotHoldIsANotFound() {
        CarrierRepository repo = new CarrierRepository(session.entityManager());
        assertThatThrownBy(() -> repo.get("ZZ")).isInstanceOf(NotFoundError.class);
    }

    @Test
    void aLoadTakesItsFaresAndCountsThem() {
        TariffRepository repo = new TariffRepository(session.entityManager());
        LoadRow load = repo.openLoad("LH", "LH.dat", "a".repeat(32), SEEN, NEW_YEAR);
        int written = repo.addFares(
                load,
                List.of(
                        fare("FRA", "JFK", "420.00"),
                        fare("FRA", "JFK", "380.00").withFareBasis("QRT")));
        session.commit();
        assertThat(written).isEqualTo(2);
        assertThat(repo.countFares()).isEqualTo(2);
    }

    @Test
    void aFareSearchComesBackCheapestFirst() {
        TariffRepository repo = new TariffRepository(session.entityManager());
        LoadRow load = repo.openLoad("LH", "LH.dat", "a".repeat(32), SEEN, NEW_YEAR);
        repo.addFares(
                load,
                List.of(
                        fare("FRA", "JFK", "420.00"),
                        fare("FRA", "JFK", "380.00").withFareBasis("QRT")));
        session.commit();
        List<Fare> found = repo.search("LH", "FRA", "NYC");
        assertThat(found)
                .extracting(f -> f.amount().amount())
                .containsExactly(new BigDecimal("380.00"), new BigDecimal("420.00"));
    }

    @Test
    void aFareSearchCanBeNarrowedToADate() {
        TariffRepository repo = new TariffRepository(session.entityManager());
        LoadRow load = repo.openLoad("LH", "LH.dat", "a".repeat(32), SEEN, NEW_YEAR);
        repo.addFares(
                load,
                List.of(
                        inSale("420.00", "KLWAP7RT", NEW_YEAR, LocalDate.of(2025, 3, 31)),
                        inSale("440.00", "QRT", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 12, 31))));
        session.commit();
        assertThat(repo.search("LH", "FRA", "NYC", LocalDate.of(2025, 2, 14))).hasSize(1);
        assertThat(repo.search("LH", "FRA", "NYC", LocalDate.of(2025, 5, 14))).hasSize(1);
        assertThat(repo.search("LH", "FRA", "NYC")).hasSize(2);
    }

    @Test
    void aMarketWithNothingFiledComesBackEmpty() {
        assertThat(new TariffRepository(session.entityManager()).search("LH", "FRA", "BKK"))
                .isEmpty();
    }

    @Test
    void theLoadsForACarrierComeBackNewestFirst() {
        TariffRepository repo = new TariffRepository(session.entityManager());
        repo.openLoad("LH", "old.dat", "a".repeat(32), SEEN, NEW_YEAR);
        repo.openLoad("LH", "new.dat", "b".repeat(32), LATER, LocalDate.of(2025, 6, 1));
        session.commit();
        assertThat(repo.loadsFor("LH")).extracting(LoadRow::getFilename).containsExactly("new.dat", "old.dat");
    }

    @Test
    void aLoadCanBeRetiredWithoutLosingItsFares() {
        TariffRepository repo = new TariffRepository(session.entityManager());
        LoadRow load = repo.openLoad("LH", "LH.dat", "a".repeat(32), SEEN, NEW_YEAR);
        repo.addFares(load, List.of(fare()));
        session.commit();
        repo.retire(load);
        session.commit();
        assertThat(repo.loadsFor("LH").get(0).isRetired()).isTrue();
        assertThat(repo.countFares()).isEqualTo(1);
    }

    @Test
    void aNewOfferIsInserted() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer());
        session.commit();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void theSameOfferAgainMovesTheStampRatherThanAddingARow() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer());
        session.commit();
        repo.save(anOffer("840.00", LATER));
        session.commit();
        assertThat(repo.count()).isEqualTo(1);
        ObservationRow saved = repo.save(anOffer());
        assertThat(session.all(saved.getClass())).isNotEmpty();
    }

    @Test
    void theSightingCountClimbsOnARepeat() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer());
        session.commit();
        ObservationRow row = repo.save(anOffer("840.00", LATER));
        session.commit();
        assertThat(row.getSightings()).isEqualTo(2);
        assertThat(row.getFirstSeen()).isEqualTo(SEEN);
        assertThat(row.getLastSeen()).isEqualTo(LATER);
    }

    @Test
    void anEarlierSightingMovesTheFirstSeenBack() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer("840.00", LATER));
        session.commit();
        ObservationRow row = repo.save(anOffer("840.00", SEEN));
        session.commit();
        assertThat(row.getFirstSeen()).isEqualTo(SEEN);
        assertThat(row.getLastSeen()).isEqualTo(LATER);
    }

    @Test
    void aDifferentPriceIsADifferentRow() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer("840.00"));
        repo.save(anOffer("790.00"));
        session.commit();
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void savingABatchReportsNewAgainstRepeats() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        ObservationRepository.Saved first = repo.saveAll(List.of(anOffer("840.00"), anOffer("790.00")));
        session.commit();
        assertThat(first).isEqualTo(new ObservationRepository.Saved(2, 0));
        ObservationRepository.Saved second = repo.saveAll(List.of(anOffer("840.00"), anOffer("700.00")));
        session.commit();
        assertThat(second).isEqualTo(new ObservationRepository.Saved(1, 1));
    }

    @Test
    void aSeriesComesBackCheapestFirst() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.saveAll(List.of(anOffer("840.00"), anOffer("790.00"), anOffer("900.00")));
        session.commit();
        String key = anOffer().seriesKey().joined();
        assertThat(repo.series(key))
                .extracting(o -> o.total().amount())
                .containsExactly(new BigDecimal("790.00"), new BigDecimal("840.00"), new BigDecimal("900.00"));
    }

    @Test
    void theCheapestInASeriesIsTheFirstOne() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.saveAll(List.of(anOffer("840.00"), anOffer("790.00")));
        session.commit();
        Optional<Offer> cheapest = repo.cheapestIn(anOffer().seriesKey().joined());
        assertThat(cheapest).isPresent();
        assertThat(cheapest.get().total()).isEqualTo(Money.of("790.00", "EUR"));
    }

    @Test
    void aSeriesWeHoldNothingForHasNoCheapest() {
        assertThat(new ObservationRepository(session.entityManager()).cheapestIn("nothing"))
                .isEmpty();
    }

    @Test
    void observationsCanBeReadBackByWhenTheyWereSeen() {
        ObservationRepository repo = new ObservationRepository(session.entityManager());
        repo.save(anOffer("840.00", SEEN));
        repo.save(anOffer("790.00", LATER));
        session.commit();
        assertThat(repo.seenSince(LATER)).hasSize(1);
        assertThat(repo.seenSince(SEEN)).hasSize(2);
    }

    private TicketRow ticketWithWatch(LocalDate departure) {
        return ticketWithWatch(departure, true);
    }

    private TicketRow ticketWithWatch(LocalDate departure, boolean active) {
        TicketRow row = new TicketRow();
        row.setNumber("220-" + departure);
        row.setRecordLocator("ABC123");
        row.setPassengerName("MUELLER/ANNA");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(departure);
        row.setIssuedOn(LocalDate.of(2025, 2, 14));
        row.setValidatingCarrier("LH");
        row.setBaseAmount(new BigDecimal("840.00"));
        row.setBaseCurrency("EUR");
        row.setTotalAmount(new BigDecimal("1090.83"));
        WatchRow watch = new WatchRow();
        watch.setSeriesKey("FRA-NYC-" + departure + "-Y");
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(departure);
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        watch.setActive(active);
        row.addWatch(watch);
        session.add(row);
        return row;
    }

    @Test
    void liveWatchesComeBackClosestFirst() {
        ticketWithWatch(LocalDate.of(2025, 8, 4));
        ticketWithWatch(LocalDate.of(2025, 7, 4));
        session.commit();
        List<WatchRow> found = new WatchRepository(session.entityManager()).live(TODAY);
        assertThat(found)
                .extracting(WatchRow::getDeparture)
                .containsExactly(LocalDate.of(2025, 7, 4), LocalDate.of(2025, 8, 4));
    }

    @Test
    void aDepartureThatHasGoneIsNotLive() {
        ticketWithWatch(LocalDate.of(2025, 6, 1));
        session.commit();
        assertThat(new WatchRepository(session.entityManager()).live(TODAY)).isEmpty();
    }

    @Test
    void aRetiredWatchIsNotLive() {
        ticketWithWatch(LocalDate.of(2025, 7, 4), false);
        session.commit();
        assertThat(new WatchRepository(session.entityManager()).live(TODAY)).isEmpty();
    }

    @Test
    void theWatchesOfOneTicketCanBeFound() {
        ticketWithWatch(LocalDate.of(2025, 7, 4));
        session.commit();
        assertThat(new WatchRepository(session.entityManager()).forTicket("220-2025-07-04"))
                .hasSize(1);
    }

    @Test
    void flownWatchesAreRetiredWithAReason() {
        ticketWithWatch(LocalDate.of(2025, 6, 1));
        ticketWithWatch(LocalDate.of(2025, 7, 4));
        session.commit();
        WatchRepository repo = new WatchRepository(session.entityManager());
        assertThat(repo.retireFlown(TODAY)).isEqualTo(1);
        session.commit();
        assertThat(repo.retireFlown(TODAY)).isZero();
        assertThat(repo.live(TODAY)).hasSize(1);
        WatchRow flown = session.one(
                "select w from WatchRow w where w.departure = ?1", WatchRow.class, LocalDate.of(2025, 6, 1));
        assertThat(flown.getRetiredReason()).isEqualTo("departure has gone");
    }

    private static QuoteRow aQuote(String amount) {
        return aQuote(amount, LocalDate.of(2025, 7, 4), false);
    }

    private static QuoteRow aQuote(String amount, LocalDate departure) {
        return aQuote(amount, departure, false);
    }

    private static QuoteRow aQuote(String amount, LocalDate departure, boolean soldOut) {
        QuoteRow row = new QuoteRow();
        row.setOrigin("FRA");
        row.setDestination("NYC");
        row.setDeparture(departure);
        row.setObservedOn(TODAY);
        row.setAmount(new BigDecimal(amount));
        row.setCurrency("EUR");
        row.setSoldOut(soldOut);
        return row;
    }

    @Test
    void aQuoteSavesOnceADayAndUpdatesAfter() {
        QuoteRepository repo = new QuoteRepository(session.entityManager());
        repo.save(aQuote("420.00"));
        session.commit();
        QuoteRow row = repo.save(aQuote("380.00"));
        session.commit();
        assertThat(row.getAmount()).isEqualByComparingTo("380.00");
        assertThat(session.count(QuoteRow.class)).isEqualTo(1);
    }

    @Test
    void theCheapestQuoteInAWindowIsFound() {
        QuoteRepository repo = new QuoteRepository(session.entityManager());
        repo.save(aQuote("420.00", LocalDate.of(2025, 7, 4)));
        repo.save(aQuote("380.00", LocalDate.of(2025, 7, 5)));
        session.commit();
        Optional<QuoteRow> found =
                repo.cheapestBetween("FRA", "NYC", LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31));
        assertThat(found).isPresent();
        assertThat(found.get().getDeparture()).isEqualTo(LocalDate.of(2025, 7, 5));
    }

    @Test
    void aSoldOutDayIsNeverTheCheapest() {
        QuoteRepository repo = new QuoteRepository(session.entityManager());
        repo.save(aQuote("420.00", LocalDate.of(2025, 7, 4)));
        repo.save(aQuote("0", LocalDate.of(2025, 7, 5), true));
        session.commit();
        Optional<QuoteRow> found =
                repo.cheapestBetween("FRA", "NYC", LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31));
        assertThat(found).isPresent();
        assertThat(found.get().getDeparture()).isEqualTo(LocalDate.of(2025, 7, 4));
    }

    @Test
    void aWindowWithNothingInItHasNoCheapest() {
        assertThat(new QuoteRepository(session.entityManager())
                        .cheapestBetween("FRA", "NYC", LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31)))
                .isEmpty();
    }
}
