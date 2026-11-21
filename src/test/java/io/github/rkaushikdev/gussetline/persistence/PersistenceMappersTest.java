package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.airportToRow;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.carrierToRow;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.fareToRow;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.offerToRow;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.rowToAirport;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.rowToCarrier;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.rowToFare;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.rowToOffer;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.rowToSegment;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.segmentToRow;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.seriesKeyOf;
import static io.github.rkaushikdev.gussetline.persistence.Mappers.taxToRow;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.carriers.Alliance;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.models.CarrierRow;
import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.SectorRow;
import io.github.rkaushikdev.gussetline.persistence.models.TaxRow;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceMappersTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 12, 9, 0);

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    /** A round trip on Lufthansa with a tax and a carrier surcharge, ready for a test to change. */
    private static Offer.Builder anOffer() {
        return Offer.builder()
                .source("gds")
                .itinerary(new Itinerary(List.of(
                        segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"),
                        segment("LH", "401", "JFK", "FRA", "2025-07-18 18:30", "2025-07-19 08:05"))))
                .breakdown(new PriceBreakdown(
                        Money.of("840.00", "EUR"),
                        List.of(
                                new AppliedTax("DE", Money.of("70.83", "EUR"), "FRA"),
                                new AppliedTax("YQ", Money.of("180.00", "EUR"), "FRA", true)),
                        Money.of("1090.83", "EUR")))
                .fareBases(List.of("KLWAP7RT", "KLWAP7RT"))
                .observedAt(SEEN)
                .seats(4)
                .baggage(Baggage.ofPieces(1))
                .refundable(false)
                .validatingCarrier("LH");
    }

    private static LoadRow load() {
        LoadRow load = new LoadRow();
        load.setCarrier("LH");
        load.setFilename("x.dat");
        load.setDigest("a".repeat(32));
        load.setLoadedAt(SEEN);
        load.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        return load;
    }

    /** Write the offer, and read it back from the database. */
    private Offer throughTheDatabase(Offer offer) {
        session.add(offerToRow(offer));
        session.commit();
        return rowToOffer(session.one(ObservationRow.class));
    }

    @Test
    void anAirportRoundTrips() {
        assertThat(rowToAirport(airportToRow(Geography.airport("FRA")))).isEqualTo(Geography.airport("FRA"));
    }

    @Test
    void aCarrierRoundTrips() {
        assertThat(rowToCarrier(carrierToRow(Carriers.carrier("LH")))).isEqualTo(Carriers.carrier("LH"));
    }

    @Test
    void anUnalignedCarrierKeepsItsAlliance() {
        CarrierRow row = carrierToRow(Carriers.carrier("EK"));
        assertThat(row.getAlliance()).isEqualTo("none");
        assertThat(rowToCarrier(row).alliance()).isSameAs(Alliance.NONE);
    }

    @Test
    void aLowCostCarrierKeepsItsFlag() {
        assertThat(rowToCarrier(carrierToRow(Carriers.carrier("FR"))).lowCost()).isTrue();
    }

    @Test
    void aFareRoundTrips() {
        Fare original = fare("FRA", "JFK", "420.00");
        LoadRow load = load();
        load.addFare(fareToRow(original, 0));
        session.add(load);
        session.commit();
        Fare back = rowToFare(session.one(FareRow.class));
        assertThat(back).isEqualTo(original);
    }

    @Test
    void aFareAmountSurvivesTheRoundTripExactly() {
        Fare original = fare().withAmount(Money.of("12.999", "KWD"));
        LoadRow load = load();
        load.addFare(fareToRow(original, 0));
        session.add(load);
        session.commit();
        assertThat(rowToFare(session.one(FareRow.class)).amount().amount()).isEqualTo(new BigDecimal("12.999"));
    }

    @Test
    void aSegmentRoundTrips() {
        Segment original = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05");
        assertThat(rowToSegment(segmentToRow(original, 0))).isEqualTo(original);
    }

    @Test
    void aCodeshareKeepsBothCarriers() {
        Segment original = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")
                .withOperatingCarrier("UA");
        SectorRow row = segmentToRow(original, 0);
        assertThat(row.getOperatingCarrier()).isEqualTo("UA");
        assertThat(rowToSegment(row).isCodeshare()).isTrue();
    }

    @Test
    void aSegmentWithNoOperatingCarrierStaysThatWay() {
        Segment original = segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05");
        SectorRow row = segmentToRow(original, 0);
        assertThat(row.getOperatingCarrier()).isEmpty();
        assertThat(rowToSegment(row).operatingCarrier()).isEmpty();
    }

    @Test
    void aTaxKeepsWhetherItWasCarrierMoney() {
        TaxRow government = taxToRow(new AppliedTax("DE", Money.of("70.83", "EUR"), "FRA"));
        TaxRow carrierMoney = taxToRow(new AppliedTax("YQ", Money.of("180.00", "EUR"), "FRA", true));
        assertThat(government.isCarrierImposed()).isFalse();
        assertThat(carrierMoney.isCarrierImposed()).isTrue();
    }

    @Test
    void anOfferRoundTripsThroughTheDatabase() {
        Offer back = throughTheDatabase(anOffer().build());
        assertThat(back.source()).isEqualTo("gds");
        assertThat(back.breakdown().base()).isEqualTo(Money.of("840.00", "EUR"));
        assertThat(back.fareBases()).containsExactly("KLWAP7RT", "KLWAP7RT");
        assertThat(back.seats()).contains(4);
        assertThat(back.baggage().pieces()).contains(1);
        assertThat(back.refundable()).contains(false);
        assertThat(back.validatingCarrier()).contains("LH");
    }

    @Test
    void theItineraryComesBackInOrder() {
        Offer back = throughTheDatabase(anOffer().build());
        assertThat(back.itinerary().segments()).extracting(Segment::designator).containsExactly("LH400", "LH401");
        assertThat(back.itinerary().origin()).isEqualTo("FRA");
        assertThat(back.itinerary().destination()).isEqualTo("FRA");
    }

    @Test
    void theTaxesComeBackSplitTheWayTheyWentIn() {
        Offer back = throughTheDatabase(anOffer().build());
        assertThat(back.breakdown().taxTotal()).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(back.breakdown().surchargeTotal()).isEqualTo(Money.of("180.00", "EUR"));
    }

    @Test
    void aQuotedTotalThatDisagreesSurvivesStorage() {
        Offer disagreeing = anOffer()
                .breakdown(new PriceBreakdown(
                        Money.of("840.00", "EUR"),
                        List.of(new AppliedTax("DE", Money.of("70.83", "EUR"), "FRA")),
                        Money.of("999.00", "EUR")))
                .build();
        Offer back = throughTheDatabase(disagreeing);
        assertThat(back.breakdown().quotedTotal()).contains(Money.of("999.00", "EUR"));
        assertThat(back.breakdown().addsUp()).isFalse();
    }

    @Test
    void anOfferWithNoQuotedTotalComesBackWithoutOne() {
        Offer plain = anOffer()
                .breakdown(new PriceBreakdown(Money.of("840.00", "EUR")))
                .build();
        Offer back = throughTheDatabase(plain);
        assertThat(back.breakdown().quotedTotal()).isEmpty();
        assertThat(back.breakdown().addsUp()).isTrue();
    }

    @Test
    void theFingerprintIsWrittenAndSurvives() {
        Offer original = anOffer().build();
        session.add(offerToRow(original));
        session.commit();
        ObservationRow row = session.one(ObservationRow.class);
        assertThat(row.getFingerprint()).isEqualTo(original.fingerprint());
        assertThat(rowToOffer(row).fingerprint()).isEqualTo(original.fingerprint());
    }

    @Test
    void theSeriesKeyIsBuiltTheOneWay() {
        session.add(offerToRow(anOffer().build()));
        session.commit();
        ObservationRow row = session.one(ObservationRow.class);
        assertThat(row.getSeriesKey()).isEqualTo(seriesKeyOf("FRA", "FRA", LocalDate.of(2025, 7, 4), "Y"));
    }

    @Test
    void aBusinessCabinOfferKeepsItsCabin() {
        Offer back = throughTheDatabase(anOffer().cabin(Cabin.BUSINESS).build());
        assertThat(back.cabin()).isSameAs(Cabin.BUSINESS);
    }

    @Test
    void anOfferWithNothingOptionalStillRoundTrips() {
        Offer bare = new Offer(
                "ndc",
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                new PriceBreakdown(Money.of("420.00", "EUR")),
                List.of("KLWAP7RT"),
                SEEN);
        Offer back = throughTheDatabase(bare);
        assertThat(back.seats()).isEmpty();
        assertThat(back.baggage().isStated()).isFalse();
        assertThat(back.refundable()).isEmpty();
        assertThat(back.validatingCarrier()).isEmpty();
    }
}
