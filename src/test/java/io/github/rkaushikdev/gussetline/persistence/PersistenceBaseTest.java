package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.persistence.TestDatabase.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.AirportRow;
import io.github.rkaushikdev.gussetline.persistence.models.CarrierRow;
import io.github.rkaushikdev.gussetline.persistence.models.InterlineRow;
import io.github.rkaushikdev.gussetline.persistence.models.MileageRow;
import io.github.rkaushikdev.gussetline.persistence.models.RateRow;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceBaseTest {

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    /** The table name each mapped row declares, beside the indexes it declares. */
    private static List<Table> declaredTables() {
        return TestDatabase.engine().entityManagerFactory().getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .map(type -> type.getAnnotation(Table.class))
                .toList();
    }

    private static RateRow rate(String table, String currency, String unitsPerNuc) {
        RateRow row = new RateRow();
        row.setTableName(table);
        row.setCurrency(currency);
        row.setEffective(LocalDate.of(2025, 1, 1));
        row.setUnitsPerNuc(new BigDecimal(unitsPerNuc));
        return row;
    }

    private static AirportRow airport() {
        AirportRow row = new AirportRow();
        row.setCode("FRA");
        row.setName("Frankfurt am Main");
        row.setCity("FRA");
        row.setCountry("DE");
        row.setLatitude(50.033);
        row.setLongitude(8.570);
        row.setUtcOffsetMinutes(60);
        return row;
    }

    private static CarrierRow carrier() {
        CarrierRow row = new CarrierRow();
        row.setCode("LH");
        row.setName("Lufthansa");
        row.setCountry("DE");
        return row;
    }

    private static InterlineRow interline(String left, String right) {
        InterlineRow row = new InterlineRow();
        row.setLeftCarrier(left);
        row.setRightCarrier(right);
        return row;
    }

    private static MileageRow mileage(String origin, String destination, int miles) {
        MileageRow row = new MileageRow();
        row.setOrigin(origin);
        row.setDestination(destination);
        row.setMiles(miles);
        return row;
    }

    /**
     * Read from the migrated schema, since a primary key's name is not something the mapping can
     * declare. Every primary key, unique constraint and foreign key there carries the name the
     * convention makes from its table and columns, and none carries a name the database made up.
     */
    @Test
    void everyConstraintIsNamedByTheConvention() {
        Catalog catalog = new Catalog(TestDatabase.engine().dataSource());
        Set<String> names = new TreeSet<>();
        int checked = 0;
        for (String table : catalog.tables()) {
            if (table.equals(Engine.HISTORY_TABLE)) {
                continue;
            }
            Map<String, String> referred = new HashMap<>();
            for (String key : catalog.foreignKeys(table)) {
                String[] parts = key.split(" \\| ");
                referred.put(parts[0], parts[2]);
            }
            for (String line : catalog.constraints(table)) {
                String[] parts = line.split(" \\| ");
                String name = parts[1];
                List<String> columns = Arrays.asList(parts[2].split(","));
                names.add(name);
                switch (parts[0]) {
                    case "primary key" -> assertThat(name).isEqualTo(Base.primaryKey(table));
                    case "unique" -> assertThat(name).isEqualTo(Base.unique(table, columns));
                    case "foreign key" ->
                        assertThat(name).isEqualTo(Base.foreignKey(table, columns, referred.get(name)));
                    default -> assertThat(name).as(line).startsWith(Base.check(table, ""));
                }
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(40);
        assertThat(names).contains("pk_airports");
        assertThat(names).anyMatch(name -> name.startsWith("uq_rates"));
        assertThat(names).noneMatch(name -> name.startsWith("constraint_"));
    }

    @Test
    void anIndexIsNamedByTheConvention() {
        Set<String> names = new TreeSet<>();
        for (Table table : declaredTables()) {
            for (Index index : table.indexes()) {
                names.add(index.name());
                List<String> columns = Arrays.asList(index.columnList().split(",\\s*"));
                if (columns.size() == 1) {
                    assertThat(index.name()).isEqualTo(Base.index(table.name(), columns));
                }
            }
        }
        assertThat(names).contains("ix_airports_city");
    }

    @Test
    void anAmountRoundTripsWithoutLosingADecimal() {
        session.add(rate("roe", "KWD", "0.30675"));
        session.commit();
        RateRow found = session.one(RateRow.class);
        assertThat(found.getUnitsPerNuc()).isEqualTo(new BigDecimal("0.30675"));
        assertThat(found.getUnitsPerNuc().scale()).isEqualTo(5);
    }

    @Test
    void aLongAmountSurvivesTheColumn() {
        session.add(rate("roe", "KRW", "1421.09876543"));
        session.commit();
        assertThat(session.one(RateRow.class).getUnitsPerNuc()).isEqualTo(new BigDecimal("1421.09876543"));
    }

    @Test
    void theTimestampsAreFilledInOnWrite() {
        LocalDateTime before = Stamp.now();
        session.add(carrier());
        session.commit();
        LocalDateTime after = Stamp.now();
        CarrierRow found = session.one(CarrierRow.class);
        // A stamp has no zone of its own; it is a UTC wall clock.
        assertThat(found.getCreatedAt()).isNotNull().isBetween(before, after);
    }

    @Test
    void anAwareTimestampIsConvertedRatherThanRefused() {
        AirportRow row = airport();
        row.setCreatedAt(OffsetDateTime.of(2025, 6, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2)));
        session.add(row);
        session.commit();
        AirportRow found = session.one(AirportRow.class);
        assertThat(found.getCreatedAt()).isEqualTo(LocalDateTime.of(2025, 6, 2, 10, 0));
    }

    @Test
    void updatingARowMovesItsUpdatedAt() {
        session.add(carrier());
        session.commit();
        CarrierRow found = session.one(CarrierRow.class);
        LocalDateTime first = found.getUpdatedAt();
        found.setName("Lufthansa German Airlines");
        found.setUpdatedAt(Stamp.now().plusSeconds(1));
        session.commit();
        assertThat(session.one(CarrierRow.class).getUpdatedAt()).isAfter(first);
    }

    @Test
    void anAirportStoresAndReadsBack() {
        session.add(airport());
        session.commit();
        AirportRow found = session.get(AirportRow.class, "FRA").orElseThrow();
        assertThat(found.getCity()).isEqualTo("FRA");
        assertThat(found.getUtcOffsetMinutes()).isEqualTo(60);
        assertThat(found.getSource()).isEqualTo("shipped");
        assertThat(found).hasToString("<Airport FRA in FRA>");
    }

    @Test
    void aCarrierDefaultsToUnalignedAndFullService() {
        session.add(carrier());
        session.commit();
        CarrierRow found = session.get(CarrierRow.class, "LH").orElseThrow();
        assertThat(found.getAlliance()).isEqualTo("none");
        assertThat(found.isLowCost()).isFalse();
        assertThat(found).hasToString("<Carrier LH>");
    }

    @Test
    void anInterlineAgreementIsStoredOnce() {
        session.add(interline("EK", "LH"));
        session.commit();
        InterlineRow found = session.one(InterlineRow.class);
        assertThat(found).hasToString("<Interline EK/LH>");
        assertThat(found.getSignedOn()).isEmpty();
    }

    @Test
    void theSamePairTwiceIsRefused() {
        session.add(interline("EK", "LH"));
        session.commit();
        assertRefused(() -> {
            session.add(interline("EK", "LH"));
            session.commit();
        });
    }

    @Test
    void aRateIsKeyedOnTableCurrencyAndDate() {
        session.add(rate("roe", "EUR", "0.951"));
        session.add(rate("bsr", "EUR", "0.980"));
        session.commit();
        assertThat(session.all(RateRow.class)).hasSize(2);
    }

    @Test
    void theSameRateKeyTwiceIsRefused() {
        session.add(rate("roe", "EUR", "0.951"));
        assertRefused(() -> {
            session.add(rate("roe", "EUR", "0.951"));
            session.commit();
        });
    }

    @Test
    void aMileageIsKeyedOnThePairAndTheIndicator() {
        MileageRow southern = mileage("FRA", "TYO", 5928);
        southern.setIndicator("TS");
        MileageRow pacific = mileage("FRA", "TYO", 7169);
        pacific.setIndicator("PA");
        session.add(southern, pacific);
        session.commit();
        List<MileageRow> rows = session.select("select m from MileageRow m order by m.miles", MileageRow.class);
        assertThat(rows).extracting(MileageRow::getIndicator).containsExactly("TS", "PA");
        assertThat(rows.get(0)).hasToString("<Mileage FRA-TYO 5928>");
    }

    @Test
    void aMileageWithNoIndicatorIsItsOwnRow() {
        session.add(mileage("FRA", "JFK", 3851));
        session.commit();
        assertThat(session.one(MileageRow.class).getIndicator()).isEmpty();
    }

    @Test
    void moneyIsRebuiltFromItsTwoColumns() {
        assertThat(Base.toMoney(new BigDecimal("420.00"), "EUR")).contains(Money.of("420.00", "EUR"));
    }

    @Test
    void moneyFromAHalfFilledPairIsNothing() {
        assertThat(Base.toMoney(null, "EUR")).isEmpty();
        assertThat(Base.toMoney(new BigDecimal("420.00"), null)).isEmpty();
        assertThat(Base.toMoney(new BigDecimal("420.00"), "")).isEmpty();
    }
}
