package io.github.rkaushikdev.gussetline.persistence.models;

import static io.github.rkaushikdev.gussetline.persistence.TestDatabase.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.TestSession;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceMarketTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 5, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2025, 6, 7, 9, 0);

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static ObservationRow observation() {
        ObservationRow row = new ObservationRow();
        row.setSeriesKey("FRA-NYC-2025-07-04-Y");
        row.setFingerprint("f".repeat(32));
        row.setSource("gds");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(LocalDate.of(2025, 7, 4));
        row.setFareBases("KLWAP7RT");
        row.setBaseAmount(new BigDecimal("420.00"));
        row.setBaseCurrency("EUR");
        row.setTaxAmount(new BigDecimal("70.83"));
        row.setSurchargeAmount(new BigDecimal("180.00"));
        row.setTotalAmount(new BigDecimal("670.83"));
        row.setFirstSeen(SEEN);
        row.setLastSeen(SEEN);
        return row;
    }

    private static SectorRow sector(int position) {
        SectorRow row = new SectorRow();
        row.setPosition(position);
        row.setMarketingCarrier("LH");
        row.setFlightNumber("400");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(LocalDateTime.of(2025, 7, 4, 10, 25));
        row.setArrival(LocalDateTime.of(2025, 7, 4, 13, 5));
        row.setBookingClass("K");
        return row;
    }

    private static TaxRow tax(String code, String amount) {
        TaxRow row = new TaxRow();
        row.setCode(code);
        row.setAmount(new BigDecimal(amount));
        row.setCurrency("EUR");
        return row;
    }

    private static QuoteRow quote(LocalDate observedOn, String amount) {
        QuoteRow row = new QuoteRow();
        row.setOrigin("FRA");
        row.setDestination("NYC");
        row.setDeparture(LocalDate.of(2025, 7, 4));
        row.setObservedOn(observedOn);
        row.setAmount(new BigDecimal(amount));
        row.setCurrency("EUR");
        return row;
    }

    @Test
    void anObservationStoresItsPriceParts() {
        session.add(observation());
        session.commit();
        ObservationRow found = session.one(ObservationRow.class);
        assertThat(found.getBaseAmount()).isEqualTo(new BigDecimal("420.00"));
        assertThat(found.getSurchargeAmount()).isEqualTo(new BigDecimal("180.00"));
        assertThat(found.getSightings()).isEqualTo(1);
        assertThat(found).hasToString("<Observation FRA-NYC-2025-07-04-Y 670.83 EUR>");
    }

    @Test
    void theSameOfferTwiceInOneSeriesIsRefused() {
        session.add(observation());
        assertRefused(() -> {
            session.add(observation());
            session.commit();
        });
    }

    @Test
    void theSameFingerprintInAnotherSeriesIsADifferentRow() {
        ObservationRow august = observation();
        august.setSeriesKey("FRA-NYC-2025-08-04-Y");
        august.setDeparture(LocalDate.of(2025, 8, 4));
        session.add(observation(), august);
        session.commit();
        assertThat(session.all(ObservationRow.class)).hasSize(2);
    }

    @Test
    void aRepeatSightingMovesTheLastSeenRatherThanAddingARow() {
        session.add(observation());
        session.commit();
        ObservationRow found = session.one(ObservationRow.class);
        found.setLastSeen(LATER);
        found.setSightings(found.getSightings() + 1);
        session.commit();
        ObservationRow again = session.one(ObservationRow.class);
        assertThat(again.getFirstSeen()).isEqualTo(SEEN);
        assertThat(again.getLastSeen()).isEqualTo(LATER);
        assertThat(again.getSightings()).isEqualTo(2);
        assertThat(session.count(ObservationRow.class)).isEqualTo(1);
    }

    @Test
    void anObservationCarriesItsSectorsInOrder() {
        ObservationRow row = observation();
        SectorRow home = sector(1);
        home.setOrigin("JFK");
        home.setDestination("FRA");
        home.setFlightNumber("401");
        row.addSector(home);
        row.addSector(sector(0));
        session.add(row);
        session.commit();
        ObservationRow found = session.one(ObservationRow.class);
        assertThat(found.getSectors()).extracting(SectorRow::getPosition).containsExactly(0, 1);
        assertThat(found.getSectors().get(0).getFlightNumber()).isEqualTo("400");
    }

    @Test
    void twoSectorsAtTheSamePositionAreRefused() {
        ObservationRow row = observation();
        row.addSector(sector(0));
        SectorRow clash = sector(0);
        clash.setFlightNumber("401");
        row.addSector(clash);
        assertRefused(() -> {
            session.add(row);
            session.commit();
        });
    }

    @Test
    void deletingAnObservationTakesItsSectorsAndTaxes() {
        ObservationRow row = observation();
        row.addSector(sector(0));
        row.addTax(tax("DE", "70.83"));
        session.add(row);
        session.commit();
        session.delete(row);
        session.commit();
        assertThat(session.all(SectorRow.class)).isEmpty();
        assertThat(session.all(TaxRow.class)).isEmpty();
    }

    @Test
    void aTaxRemembersWhetherItWasCarrierMoney() {
        ObservationRow row = observation();
        row.addTax(tax("DE", "70.83"));
        TaxRow surcharge = tax("YQ", "180.00");
        surcharge.setCarrierImposed(true);
        row.addTax(surcharge);
        session.add(row);
        session.commit();
        List<TaxRow> found = session.select("select t from TaxRow t order by t.code", TaxRow.class);
        assertThat(found).extracting(TaxRow::isCarrierImposed).containsExactly(false, true);
        assertThat(found.get(0)).hasToString("<Tax DE 70.83>");
    }

    @Test
    void aSectorReadsBackAsATimetableLine() {
        ObservationRow row = observation();
        row.addSector(sector(0));
        session.add(row);
        session.commit();
        assertThat(session.one(SectorRow.class)).hasToString("<Sector LH400 FRAJFK>");
    }

    @Test
    void theSeriesIndexIsDeclared() {
        assertThat(Arrays.stream(ObservationRow.class.getAnnotation(Table.class).indexes())
                        .map(Index::name))
                .contains("ix_observations_series_seen", "ix_observations_market");
    }

    @Test
    void aSeriesCanBeReadBackCheapestFirst() {
        ObservationRow dearer = observation();
        dearer.setFingerprint("a".repeat(32));
        dearer.setTotalAmount(new BigDecimal("670.83"));
        ObservationRow cheaper = observation();
        cheaper.setFingerprint("b".repeat(32));
        cheaper.setTotalAmount(new BigDecimal("610.00"));
        session.add(dearer, cheaper);
        session.commit();
        List<ObservationRow> rows = session.select(
                "select o from ObservationRow o where o.seriesKey = ?1 order by o.totalAmount",
                ObservationRow.class,
                "FRA-NYC-2025-07-04-Y");
        assertThat(rows)
                .extracting(ObservationRow::getTotalAmount)
                .containsExactly(new BigDecimal("610.00"), new BigDecimal("670.83"));
    }

    @Test
    void aQuotedTotalThatDisagreesIsKeptBesideOurs() {
        ObservationRow row = observation();
        row.setQuotedTotal(new BigDecimal("700.00"));
        session.add(row);
        session.commit();
        ObservationRow found = session.one(ObservationRow.class);
        assertThat(found.getQuotedTotal()).contains(new BigDecimal("700.00"));
        assertThat(found.getTotalAmount()).isEqualTo(new BigDecimal("670.83"));
    }

    @Test
    void aQuoteStoresAndReadsBack() {
        QuoteRow row = quote(LocalDate.of(2025, 6, 5), "420.00");
        row.setCarrier("LH");
        session.add(row);
        session.commit();
        QuoteRow found = session.one(QuoteRow.class);
        assertThat(found.getCarrier()).isEqualTo("LH");
        assertThat(found.isSoldOut()).isFalse();
        assertThat(found).hasToString("<Quote FRANYC 2025-07-04 420.00>");
    }

    @Test
    void theSameQuoteTwiceOnOneDayIsRefused() {
        session.add(quote(LocalDate.of(2025, 6, 5), "420.00"));
        assertRefused(() -> {
            session.add(quote(LocalDate.of(2025, 6, 5), "420.00"));
            session.commit();
        });
    }

    @Test
    void theSameQuoteOnAnotherDayIsANewRow() {
        for (LocalDate day : List.of(LocalDate.of(2025, 6, 5), LocalDate.of(2025, 6, 6))) {
            session.add(quote(day, "420.00"));
        }
        session.commit();
        assertThat(session.all(QuoteRow.class)).hasSize(2);
    }

    @Test
    void aSoldOutQuoteIsStoredWithItsFlag() {
        QuoteRow row = quote(LocalDate.of(2025, 6, 5), "0");
        row.setSoldOut(true);
        session.add(row);
        session.commit();
        assertThat(session.one(QuoteRow.class).isSoldOut()).isTrue();
    }
}
