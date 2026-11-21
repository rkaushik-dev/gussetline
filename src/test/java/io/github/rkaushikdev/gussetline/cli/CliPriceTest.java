package io.github.rkaushikdev.gussetline.cli;

import static io.github.rkaushikdev.gussetline.cli.Desk.OPERATOR_CAN_FIX;
import static io.github.rkaushikdev.gussetline.cli.Desk.exitCodeFor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.load.CombinabilityFeed;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliPriceTest {

    private static final String TODAY = "2025-07-22";
    private static final String OUT = "FRA LH400 K 04AUG JFK";
    private static final String RETURN = "FRA LH400 K 04AUG JFK LH401 K 18AUG FRA";

    @TempDir
    Path tmp;

    private String env;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        env = Shell.fileUrl(tmp);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", tmp.resolve("raw").toString());
        Shell.createSchema(env);
    }

    /** A fare from Frankfurt to New York, half a round trip, on sale and flyable through 2025. */
    private static Fare.Builder fare() {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KLWAP7RT")
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
                .lastTravel(LocalDate.of(2025, 12, 31));
    }

    private static Fare.Builder oneWay(String basis, String amount) {
        return fare().owrt(OwRt.ONE_WAY_ONLY).fareBasis(basis).amount(Money.of(amount, "EUR"));
    }

    private static Fare back() {
        return fare().origin("JFK").destination("FRA").build();
    }

    private static void load(String url, Fare... fares) {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            LoadRow row = work.tariff()
                    .openLoad(
                            fares[0].carrier(),
                            "test.dat",
                            "d".repeat(32),
                            LocalDateTime.of(2025, 3, 1, 6, 0),
                            LocalDate.of(2025, 3, 1));
            work.tariff().addFares(row, List.of(fares));
            work.commit();
        }
    }

    private static void combinability(String url, String basis, String carrier) {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            work.combinability()
                    .save(Combinability.builder(carrier, basis)
                            .roundTrip(true)
                            .circleTrip(true)
                            .openJaw(true)
                            .endOnEnd(true)
                            .build());
            work.commit();
        }
    }

    private String categoryTenFile(Map<String, Object> flags) {
        Map<String, Object> values = new LinkedHashMap<>(flags);
        values.put("carrier", "LH");
        values.put("fare_basis", "KLWAP7RT");
        Path path = tmp.resolve("cat10.dat");
        try {
            Files.writeString(path, CombinabilityFeed.COMBINABILITY.write(values), StandardCharsets.US_ASCII);
        } catch (IOException unwritable) {
            throw new AssertionError(unwritable);
        }
        return path.toString();
    }

    @Test
    void thePriceCommandShowsUpInTheHelp() {
        assertThat(runner.run("--help").output()).contains("price");
    }

    @Test
    void anEntryIsEchoedBackTheWayItWasRead() {
        load(env, fare().build());
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.output()).contains(RETURN);
    }

    @Test
    void aRoundTripPricesOffTwoHalves() {
        load(env, fare().build(), back());
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.output()).contains("KLWAP7RT", "base fare 840 EUR");
    }

    @Test
    void aFareWithNoCombinabilityFiledPricesAndSaysItCannotBeSold() {
        // Still printed. The desk is looking at it because a supplier quoted something, and a blank
        // screen answers nothing.
        load(env, fare().build(), back());
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.code()).isEqualTo(1);
        assertThat(reply.output()).contains("cannot be issued", "no combinability filed");
    }

    @Test
    void theFareCalculationLineIsPrinted() {
        load(env, fare().build(), back());
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.output()).contains("FRA LH NYC", "NUC");
    }

    @Test
    void anEntryWithNoFareFiledSaysSoRatherThanPricing() {
        load(env, fare().destination("ORD").build());
        Shell.Result reply = runner.run("price", OUT, "--on", TODAY);
        assertThat(reply.code()).isNotZero();
    }

    @Test
    void aFareOutsideItsSaleWindowIsNotUsed() {
        load(env, fare().build());
        Shell.Result reply = runner.run("price", OUT, "--on", "2026-03-01");
        assertThat(reply.code()).isNotZero();
    }

    @Test
    void theCarrierCanBePinned() {
        load(env, oneWay("KOW", "420.00").build());
        load(env, oneWay("KCHEAP", "300.00").carrier("UA").build());
        Shell.Result reply = runner.run("price", OUT, "--on", TODAY, "--carrier", "LH");
        assertThat(reply.output()).contains("KOW");
        assertThat(reply.output()).doesNotContain("KCHEAP");
    }

    @Test
    void theTrailIsOffUnlessItIsAskedFor() {
        load(env, oneWay("KOW", "420.00").build());
        Shell.Result quiet = runner.run("price", OUT, "--on", TODAY);
        Shell.Result loud = runner.run("price", OUT, "--on", TODAY, "--why");
        assertThat(loud.output().length()).isGreaterThan(quiet.output().length());
        assertThat(loud.output()).contains("selection");
    }

    @Test
    void theBreaksCanBeGiven() {
        // Break after the first sector, so the connection at Munich becomes a fare break rather than
        // a point the through fare covers.
        load(
                env,
                oneWay("KSHORT", "90.00")
                        .destination("MUC")
                        .globalIndicator(GlobalIndicator.DO)
                        .build());
        load(env, oneWay("KLONG", "600.00").origin("MUC").build());
        String entry = "FRA LH100 K 04AUG MUC LH400 K 04AUG JFK";
        Shell.Result reply = runner.run("price", entry, "--on", TODAY, "--breaks", "0");
        assertThat(reply.output()).contains("KSHORT", "KLONG");
    }

    @Test
    void aBreakPastTheEndIsRefused() {
        load(env, fare().build());
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY, "--breaks", "9");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(ValidationError.class);
    }

    @Test
    void breaksThatAreNotNumbersAreAUsageError() {
        load(env, fare().build());
        Shell.Result reply = runner.run("price", OUT, "--on", TODAY, "--breaks", "middle");
        assertThat(reply.code()).isEqualTo(2);
        assertThat(reply.output()).contains("element numbers");
    }

    @Test
    void anEntryThatWillNotReadIsRefusedWithTheToken() {
        Shell.Result reply = runner.run("price", "FRA LUFTHANSA K 04AUG JFK", "--on", TODAY);
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(ValidationError.class);
        RuntimeException refused = reply.failure().orElseThrow();
        assertThat(refused.getMessage()).contains("not a flight");
        assertThat(exitCodeFor(refused)).isEqualTo(OPERATOR_CAN_FIX);
    }

    @Test
    void aOneWayFarePricesAOneWayEntry() {
        load(env, oneWay("KOW", "620.00").build());
        Shell.Result reply = runner.run("price", OUT, "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("KOW");
    }

    @Test
    void theCheapestFareFiledIsTheOneUsed() {
        load(env, oneWay("KOW", "620.00").build(), oneWay("KOWSALE", "480.00").build());
        Shell.Result reply = runner.run("price", OUT, "--on", TODAY);
        assertThat(reply.output()).contains("KOWSALE", "519.48");
    }

    @Test
    void theSellingCurrencyComesOffTheSettings() {
        load(env, oneWay("KOW", "620.00").build());
        Shell.Result reply =
                runner.exporting("GUSSETLINE_SELLING_CURRENCY", "GBP").run("price", OUT, "--on", TODAY);
        assertThat(reply.output()).contains("GBP");
    }

    @Test
    void aQuietRunStillPrintsTheRefusals() {
        load(env, fare().destination("ORD").build());
        Shell.Result reply = runner.run("--quiet", "price", OUT, "--on", TODAY);
        assertThat(reply.output()).doesNotContain("base fare");
    }

    @Test
    void aRoundTripWithCategory10FiledCanBeIssued() {
        load(env, fare().build(), back());
        combinability(env, "KLWAP7RT", "LH");
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).doesNotContain("cannot be issued");
        assertThat(reply.output()).contains("base fare 840 EUR");
    }

    @Test
    void aRecordFiledForAnotherCarrierDoesNotHelp() {
        load(env, fare().build(), back());
        combinability(env, "KLWAP7RT", "UA");
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.code()).isEqualTo(1);
        assertThat(reply.output()).contains("no combinability filed");
    }

    @Test
    void aRecordFiledForAnotherFareBasisDoesNotHelp() {
        load(env, fare().build(), back());
        combinability(env, "MLWAP7RT", "LH");
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.code()).isEqualTo(1);
    }

    @Test
    void theLoadGroupNowTakesACategory10File() {
        String path = categoryTenFile(Map.of("round_trip", "Y", "circle_trip", "Y", "end_on_end", "Y"));
        Shell.Result reply = runner.run("load", "combinability", path);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("kept 1 records");
    }

    @Test
    void aLoadedCategory10FileMakesTheRoundTripIssuable() {
        load(env, fare().build(), back());
        String path = categoryTenFile(Map.of("round_trip", "Y"));
        runner.run("load", "combinability", path);
        Shell.Result reply = runner.run("price", RETURN, "--on", TODAY);
        assertThat(reply.code()).isZero();
    }
}
