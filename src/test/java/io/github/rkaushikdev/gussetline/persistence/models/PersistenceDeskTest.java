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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceDeskTest {

    private static final LocalDateTime STARTED = LocalDateTime.of(2025, 6, 8, 3, 0);

    private TestSession session;

    @BeforeEach
    void open() {
        session = TestDatabase.session();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private static TicketRow ticket() {
        TicketRow row = new TicketRow();
        row.setNumber("220-1234567890");
        row.setRecordLocator("ABC123");
        row.setPassengerName("MUELLER/ANNA");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(LocalDate.of(2025, 7, 4));
        row.setIssuedOn(LocalDate.of(2025, 2, 14));
        row.setValidatingCarrier("LH");
        row.setBaseAmount(new BigDecimal("840.00"));
        row.setBaseCurrency("EUR");
        row.setTotalAmount(new BigDecimal("1090.83"));
        return row;
    }

    private static CouponRow coupon(int position) {
        CouponRow row = new CouponRow();
        row.setPosition(position);
        row.setCarrier("LH");
        row.setFlightNumber("400");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(LocalDateTime.of(2025, 7, 4, 10, 25));
        row.setBookingClass("K");
        row.setFareBasis("KLWAP7RT");
        return row;
    }

    private static WatchRow watch() {
        WatchRow row = new WatchRow();
        row.setSeriesKey("FRA-NYC-2025-07-04-Y");
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(LocalDate.of(2025, 7, 4));
        row.setThresholdAmount(new BigDecimal("50.00"));
        row.setThresholdCurrency("EUR");
        return row;
    }

    private static RecommendationRow recommendation() {
        RecommendationRow row = new RecommendationRow();
        row.setRaisedOn(LocalDate.of(2025, 6, 10));
        row.setPaidAmount(new BigDecimal("840.00"));
        row.setNewFareAmount(new BigDecimal("620.00"));
        row.setPenaltyAmount(new BigDecimal("50.00"));
        row.setBenefitAmount(new BigDecimal("170.00"));
        row.setCurrency("EUR");
        row.setResidual("refundable");
        return row;
    }

    private static RunRow run() {
        RunRow row = new RunRow();
        row.setStartedAt(STARTED);
        return row;
    }

    private static RunResultRow result(String supplier, String outcome) {
        RunResultRow row = new RunResultRow();
        row.setSupplier(supplier);
        row.setMarket("FRA-NYC");
        row.setDeparture(LocalDate.of(2025, 7, 4));
        row.setOutcome(outcome);
        return row;
    }

    @Test
    void aTicketCarriesItsCouponsInOrder() {
        TicketRow row = ticket();
        CouponRow home = coupon(1);
        home.setOrigin("JFK");
        home.setDestination("FRA");
        home.setFlightNumber("401");
        row.addCoupon(home);
        row.addCoupon(coupon(0));
        session.add(row);
        session.commit();
        TicketRow found = session.one(TicketRow.class);
        assertThat(found.getCoupons()).extracting(CouponRow::getPosition).containsExactly(0, 1);
        assertThat(found).hasToString("<Ticket 220-1234567890 FRAJFK>");
    }

    @Test
    void aTicketNumberIsUnique() {
        TicketRow again = ticket();
        again.setRecordLocator("XYZ789");
        session.add(ticket());
        assertRefused(() -> {
            session.add(again);
            session.commit();
        });
    }

    @Test
    void twoCouponsAtTheSamePositionAreRefused() {
        TicketRow row = ticket();
        row.addCoupon(coupon(0));
        CouponRow clash = coupon(0);
        clash.setFlightNumber("401");
        row.addCoupon(clash);
        assertRefused(() -> {
            session.add(row);
            session.commit();
        });
    }

    @Test
    void aCouponStartsOpenAndUnflown() {
        TicketRow row = ticket();
        row.addCoupon(coupon(0));
        session.add(row);
        session.commit();
        CouponRow found = session.one(CouponRow.class);
        assertThat(found.getStatus()).isEqualTo("OK");
        assertThat(found.isFlown()).isFalse();
        assertThat(found).hasToString("<Coupon 0 LH400 OK>");
    }

    @Test
    void aFlownCouponIsMarked() {
        TicketRow row = ticket();
        CouponRow used = coupon(0);
        used.setFlown(true);
        used.setStatus("USED");
        row.addCoupon(used);
        session.add(row);
        session.commit();
        assertThat(session.one(CouponRow.class).isFlown()).isTrue();
    }

    @Test
    void deletingATicketTakesItsCoupons() {
        TicketRow row = ticket();
        row.addCoupon(coupon(0));
        session.add(row);
        session.commit();
        session.delete(row);
        session.commit();
        assertThat(session.all(CouponRow.class)).isEmpty();
    }

    @Test
    void aReissuedTicketNamesTheOneItCameFrom() {
        TicketRow reissue = ticket();
        reissue.setNumber("220-9999999999");
        reissue.setReissuedFrom("220-1234567890");
        session.add(ticket(), reissue);
        session.commit();
        TicketRow found = session.one("select t from TicketRow t where t.reissuedFrom <> ''", TicketRow.class);
        assertThat(found.getReissuedFrom()).isEqualTo("220-1234567890");
    }

    @Test
    void aWatchHangsOffATicketAndStartsLive() {
        TicketRow row = ticket();
        row.addWatch(watch());
        session.add(row);
        session.commit();
        WatchRow found = session.one(WatchRow.class);
        assertThat(found.isActive()).isTrue();
        assertThat(found.getChecks()).isZero();
        assertThat(found).hasToString("<Watch FRA-NYC-2025-07-04-Y live>");
    }

    @Test
    void aRetiredWatchSaysWhy() {
        TicketRow row = ticket();
        WatchRow retired = watch();
        retired.setActive(false);
        retired.setRetiredReason("departure has gone");
        row.addWatch(retired);
        session.add(row);
        session.commit();
        WatchRow found = session.one(WatchRow.class);
        assertThat(found).hasToString("<Watch FRA-NYC-2025-07-04-Y retired, departure has gone>");
    }

    @Test
    void aWatchCountsItsChecks() {
        TicketRow row = ticket();
        row.addWatch(watch());
        session.add(row);
        session.commit();
        WatchRow found = session.one(WatchRow.class);
        found.setChecks(found.getChecks() + 1);
        found.setLastChecked(LocalDateTime.of(2025, 6, 9, 3, 0));
        session.commit();
        assertThat(session.one(WatchRow.class).getChecks()).isEqualTo(1);
    }

    @Test
    void oneTicketCanCarryTwoWatches() {
        TicketRow row = ticket();
        row.addWatch(watch());
        WatchRow business = watch();
        business.setSeriesKey("FRA-NYC-2025-07-04-C");
        business.setCabin("C");
        row.addWatch(business);
        session.add(row);
        session.commit();
        assertThat(session.one(TicketRow.class).getWatches()).hasSize(2);
    }

    @Test
    void aRecommendationKeepsEveryNumberItWasMadeOn() {
        TicketRow row = ticket();
        WatchRow seen = watch();
        seen.addRecommendation(recommendation());
        row.addWatch(seen);
        session.add(row);
        session.commit();
        RecommendationRow found = session.one(RecommendationRow.class);
        assertThat(found.getPaidAmount()).isEqualTo(new BigDecimal("840.00"));
        assertThat(found.getBenefitAmount()).isEqualTo(new BigDecimal("170.00"));
        assertThat(found.getState()).isEqualTo("open");
        assertThat(found).hasToString("<Recommendation open worth 170.00 EUR>");
    }

    @Test
    void aRecommendationMovesThroughItsStates() {
        TicketRow row = ticket();
        WatchRow seen = watch();
        seen.addRecommendation(recommendation());
        row.addWatch(seen);
        session.add(row);
        session.commit();
        RecommendationRow found = session.one(RecommendationRow.class);
        found.setState("taken");
        found.setDecidedOn(LocalDate.of(2025, 6, 11));
        found.setDecidedBy("anna");
        session.commit();
        RecommendationRow again = session.one(RecommendationRow.class);
        assertThat(again.getState()).isEqualTo("taken");
        assertThat(again.getDecidedBy()).isEqualTo("anna");
    }

    @Test
    void deletingAWatchTakesItsRecommendations() {
        TicketRow row = ticket();
        WatchRow seen = watch();
        seen.addRecommendation(recommendation());
        row.addWatch(seen);
        session.add(row);
        session.commit();
        // The watch leaves its ticket's list first: a row still on its parent's list is saved
        // again by the parent when the session writes, and the delete would quietly not happen.
        row.removeWatch(seen);
        session.delete(seen);
        session.commit();
        assertThat(session.all(WatchRow.class)).isEmpty();
        assertThat(session.all(RecommendationRow.class)).isEmpty();
    }

    @Test
    void aRunCarriesItsResults() {
        RunRow run = run();
        run.setTasks(2);
        run.setCalls(2);
        run.setOffers(5);
        RunResultRow ok = result("gds", "ok");
        ok.setOffers(3);
        ok.setAttempts(1);
        run.addResult(ok);
        RunResultRow budget = result("ndc", "budget");
        budget.setReason("quota");
        run.addResult(budget);
        session.add(run);
        session.commit();
        RunRow found = session.one(RunRow.class);
        assertThat(found.getResults()).hasSize(2);
        assertThat(found).hasToString("<Run 2025-06-08 03:00 2 tasks>");
        assertThat(found.getResults().get(0)).hasToString("<Result gds FRA-NYC ok>");
    }

    @Test
    void aRunThatDidNothingIsStillARow() {
        session.add(run());
        session.commit();
        RunRow found = session.one(RunRow.class);
        assertThat(found.getTasks()).isZero();
        assertThat(found.getCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void deletingARunTakesItsResults() {
        RunRow run = run();
        run.addResult(result("gds", "ok"));
        session.add(run);
        session.commit();
        session.delete(run);
        session.commit();
        assertThat(session.all(RunResultRow.class)).isEmpty();
    }

    @Test
    void theLiveWatchIndexIsDeclared() {
        assertThat(Arrays.stream(WatchRow.class.getAnnotation(Table.class).indexes())
                        .map(Index::name))
                .contains("ix_watches_live");
    }
}
