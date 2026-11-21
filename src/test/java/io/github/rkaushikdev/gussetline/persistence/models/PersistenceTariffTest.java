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

class PersistenceTariffTest {

    private static final LocalDateTime LOADED = LocalDateTime.of(2025, 6, 3, 6, 0);

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static LoadRow load() {
        LoadRow row = new LoadRow();
        row.setCarrier("LH");
        row.setFilename("LH-20250603.dat");
        row.setDigest("a".repeat(32));
        row.setLoadedAt(LOADED);
        row.setEffectiveFrom(LocalDate.of(2025, 6, 1));
        row.setRowCount(2);
        return row;
    }

    private static FareRow fare() {
        FareRow row = new FareRow();
        row.setCarrier("LH");
        row.setOrigin("FRA");
        row.setDestination("NYC");
        row.setFareBasis("KLWAP7RT");
        row.setAmount(new BigDecimal("420.00"));
        row.setCurrency("EUR");
        row.setOwrt("2");
        row.setGlobalIndicator("AT");
        row.setRuleNumber("1100");
        row.setRoutingNumber("0000");
        row.setEffective(LocalDate.of(2025, 1, 1));
        row.setDiscontinue(LocalDate.of(2025, 12, 31));
        row.setFirstTravel(LocalDate.of(2025, 1, 1));
        row.setLastTravel(LocalDate.of(2025, 12, 31));
        return row;
    }

    private static FareRow fare(String amount) {
        FareRow row = fare();
        row.setAmount(new BigDecimal(amount));
        return row;
    }

    private static RuleRow rule() {
        RuleRow row = new RuleRow();
        row.setCarrier("LH");
        row.setNumber("1100");
        return row;
    }

    private static ProvisionRow provision(int category, String kind) {
        ProvisionRow row = new ProvisionRow();
        row.setCategory(category);
        row.setKind(kind);
        return row;
    }

    private static ProvisionRow minimumStay() {
        ProvisionRow row = provision(6, "minimum_stay");
        row.setNumberOne(7);
        return row;
    }

    private static CombinabilityRow combinability() {
        CombinabilityRow row = new CombinabilityRow();
        row.setCarrier("LH");
        row.setFareBasis("KLWAP7RT");
        return row;
    }

    private static RoutingRow hop(String origin, String destination) {
        RoutingRow row = new RoutingRow();
        row.setCarrier("LH");
        row.setNumber("0101");
        row.setOrigin(origin);
        row.setDestination(destination);
        return row;
    }

    @Test
    void aLoadCarriesItsFares() {
        LoadRow load = load();
        load.addFare(fare());
        FareRow other = fare("380.00");
        other.setFareBasis("QLWAP14RT");
        load.addFare(other);
        session.add(load);
        session.commit();
        LoadRow found = session.one(LoadRow.class);
        assertThat(found.getFares()).hasSize(2);
        assertThat(found).hasToString("<Load LH LH-20250603.dat 2 rows>");
    }

    @Test
    void retiringALoadTakesItsFaresWithIt() {
        LoadRow load = load();
        load.addFare(fare());
        session.add(load);
        session.commit();
        session.delete(load);
        session.commit();
        assertThat(session.all(FareRow.class)).isEmpty();
    }

    @Test
    void twoLoadsOfTheSameMarketBothSurvive() {
        LoadRow first = load();
        first.addFare(fare("420.00"));
        LoadRow second = load();
        second.setDigest("b".repeat(32));
        second.setLoadedAt(LocalDateTime.of(2025, 6, 10, 6, 0));
        second.addFare(fare("440.00"));
        session.add(first, second);
        session.commit();
        assertThat(session.all(FareRow.class).stream().map(FareRow::getAmount).sorted())
                .containsExactly(new BigDecimal("420.00"), new BigDecimal("440.00"));
    }

    @Test
    void aLoadCanBeMarkedRetiredWithoutDeletingIt() {
        LoadRow load = load();
        session.add(load);
        session.commit();
        load.setRetired(true);
        session.commit();
        assertThat(session.one("select l from LoadRow l where l.retired", LoadRow.class))
                .isSameAs(load);
    }

    @Test
    void aFareReadsBackAsItsMarketAndBasis() {
        LoadRow load = load();
        load.addFare(fare());
        session.add(load);
        session.commit();
        assertThat(session.one(FareRow.class)).hasToString("<Fare LH FRANYC KLWAP7RT>");
    }

    @Test
    void theMarketIndexIsDeclared() {
        assertThat(Arrays.stream(FareRow.class.getAnnotation(Table.class).indexes())
                        .map(Index::name))
                .contains("ix_fares_market", "ix_fares_lookup");
    }

    @Test
    void aRuleCarriesItsProvisions() {
        RuleRow rule = rule();
        rule.addProvision(minimumStay());
        ProvisionRow penalty = provision(16, "penalty");
        penalty.setAmount(new BigDecimal("50.00"));
        rule.addProvision(penalty);
        session.add(rule);
        session.commit();
        RuleRow found = session.one(RuleRow.class);
        assertThat(found.getProvisions()).hasSize(2);
        assertThat(found).hasToString("<Rule LH 1100>");
        assertThat(found.getProvisions().get(0)).hasToString("<Provision cat 6 minimum_stay>");
    }

    @Test
    void deletingARuleTakesItsProvisions() {
        RuleRow rule = rule();
        rule.addProvision(minimumStay());
        session.add(rule);
        session.commit();
        session.delete(rule);
        session.commit();
        assertThat(session.all(ProvisionRow.class)).isEmpty();
    }

    @Test
    void theSameRuleNumberTwiceOnOneCarrierIsRefused() {
        session.add(rule());
        assertRefused(() -> {
            session.add(rule());
            session.commit();
        });
    }

    @Test
    void theSameRuleNumberOnAnotherTariffIsADifferentRule() {
        RuleRow first = rule();
        first.setTariffNumber("1");
        RuleRow eighth = rule();
        eighth.setTariffNumber("8");
        session.add(first, eighth);
        session.commit();
        assertThat(session.all(RuleRow.class)).hasSize(2);
    }

    @Test
    void aProvisionAmountKeepsItsDecimals() {
        RuleRow rule = rule();
        ProvisionRow penalty = provision(16, "penalty");
        penalty.setAmount(new BigDecimal("12.999"));
        penalty.setCurrency("KWD");
        rule.addProvision(penalty);
        session.add(rule);
        session.commit();
        assertThat(session.one(ProvisionRow.class).getAmount()).contains(new BigDecimal("12.999"));
    }

    @Test
    void aSurchargeStoresAndReadsBack() {
        SurchargeRow row = new SurchargeRow();
        row.setCarrier("LH");
        row.setOrigin("FRA");
        row.setDestination("NYC");
        row.setAmount(new BigDecimal("30.00"));
        row.setCurrency("EUR");
        row.setFirstTravel(LocalDate.of(2025, 1, 1));
        row.setLastTravel(LocalDate.of(2025, 12, 31));
        session.add(row);
        session.commit();
        SurchargeRow found = session.one(SurchargeRow.class);
        assertThat(found.getScope()).isEqualTo("component");
        assertThat(found).hasToString("<Surcharge Q FRANYC>");
    }

    @Test
    void combinabilityDefaultsToRefusingEverything() {
        session.add(combinability());
        session.commit();
        CombinabilityRow found = session.one(CombinabilityRow.class);
        assertThat(found.isRoundTrip()).isFalse();
        assertThat(found.isEndOnEnd()).isFalse();
        assertThat(found.isSameCarrierOnly()).isTrue();
        assertThat(found).hasToString("<Combinability LH KLWAP7RT>");
    }

    @Test
    void theSameFareBasisTwiceOnOneCarrierIsRefused() {
        session.add(combinability());
        assertRefused(() -> {
            session.add(combinability());
            session.commit();
        });
    }

    @Test
    void aRoutingHopStoresItsCarriersAsText() {
        RoutingRow row = hop("FRA", "NYC");
        row.setCarriers("LH/UA");
        session.add(row);
        session.commit();
        RoutingRow found = session.one(RoutingRow.class);
        assertThat(found.getCarriers()).isEqualTo("LH/UA");
        assertThat(found.isBidirectional()).isTrue();
        assertThat(found).hasToString("<Routing LH 0101 FRA-NYC>");
    }

    @Test
    void aRoutingMapIsManyRowsUnderOneNumber() {
        for (List<String> pair : List.of(List.of("FRA", "LON"), List.of("LON", "NYC"))) {
            session.add(hop(pair.get(0), pair.get(1)));
        }
        session.commit();
        assertThat(session.select("select r from RoutingRow r where r.number = '0101'", RoutingRow.class))
                .hasSize(2);
    }
}
