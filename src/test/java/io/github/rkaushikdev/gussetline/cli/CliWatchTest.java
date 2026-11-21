package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWatchTest {

    private static final String TODAY = "2025-08-01";
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 9, 20);
    private static final String SERIES = "FRA-NYC-2025-09-20-Y";
    private static final String TICKET = "220-1234567890";

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

    private static TicketRow aTicket(UnitOfWork work) {
        return aTicket(work, DEPARTURE, "JFK");
    }

    private static TicketRow aTicket(UnitOfWork work, LocalDate departure, String destination) {
        TicketRow ticket = new TicketRow();
        ticket.setNumber(TICKET);
        ticket.setRecordLocator("ABC123");
        ticket.setPassengerName("MUELLER/ANNA");
        ticket.setOrigin("FRA");
        ticket.setDestination(destination);
        ticket.setDeparture(departure);
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
        coupon.setDestination(destination);
        coupon.setDeparture(departure.atTime(10, 25));
        coupon.setBookingClass("K");
        coupon.setFareBasis("KLWAP7RT");
        ticket.addCoupon(coupon);
        work.session().persist(ticket);
        work.flush();
        return ticket;
    }

    private static void aFareAndRule(UnitOfWork work, String fee) {
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
        work.rules().save(new RuleSet("LH", "0100", List.of(new Penalty(Action.CHANGE, Money.of(fee, "EUR"))), "1"));
    }

    /** A ticket on the desk, departing on the ordinary day, to Kennedy, with nothing else. */
    private void aTicketOnTheDesk() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aTicket(work);
            work.commit();
        }
    }

    /** The same ticket, and the fare and rule it was sold on, filing a change fee. */
    private void aTicketWithItsRule(String fee) {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aTicket(work);
            aFareAndRule(work, fee);
            work.commit();
        }
    }

    /**
     * One watch as a test reads it back.
     *
     * @param seriesKey the series it reads
     * @param active whether it is live
     * @param threshold what a drop has to beat
     * @param retiredReason why it was closed, or nothing
     */
    private record Watched(String seriesKey, boolean active, BigDecimal threshold, String retiredReason) {}

    private List<Watched> watches() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            return work
                    .session()
                    .createQuery("select w from WatchRow w order by w.id", WatchRow.class)
                    .getResultList()
                    .stream()
                    .map(row -> new Watched(
                            row.getSeriesKey(), row.isActive(), row.getThresholdAmount(), row.getRetiredReason()))
                    .toList();
        }
    }

    @Test
    void theWatchGroupListsItsThreeCommands() {
        Shell.Result reply = runner.run("watch", "--help");
        assertThat(reply.output()).contains("add");
        assertThat(reply.output()).contains("list");
        assertThat(reply.output()).contains("retire");
    }

    @Test
    void aTicketBecomesAWatch() {
        aTicketOnTheDesk();

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("watching " + SERIES);
        assertThat(watches().getFirst().seriesKey()).isEqualTo(SERIES);
    }

    @Test
    void theSeriesKeyIsBuiltFromCitiesAndNotAirports() {
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aTicket(work, DEPARTURE, "EWR");
            work.commit();
        }

        runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(watches().getFirst().seriesKey()).isEqualTo(SERIES);
    }

    @Test
    void aTicketNobodyHasIsANotFound() {
        Shell.Result reply = runner.run("watch", "add", "220-0000000000", "--on", TODAY);
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(NotFoundError.class);
    }

    @Test
    void aDepartureThatHasGoneIsRefusedWithTheDateItRead() {
        // A ticket somebody typed the wrong year on. Accepting it puts a watch on the schedule that will
        // never be polled and never be looked at again.
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            aTicket(work, LocalDate.of(2025, 1, 5), "JFK");
            work.commit();
        }

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(ValidationError.class);
        assertThat(reply.failure())
                .hasValueSatisfying(refused -> assertThat(refused).hasMessageContaining("2025-01-05"));
    }

    @Test
    void theSameSeriesIsNotWatchedTwice() {
        aTicketOnTheDesk();

        runner.run("watch", "add", TICKET, "--on", TODAY);
        Shell.Result again = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(again.code()).isNotZero();
        assertThat(again.output()).contains("already watched");
    }

    @Test
    void theThresholdIsTheChangeFeePlusAMargin() {
        aTicketWithItsRule("80.00");

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.output()).contains("a drop has to beat 105.00 EUR");
    }

    @Test
    void aBiggerFeeMakesABiggerThreshold() {
        aTicketWithItsRule("250.00");

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.output()).contains("275.00 EUR");
    }

    @Test
    void withNoRuleLoadedTheSettingsFigureIsUsed() {
        aTicketOnTheDesk();

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.output()).contains("50.00 EUR");
    }

    @Test
    void theThresholdCanBeOverriddenByHand() {
        aTicketWithItsRule("80.00");

        runner.run("watch", "add", TICKET, "--on", TODAY, "--threshold", "300.00");
        assertThat(watches().getFirst().threshold()).isEqualByComparingTo("300.00");
    }

    @Test
    void aCabinCanBeNamed() {
        aTicketOnTheDesk();

        runner.run("watch", "add", TICKET, "--on", TODAY, "--cabin", "C");
        assertThat(watches().getFirst().seriesKey()).endsWith("-C");
    }

    @Test
    void aCabinNobodySellsIsRefused() {
        aTicketOnTheDesk();

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY, "--cabin", "Q");
        assertThat(reply.code()).isNotZero();
    }

    @Test
    void anEmptyDeskListsNothing() {
        Shell.Result reply = runner.run("watch", "list", "--on", TODAY);
        assertThat(reply.output()).contains("nothing watched");
    }

    @Test
    void aWatchListsWithWhatWasPaidAndWhatWasSeen() {
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);

        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            ObservationRow row = new ObservationRow();
            row.setSeriesKey(SERIES);
            row.setSource("gds");
            row.setFingerprint("a".repeat(32));
            row.setOrigin("FRA");
            row.setDestination("JFK");
            row.setDeparture(DEPARTURE);
            row.setCabin("Y");
            row.setBaseAmount(new BigDecimal("700.00"));
            row.setBaseCurrency("EUR");
            row.setTaxAmount(new BigDecimal("0.00"));
            row.setSurchargeAmount(new BigDecimal("0.00"));
            row.setTotalAmount(new BigDecimal("700.00"));
            row.setFirstSeen(LocalDateTime.of(2025, 7, 30, 6, 0));
            row.setLastSeen(LocalDateTime.of(2025, 7, 30, 6, 0));
            work.session().persist(row);
            work.commit();
        }

        Shell.Result reply = runner.run("watch", "list", "--on", TODAY);
        assertThat(reply.output()).contains(SERIES);
        assertThat(reply.output()).contains("paid 840.00");
        assertThat(reply.output()).contains("cheapest 700.00 EUR");
    }

    @Test
    void aWatchWithNothingSeenSaysSo() {
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(runner.run("watch", "list", "--on", TODAY).output()).contains("nothing seen");
    }

    @Test
    void retiringRecordsThatAPersonSaidSo() {
        // A watch closed by a person and one closed because the departure went are different readings of
        // the desk.
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);

        Shell.Result reply = runner.run("watch", "retire", SERIES);
        assertThat(reply.code()).isZero();
        Watched watched = watches().getFirst();
        assertThat(watched.active()).isFalse();
        assertThat(watched.retiredReason()).isEqualTo("closed by the desk");
    }

    @Test
    void aRetiredWatchIsOutOfTheListingUnlessItIsAskedFor() {
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);
        runner.run("watch", "retire", SERIES);

        assertThat(runner.run("watch", "list", "--on", TODAY).output()).contains("nothing watched");
        Shell.Result everything = runner.run("watch", "list", "--on", TODAY, "--all");
        assertThat(everything.output()).contains("closed by the desk");
    }

    @Test
    void retiringSomethingThatIsNotThereIsANotFound() {
        Shell.Result reply = runner.run("watch", "retire", "FRA-NYC-2099-01-01-Y");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(NotFoundError.class);
    }

    @Test
    void retiringSomethingThatIsNotASeriesKeySaysWhichArgumentIsWrong() {
        // A typo used to come back as no such watch, which sends somebody looking in the database for a
        // row that was never going to be there.
        Shell.Result reply = runner.run("watch", "retire", "FRA-NYC-2099-1-1");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.failure()).containsInstanceOf(ValidationError.class);
        assertThat(reply.failure())
                .hasValueSatisfying(refused -> assertThat(refused).hasMessageContaining("not a series key"));
    }

    @Test
    void retiringTwiceSaysItIsAlreadyRetired() {
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);
        runner.run("watch", "retire", SERIES);

        Shell.Result again = runner.run("watch", "retire", SERIES);
        assertThat(again.code()).isNotZero();
        assertThat(again.output()).contains("already retired");
    }

    @Test
    void aRetiredSeriesCanBeWatchedAgain() {
        aTicketOnTheDesk();
        runner.run("watch", "add", TICKET, "--on", TODAY);
        runner.run("watch", "retire", SERIES);

        Shell.Result reply = runner.run("watch", "add", TICKET, "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(watches()).hasSize(2);
    }
}
