package io.github.rkaushikdev.gussetline.cli;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.audit.Audit;
import io.github.rkaushikdev.gussetline.audit.Finding;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliAuditTest {

    private static final String TODAY = "2025-07-24";
    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 7, 23, 6, 0);

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

    /** What an offer is when a test says nothing: a gateway quote of 620.00 EUR under KOW, Frankfurt to New York on the 4th of August. */
    private static Offer offer() {
        return offer("620.00", "gds", SEEN, 4);
    }

    private static Offer offer(String total) {
        return offer(total, "gds", SEEN, 4);
    }

    private static Offer offer(String total, int day) {
        return offer(total, "gds", SEEN, day);
    }

    private static Offer offer(String total, String source, LocalDateTime seen, int day) {
        String date = String.format(Locale.ROOT, "2025-08-%02d", day);
        return Offer.builder()
                .source(source)
                .itinerary(new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", date + " 10:25", date + " 13:05"))))
                .breakdown(new PriceBreakdown(Money.of(total, "EUR")))
                .fareBases(List.of("KOW"))
                .observedAt(seen)
                .validatingCarrier("LH")
                .build();
    }

    private static Fare fare() {
        return fare("620.00");
    }

    private static Fare fare(String amount) {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KOW")
                .amount(Money.of(amount, "EUR"))
                .owrt(OwRt.ONE_WAY_ONLY)
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

    private static void store(String url, List<Offer> offers, List<Fare> fares) {
        try (Database database = Shell.database(url);
                UnitOfWork work = database.unit()) {
            if (!offers.isEmpty()) {
                work.observations().saveAll(offers);
            }
            if (!fares.isEmpty()) {
                LoadRow row = work.tariff()
                        .openLoad(
                                "LH",
                                "test.dat",
                                "d".repeat(32),
                                LocalDateTime.of(2025, 3, 1, 6, 0),
                                LocalDate.of(2025, 3, 1));
                work.tariff().addFares(row, fares);
            }
            for (Fare filed : fares) {
                work.combinability()
                        .save(Combinability.builder(filed.carrier(), filed.fareBasis())
                                .roundTrip(true)
                                .build());
            }
            work.commit();
        }
    }

    @Test
    void theAuditCommandShowsUpInTheHelp() {
        assertThat(runner.run("--help").output()).contains("audit");
    }

    @Test
    void anEmptyDeskSaysThereIsNothingToAudit() {
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("nothing stored since 2025-07-01");
    }

    @Test
    void anOfferThatAgreesWithTheTariffIsCountedAndGoesNoFurther() {
        store(env, List.of(offer("620.00")), List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("1 offers since 2025-07-01", "agreed          1");
    }

    @Test
    void oneOfferOutOnItsOwnIsNoiseAndNotALetter() {
        store(env, List.of(offer("640.00")), List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("claims          0", "noise           1");
    }

    @Test
    void enoughOffersAgreeingAboutTheGapBecomesALetter() {
        // Same carrier, same market, same fare basis, same gap, three departures. That is a filing
        // error and it is one letter, not three.
        List<Offer> offers = List.of(offer("640.00", 4), offer("640.00", 5), offer("640.00", 6));
        store(env, offers, List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("claims          1", "worth a letter");
    }

    @Test
    void anOfferTheTariffCannotPriceIsKeptApartFromTheFindings() {
        // Says something about us and nothing about the supplier. Mixing the two buries thirty letters
        // under nine hundred rows of our own gaps.
        store(env, List.of(offer()), List.of());
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("not priceable   1", "claims          0");
    }

    @Test
    void theOnesWeCouldNotPriceNeverReachAGroup() {
        store(env, List.of(offer("620.00", 4), offer("620.00", 5), offer("620.00", 6)), List.of());
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("not priceable   3", "noise           0");
        assertThat(reply.output()).doesNotContain("worth a letter");
    }

    @Test
    void theWindowNarrowsWhatIsAudited() {
        store(
                env,
                List.of(
                        offer("620.00", "gds", LocalDateTime.of(2025, 7, 10, 6, 0), 4),
                        offer("620.00", "gds", LocalDateTime.of(2025, 7, 23, 6, 0), 5)),
                List.of(fare()));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-20");
        assertThat(reply.output()).contains("1 offers since 2025-07-20");
    }

    @Test
    void theWindowDefaultsToTheDayBefore() {
        store(env, List.of(offer("620.00", "gds", LocalDateTime.of(2025, 7, 23, 6, 0), 4)), List.of(fare()));
        Shell.Result reply = runner.run("audit", "--on", TODAY);
        assertThat(reply.output()).contains("1 offers since 2025-07-23");
    }

    @Test
    void theSourceCanBePinned() {
        store(env, List.of(offer("620.00", "gds", SEEN, 4), offer("620.00", "ndc", SEEN, 5)), List.of(fare()));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01", "--source", "ndc");
        assertThat(reply.output()).contains("1 offers since");
    }

    @Test
    void theLimitStopsTheRunEarly() {
        store(env, List.of(offer("620.00", 4), offer("620.00", 5), offer("620.00", 6)), List.of(fare()));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01", "--limit", "2");
        assertThat(reply.output()).contains("2 offers since");
    }

    @Test
    void theNumberOfCasesPrintedCanBeCapped() {
        List<Offer> offers = IntStream.range(0, 5)
                .mapToObj(index -> offer((640 + index * 10) + ".00", 4 + index))
                .toList();
        store(env, offers, List.of(fare()));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01", "--show", "1");
        assertThat(reply.code()).isZero();
    }

    @Test
    void aSupplierBelowOurPriceIsOurGapAndNeverAClaim() {
        List<Offer> offers = List.of(offer("500.00", 4), offer("500.00", 5), offer("500.00", 6));
        store(env, offers, List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("claims          0", "fares we do not hold, which is ours to fix");
    }

    @Test
    void aQuietRunPrintsNothingWhenAllIsWell() {
        store(env, List.of(offer("620.00")), List.of(fare("620.00")));
        Shell.Result reply = runner.run("--quiet", "audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output().strip()).isEmpty();
    }

    @Test
    void aRunIsSummarisedInTheWordsTheDailyMailUses() {
        List<Audit> made = List.of(
                new Audit(offer(), Finding.AGREES),
                new Audit(offer(), Finding.SUPPLIER_HIGH),
                new Audit(offer(), Finding.SUPPLIER_HIGH));
        assertThat(AuditCommand.summarise(made)).isEqualTo(Map.of("agreed", 1, "supplier_high", 2));
    }

    @Test
    void summarisingNothingGivesNothing() {
        assertThat(AuditCommand.summarise(List.of())).isEmpty();
    }

    /** The default offer, charged the German taxes and the fuel surcharge but not the US arrival tax. */
    private static Offer taxedOffer() {
        return Offer.builder()
                .source("gds")
                .itinerary(new Itinerary(
                        List.of(segment("LH", "400", "FRA", "JFK", "2025-08-04 10:25", "2025-08-04 13:05"))))
                .breakdown(new PriceBreakdown(
                        Money.of("620.00", "EUR"),
                        List.of(
                                AppliedTax.asSent("DE", Money.of("70.83", "EUR"), "FRA"),
                                AppliedTax.asSent("RA", Money.of("10.00", "EUR"), "FRA"),
                                AppliedTax.asSent("YQ", Money.of("180.00", "EUR"), ""))))
                .fareBases(List.of("KOW"))
                .observedAt(SEEN)
                .validatingCarrier("LH")
                .build();
    }

    @Test
    void checkingTaxesNamesTheCodeASupplierLeftOut() {
        store(env, List.of(taxedOffer()), List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01", "--check-taxes");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("1 offers with a tax out against the filing", "1  US not charged");
        assertThat(reply.output()).doesNotContain("agreed          1");
    }

    @Test
    void withoutTheOptionTheTaxesAreNotChecked() {
        store(env, List.of(taxedOffer()), List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01");
        assertThat(reply.output()).contains("agreed          1");
        assertThat(reply.output()).doesNotContain("with a tax out");
    }

    @Test
    void anOfferWithNoTaxesIsOutOnEveryFiledCode() {
        store(env, List.of(offer("620.00")), List.of(fare("620.00")));
        Shell.Result reply = runner.run("audit", "--on", TODAY, "--since", "2025-07-01", "--check-taxes");
        // The bare offer charges no tax at all, so every filed code is out against it.
        assertThat(reply.output())
                .contains("1 offers with a tax out against the filing", "DE not charged", "RA not charged");
    }
}
