package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.persistence.TestDatabase.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.models.AirportRow;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigAndUnitofworkTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 15, 9, 0);

    private final List<Database> opened = new ArrayList<>();

    @AfterEach
    void closeEveryDatabase() {
        opened.forEach(Database::close);
    }

    /** A database of the test's own in memory, closed after the test. */
    private Database inMemory() {
        Database database = Database.inMemory();
        opened.add(database);
        return database;
    }

    private static Offer anOffer() {
        return new Offer(
                "gds",
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                new PriceBreakdown(Money.of("840.00", "EUR")),
                List.of("KLWAP7RT"),
                SEEN);
    }

    @Test
    void theDefaultsAreReadableAsValuesOnTheirOwn() {
        // Every fallback in the reader below reads these, so they have to be plain values.
        assertThat(Settings.DEFAULT_SELLING_CURRENCY).isEqualTo("EUR");
        assertThat(Settings.DEFAULT_DAILY_QUOTA).isEqualTo(1000);
        assertThat(Settings.DEFAULT_HONOUR_ROBOTS).isTrue();
    }

    @Test
    void theDefaultsAreAWorkingConfiguration() {
        Settings settings = new Settings();
        assertThat(settings.isH2()).isTrue();
        assertThat(settings.sellingCurrency()).isEqualTo("EUR");
        assertThat(settings.dailyQuota()).isEqualTo(1000);
    }

    @Test
    void anEmptyEnvironmentGivesTheDefaults() {
        assertThat(Settings.fromEnv(Map.of())).isEqualTo(new Settings());
    }

    @Test
    void aSettingIsReadUnderItsPrefix() {
        Settings settings = Settings.fromEnv(Map.of("GUSSETLINE_SELLING_CURRENCY", "gbp"));
        assertThat(settings.sellingCurrency()).isEqualTo("GBP");
    }

    @Test
    void aNumberThatIsNotOneNamesTheSetting() {
        ConfigError caught = catchThrowableOfType(
                ConfigError.class, () -> Settings.fromEnv(Map.of("GUSSETLINE_DAILY_QUOTA", "lots")));
        assertThat(caught.details()).containsEntry("setting", "GUSSETLINE_DAILY_QUOTA");
    }

    @Test
    void aRateThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> Settings.fromEnv(Map.of("GUSSETLINE_RATE_PER_SECOND", "fast")))
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void anAmountThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> Settings.fromEnv(Map.of("GUSSETLINE_WATCH_THRESHOLD", "a lot")))
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void aYesOrNoIsReadInEverySpelling() {
        for (String text : List.of("1", "yes", "true", "on", "YES")) {
            assertThat(Settings.fromEnv(Map.of("GUSSETLINE_DRY_RUN", text)).dryRun())
                    .as(text)
                    .isTrue();
        }
        for (String text : List.of("0", "no", "false", "off")) {
            assertThat(Settings.fromEnv(Map.of("GUSSETLINE_DRY_RUN", text)).dryRun())
                    .as(text)
                    .isFalse();
        }
    }

    @Test
    void somethingThatIsNeitherAYesNorANoIsRefused() {
        assertThatThrownBy(() -> Settings.fromEnv(Map.of("GUSSETLINE_HONOUR_ROBOTS", "maybe")))
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void aQuotaAndARateHaveToMakeSense() {
        assertThatThrownBy(() -> Settings.builder().dailyQuota(-1).build()).isInstanceOf(ConfigError.class);
        assertThatThrownBy(() -> Settings.builder().ratePerSecond(0).build()).isInstanceOf(ConfigError.class);
        assertThatThrownBy(() -> Settings.builder().burst(0).build()).isInstanceOf(ConfigError.class);
        assertThatThrownBy(() -> Settings.builder().retryAttempts(0).build()).isInstanceOf(ConfigError.class);
    }

    @Test
    void aCreditIsWorthBetweenNothingAndCash() {
        assertThatThrownBy(() ->
                        Settings.builder().creditDiscount(new BigDecimal("1.5")).build())
                .isInstanceOf(ConfigError.class);
        assertThat(Settings.builder().creditDiscount(BigDecimal.ONE).build().creditDiscount())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void aCurrencyCodeIsThreeLetters() {
        assertThatThrownBy(() -> Settings.builder().sellingCurrency("EURO").build())
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void theArchiveRootComesBackAsAPath() {
        Settings settings = Settings.fromEnv(Map.of("GUSSETLINE_ARCHIVE_ROOT", "/tmp/raw"));
        assertThat(settings.archiveRoot()).isEqualTo(Path.of("/tmp/raw"));
    }

    @Test
    void anInMemoryUrlIsRecognised() {
        assertThat(new Settings().withDatabase(Database.IN_MEMORY).inMemory()).isTrue();
        assertThat(new Settings().inMemory()).isFalse();
    }

    @Test
    void pointingAtAnotherDatabaseKeepsEveryOtherSetting() {
        Settings settings =
                Settings.builder().sellingCurrency("GBP").dailyQuota(42).build();
        Settings moved = settings.withDatabase(Database.IN_MEMORY);
        assertThat(moved.sellingCurrency()).isEqualTo("GBP");
        assertThat(moved.dailyQuota()).isEqualTo(42);
        assertThat(moved.inMemory()).isTrue();
    }

    @Test
    void settingsReadBackInOneLine() {
        assertThat(new Settings().withDatabase(Database.IN_MEMORY))
                .hasToString("gussetline against memory, selling in EUR");
    }

    @Test
    void aUnitThatCommitsKeepsItsWork() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            work.airports().save(Geography.airport("FRA"));
            work.commit();
        }
        try (UnitOfWork work = database.unit()) {
            assertThat(work.airports().count()).isEqualTo(1);
        }
    }

    @Test
    void aUnitThatDoesNotCommitKeepsNothing() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            work.airports().save(Geography.airport("FRA"));
        }
        try (UnitOfWork work = database.unit()) {
            assertThat(work.airports().count()).isZero();
        }
    }

    @Test
    void aUnitThatThrowsRollsBack() {
        Database database = inMemory();
        assertThatThrownBy(() -> {
                    try (UnitOfWork work = database.unit()) {
                        work.airports().save(Geography.airport("FRA"));
                        work.flush();
                        throw new IllegalStateException("something went wrong halfway");
                    }
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("something went wrong halfway");
        try (UnitOfWork work = database.unit()) {
            assertThat(work.airports().count()).isZero();
        }
    }

    @Test
    void everyRepositorySharesTheOneSession() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            assertThat(work.airports().session()).isSameAs(work.observations().session());
            assertThat(work.tariff().session()).isSameAs(work.watches().session());
        }
    }

    @Test
    void theRepositoriesSeeEachOthersUncommittedWork() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            work.airports().save(Geography.airport("FRA"));
            work.flush();
            AirportRow row = work.session()
                    .createQuery("select a from AirportRow a", AirportRow.class)
                    .getSingleResult();
            assertThat(row.getCode()).isEqualTo("FRA");
        }
    }

    @Test
    void anOfferWrittenThroughAUnitReadsBack() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            work.observations().save(anOffer());
            work.commit();
        }
        try (UnitOfWork work = database.unit()) {
            assertThat(work.observations().count()).isEqualTo(1);
        }
    }

    @Test
    void twoUnitsDoNotShareState() {
        Database database = inMemory();
        try (UnitOfWork first = database.unit()) {
            first.airports().save(Geography.airport("FRA"));
            try (UnitOfWork second = database.unit()) {
                assertThat(second.airports().count()).isZero();
            }
        }
    }

    @Test
    void aDatabaseReadsBackWithItsUrl() {
        Database database = inMemory();
        assertThat(database.toString()).contains("memory");
    }

    @Test
    void foreignKeysAreEnforced() {
        Database database = inMemory();
        try (UnitOfWork work = database.unit()) {
            CouponRow orphan = new CouponRow();
            orphan.setTicketId(999);
            orphan.setPosition(0);
            orphan.setCarrier("LH");
            orphan.setFlightNumber("400");
            orphan.setOrigin("FRA");
            orphan.setDestination("JFK");
            orphan.setDeparture(SEEN);
            orphan.setBookingClass("K");
            orphan.setFareBasis("KLWAP7RT");
            assertRefused(() -> {
                work.session().persist(orphan);
                work.commit();
            });
        }
    }

    @Test
    void aDatabaseCanBeDisposedOf() {
        Database database = inMemory();
        database.dispose();
        assertThat(((HikariDataSource) database.engine().dataSource()).isClosed())
                .isTrue();
    }
}
