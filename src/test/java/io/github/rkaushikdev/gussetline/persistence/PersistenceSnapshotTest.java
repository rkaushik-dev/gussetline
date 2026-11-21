package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceSnapshotTest {

    private static final LocalDate ON = LocalDate.of(2025, 8, 1);

    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
    }

    private static Fare fare() {
        return fare("LH", "JFK", "KLWAP7RT");
    }

    private static Fare fare(String carrier, String destination, String basis) {
        return Fare.builder()
                .carrier(carrier)
                .origin("FRA")
                .destination(destination)
                .fareBasis(basis)
                .amount(Money.of("420.00", "EUR"))
                .owrt(OwRt.ROUND_TRIP_HALF)
                .globalIndicator(GlobalIndicator.AT)
                .ruleNumber("0100")
                .routingNumber("0011")
                .tariffNumber("1")
                .fareType(FareType.PUBLISHED)
                .effective(LocalDate.of(2025, 1, 1))
                .discontinue(LocalDate.of(2025, 12, 31))
                .firstTravel(LocalDate.of(2025, 1, 1))
                .lastTravel(LocalDate.of(2025, 12, 31))
                .build();
    }

    /** File the fares under one load, a round trip combinability record for each, and one rule. */
    private void stocked(Fare... fares) {
        try (UnitOfWork work = database.unit()) {
            LoadRow row = work.tariff()
                    .openLoad(
                            fares.length > 0 ? fares[0].carrier() : "LH",
                            "test.dat",
                            "d".repeat(32),
                            LocalDateTime.of(2025, 3, 1, 6, 0),
                            LocalDate.of(2025, 3, 1));
            work.tariff().addFares(row, List.of(fares));
            for (Fare filed : fares) {
                work.combinability()
                        .save(Combinability.builder(filed.carrier(), filed.fareBasis())
                                .roundTrip(true)
                                .build());
            }
            work.rules().save(new RuleSet("LH", "0100", List.of(new MinimumStay(3)), "1"));
            work.commit();
        }
    }

    private static Itinerary anItinerary() {
        return new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-08-04 10:25", "2025-08-04 13:05")));
    }

    private static Offer anOffer(String destination) {
        return Offer.builder()
                .source("gds")
                .itinerary(new Itinerary(
                        List.of(segment("LH", "400", "FRA", destination, "2025-08-04 10:25", "2025-08-04 13:05"))))
                .breakdown(new PriceBreakdown(Money.of("620.00", "EUR")))
                .fareBases(List.of("KLWAP7RT"))
                .observedAt(LocalDateTime.of(2025, 8, 1, 6, 0))
                .validatingCarrier("LH")
                .build();
    }

    private Tariffs taken(List<String> carriers, List<String> points, LocalDate on) {
        try (UnitOfWork work = database.unit()) {
            return Snapshot.take(work, carriers, points, on);
        }
    }

    @Test
    void aSnapshotOfAnEmptyDeskHoldsNothingButTheMileages() {
        Tariffs taken = taken(List.of("LH"), List.of("FRA", "JFK"), ON);
        assertThat(taken.fares().size()).isZero();
        assertThat(taken.combinability().size()).isZero();
        // Not from the database. An empty mileage table is a check that fails on a city pair it has
        // never heard of, which is a different failure.
        assertThat(taken.miles().size()).isPositive();
    }

    @Test
    void aSnapshotFindsAFareByTheCityTheTariffFilesItUnder() {
        // A command that did not fold its pairs into cities would find nothing here, and read the
        // desk's own tariff as a market with nothing filed.
        stocked(fare());

        assertThat(taken(List.of("LH"), List.of("FRA", "JFK"), ON).fares().size())
                .isEqualTo(1);
    }

    @Test
    void aSnapshotAskedForAnAirportFindsTheCityFare() {
        stocked(fare());

        assertThat(taken(List.of("LH"), List.of("FRA", "EWR"), ON).fares().size())
                .isEqualTo(1);
    }

    @Test
    void aSnapshotCarriesTheCombinabilityAndTheRules() {
        stocked(fare());

        Tariffs taken = taken(List.of("LH"), List.of("FRA", "JFK"), ON);
        assertThat(taken.combinability().get("LH", "KLWAP7RT")).isPresent();
        Optional<RuleSet> found = taken.rules().find("LH", "0100");
        assertThat(found).isPresent();
        assertThat(found.get().has(Category.MINIMUM_STAY)).isTrue();
    }

    @Test
    void aSnapshotLeavesOutACarrierNobodyAskedFor() {
        stocked(fare(), fare("UA", "JFK", "KUA"));

        assertThat(taken(List.of("LH"), List.of("FRA", "JFK"), ON)
                        .combinability()
                        .get("UA", "KUA"))
                .isEmpty();
    }

    @Test
    void aSnapshotLeavesOutAMarketNobodyAskedFor() {
        stocked(fare(), fare("LH", "ORD", "KORD"));

        assertThat(taken(List.of("LH"), List.of("FRA", "JFK"), ON).fares().size())
                .isEqualTo(1);
    }

    @Test
    void aSnapshotAppliesTheSaleWindowInTheQuery() {
        stocked(fare());

        assertThat(taken(List.of("LH"), List.of("FRA", "JFK"), LocalDate.of(2026, 6, 1))
                        .fares()
                        .size())
                .isZero();
    }

    @Test
    void noCarriersAtAllReadsNothing() {
        stocked(fare());

        assertThat(taken(List.of(), List.of("FRA", "JFK"), ON).fares().size()).isZero();
    }

    @Test
    void anItineraryWorksOutItsOwnCarriers() {
        stocked(fare());

        Tariffs taken;
        try (UnitOfWork work = database.unit()) {
            taken = Snapshot.forItinerary(work, anItinerary(), ON);
        }
        assertThat(taken.fares().size()).isEqualTo(1);
    }

    @Test
    void namingACarrierNarrowsIt() {
        stocked(fare(), fare("UA", "JFK", "KUA"));

        Tariffs taken;
        try (UnitOfWork work = database.unit()) {
            taken = Snapshot.forItinerary(work, anItinerary(), ON, "UA");
        }
        assertThat(taken.combinability().get("UA", "KUA")).isPresent();
        assertThat(taken.combinability().get("LH", "KLWAP7RT")).isEmpty();
    }

    @Test
    void aRunOfOffersReadsOneSnapshotForAllOfThem() {
        stocked(fare(), fare("LH", "ORD", "KORD"));

        Tariffs taken;
        try (UnitOfWork work = database.unit()) {
            taken = Snapshot.forOffers(work, List.of(anOffer("JFK"), anOffer("ORD")), ON);
        }
        assertThat(taken.fares().size()).isEqualTo(2);
    }

    @Test
    void aRunOfNoOffersReadsNothing() {
        Tariffs taken;
        try (UnitOfWork work = database.unit()) {
            taken = Snapshot.forOffers(work, List.of(), ON);
        }
        assertThat(taken.fares().size()).isZero();
    }
}
