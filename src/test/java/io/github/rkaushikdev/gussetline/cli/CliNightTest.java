package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliNightTest {

    private static final String TODAY = "2025-07-28";
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 8, 24);
    private static final String SERIES = "FRA-NYC-2025-08-24-Y";
    private static final DateTimeFormatter DAY_OF_MONTH = DateTimeFormatter.ofPattern("dd", Locale.ROOT);

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

    private static TicketRow makeTicket(UnitOfWork work) {
        return makeTicket(work, "220-1234567890");
    }

    private static TicketRow makeTicket(UnitOfWork work, String number) {
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
        coupon.setDeparture(LocalDateTime.of(2025, 8, 24, 10, 25));
        coupon.setBookingClass("K");
        coupon.setFareBasis("KLWAP7RT");
        ticket.addCoupon(coupon);
        work.session().persist(ticket);
        work.flush();
        return ticket;
    }

    private static WatchRow makeWatch(UnitOfWork work, TicketRow ticket) {
        return makeWatch(work, ticket, SERIES);
    }

    private static WatchRow makeWatch(UnitOfWork work, TicketRow ticket, String series) {
        WatchRow watch = new WatchRow();
        watch.setTicketId(ticket.getId());
        watch.setSeriesKey(series);
        watch.setOrigin("FRA");
        watch.setDestination("JFK");
        watch.setDeparture(DEPARTURE);
        watch.setThresholdAmount(new BigDecimal("50.00"));
        watch.setThresholdCurrency("EUR");
        work.session().persist(watch);
        work.flush();
        return watch;
    }

    private static void observe(UnitOfWork work, String amount, LocalDateTime seen) {
        String fingerprint = amount + "-" + DAY_OF_MONTH.format(seen);
        ObservationRow row = new ObservationRow();
        row.setSeriesKey(SERIES);
        row.setSource("gds");
        row.setFingerprint(fingerprint + "x".repeat(Math.max(0, 32 - fingerprint.length())));
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
        work.session().persist(row);
    }

    private static void fileRule(UnitOfWork work) {
        fileRule(work, true, RepricingBasis.CURRENT);
    }

    private static void fileRule(UnitOfWork work, boolean permitted, RepricingBasis basis) {
        work.rules()
                .save(new RuleSet(
                        "LH",
                        "0100",
                        List.of(
                                VoluntaryChange.builder()
                                        .permitted(permitted)
                                        .basis(basis)
                                        .residual(Residual.REFUNDABLE)
                                        .build(),
                                new Penalty(Action.CHANGE, Money.of("50.00", "EUR"))),
                        "1"));
    }

    private static void fileFare(UnitOfWork work) {
        LoadRow row = work.tariff()
                .openLoad(
                        "LH", "test.dat", "d".repeat(32), LocalDateTime.of(2025, 3, 1, 6, 0), LocalDate.of(2025, 3, 1));
        work.tariff()
                .addFares(
                        row,
                        List.of(Fare.builder()
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
                                .lastTravel(LocalDate.of(2025, 12, 31))
                                .build()));
    }

    /** One ticket, its watch, two mornings at a price, and the fare and rule it was sold on. */
    private void aDesk() {
        aDesk(true, true, "700.00", "700.00");
    }

    private void aDesk(boolean withRule, boolean withFare, String... drops) {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            TicketRow ticket = makeTicket(work);
            makeWatch(work, ticket);
            for (int index = 0; index < drops.length; index++) {
                observe(work, drops[index], LocalDateTime.of(2025, 7, 26 + index, 6, 0));
            }
            if (withFare) {
                fileFare(work);
            }
            if (withRule) {
                fileRule(work);
            }
            work.commit();
        }
    }

    /** What the recommendations in one state are worth, read inside the unit. */
    private List<Money> inState(String state) {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            return work
                    .session()
                    .createQuery("select r from RecommendationRow r where r.state = ?1", RecommendationRow.class)
                    .setParameter(1, state)
                    .getResultList()
                    .stream()
                    .map(row -> new Money(row.getBenefitAmount(), row.getCurrency()))
                    .toList();
        }
    }

    private List<Money> inState() {
        return inState("open");
    }

    private List<WatchRow> liveOn(LocalDate day) {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            return work.watches().live(day);
        }
    }

    @Test
    void theNightCommandShowsUpInTheHelp() {
        assertThat(runner.run("--help").output()).contains("night");
    }

    @Test
    void aDeskWithNoWatchesSaysSo() {
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("no live watches");
    }

    @Test
    void aDropThatHoldsRaisesARecommendation() {
        aDesk();
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("raised 1 worth 90.00 EUR");
        assertThat(reply.output()).contains("wrote 1 recommendations");
    }

    @Test
    void theRecommendationReachesTheDatabase() {
        aDesk();
        runner.run("night", "--on", TODAY);
        assertThat(inState()).containsExactly(Money.of("90.00", "EUR"));
    }

    @Test
    void aDryRunWorksItOutAndWritesNothing() {
        aDesk();
        Shell.Result reply = runner.run("night", "--on", TODAY, "--dry-run");
        assertThat(reply.output()).contains("raised 1 worth 90.00 EUR");
        assertThat(reply.output()).contains("dry run, nothing written");
        assertThat(inState()).isEmpty();
    }

    @Test
    void aTicketWithNoRuleFiledIsLeftAloneAndCounted() {
        // Guessing that a change is permitted is how a desk quotes a reissue the carrier refuses at the
        // counter.
        aDesk(false, true, "700.00", "700.00");
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("1 watches have no rule filed and were left alone");
        assertThat(inState()).isEmpty();
    }

    @Test
    void aFareBasisThatHasLeftTheTariffFindsNoRule() {
        aDesk(true, false, "700.00", "700.00");
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("have no rule filed");
    }

    @Test
    void aFareThatCannotBeChangedRetiresTheWatch() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            fileRule(work, false, RepricingBasis.CURRENT);
            work.commit();
        }
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("retired FRA-NYC-2025-08-24-Y");

        assertThat(liveOn(LocalDate.of(2025, 7, 28))).isEmpty();
    }

    @Test
    void aFareThatRepricesHistoricallyRetiresTheWatchToo() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            fileRule(work, true, RepricingBasis.HISTORICAL);
            work.commit();
        }
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("reprices off the original ticketing date");
    }

    @Test
    void aDropTooSmallToBeWorthItLeavesTheWatchAlone() {
        aDesk(true, true, "820.00", "820.00");
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("not_worth_it");
        assertThat(inState()).isEmpty();

        assertThat(liveOn(LocalDate.of(2025, 7, 28))).hasSize(1);
    }

    @Test
    void aSecondNightDoesNotRaiseTheSameNumberTwice() {
        aDesk();
        runner.run("night", "--on", TODAY);
        Shell.Result reply = runner.run("night", "--on", "2025-07-29");
        assertThat(reply.output()).contains("wrote 0 recommendations");
        assertThat(inState()).hasSize(1);
    }

    @Test
    void aBetterFareOnASecondNightReplacesTheFirst() {
        aDesk();
        runner.run("night", "--on", TODAY);
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            observe(work, "600.00", LocalDateTime.of(2025, 7, 29, 6, 0));
            observe(work, "600.00", LocalDateTime.of(2025, 7, 30, 6, 0));
            work.commit();
        }

        Shell.Result reply = runner.run("night", "--on", "2025-07-30");
        assertThat(reply.output()).contains("wrote 1 recommendations");
        assertThat(inState()).containsExactly(Money.of("190.00", "EUR"));
        assertThat(inState("superseded")).containsExactly(Money.of("90.00", "EUR"));
    }

    @Test
    void aNightReportReadsBackWithTheCounts() {
        aDesk();
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("2025-07-28: looked at 1");
        assertThat(reply.output()).contains("retired 0, expired 0");
    }

    @Test
    void aQuietNightSaysNothingWhenThereIsNothingToSay() {
        aDesk(true, true, "820.00", "820.00");
        Shell.Result reply = runner.run("--quiet", "night", "--on", TODAY);
        assertThat(reply.output().strip()).isEmpty();
    }

    @Test
    void twoWatchesAreBothLookedAt() {
        aDesk();
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            TicketRow other = makeTicket(work, "220-9999999999");
            makeWatch(work, other, "FRA-NYC-2025-08-24-C");
            work.commit();
        }
        Shell.Result reply = runner.run("night", "--on", TODAY);
        assertThat(reply.output()).contains("looked at 2");
    }
}
