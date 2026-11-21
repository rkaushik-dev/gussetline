package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.desk.Coupon;
import io.github.rkaushikdev.gussetline.desk.CouponStatus;
import io.github.rkaushikdev.gussetline.desk.Observation;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.Retired;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.desk.Ticket;
import io.github.rkaushikdev.gussetline.desk.Watch;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Residual;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceDeskmapTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2025, 8, 4);

    private static Coupon coupon() {
        return coupon(0, "FRA", "JFK", CouponStatus.OPEN);
    }

    private static Coupon coupon(int position, String origin, String destination) {
        return coupon(position, origin, destination, CouponStatus.OPEN);
    }

    private static Coupon coupon(int position, String origin, String destination, CouponStatus status) {
        return new Coupon(
                position,
                "LH",
                "400",
                origin,
                destination,
                LocalDateTime.of(2025, 8, 4, 10, 25),
                "K",
                "KLWAP7RT",
                status);
    }

    private static Ticket ticket(Coupon... coupons) {
        return ticket("", coupons);
    }

    private static Ticket ticket(String fareCalculation, Coupon... coupons) {
        return new Ticket(
                "220-1234567890",
                "ABC123",
                "MUELLER/ANNA",
                coupons.length == 0 ? List.of(coupon()) : List.of(coupons),
                LocalDate.of(2025, 2, 14),
                "LH",
                Money.of("840.00", "EUR"),
                Money.of("1090.83", "EUR"),
                fareCalculation,
                "",
                "",
                List.of());
    }

    private static Watch.Builder watch() {
        return Watch.builder()
                .ticket(ticket())
                .origin("FRA")
                .destination("JFK")
                .departure(DEPARTURE)
                .threshold(Money.of("50.00", "EUR"));
    }

    /** The watch's row with its ticket's row hung under it, as a row read back has. */
    private static WatchRow onItsTicket(Watch made) {
        WatchRow row = DeskMap.watchToRow(made);
        DeskMap.ticketToRow(made.ticket()).addWatch(row);
        return row;
    }

    private static Recommendation.Builder recommendation() {
        return Recommendation.builder()
                .ticketNumber("220-1234567890")
                .seriesKey("FRA-NYC-2025-08-04-Y")
                .raisedOn(LocalDate.of(2025, 7, 1))
                .paid(Money.of("840.00", "EUR"))
                .newFare(Money.of("700.00", "EUR"))
                .penalty(Money.of("50.00", "EUR"))
                .benefit(Money.of("90.00", "EUR"))
                .residual(Residual.REFUNDABLE)
                .fareBases(List.of("KLWAP7RT", "MLWAP7RT"))
                .fingerprint("f".repeat(32));
    }

    private static Recommendation roundTrip(Recommendation made) {
        RecommendationRow row = DeskMap.recommendationToRow(made);
        return DeskMap.rowToRecommendation(row, made.ticketNumber(), made.seriesKey());
    }

    private static ObservationRow anObservationRow() {
        ObservationRow row = new ObservationRow();
        row.setSeriesKey("FRA-NYC-2025-08-04-Y");
        row.setSource("gds");
        row.setFingerprint("a".repeat(32));
        row.setOrigin("FRA");
        row.setDestination("JFK");
        row.setDeparture(DEPARTURE);
        row.setCabin("Y");
        row.setBaseAmount(new BigDecimal("620.00"));
        row.setBaseCurrency("EUR");
        row.setTotalAmount(new BigDecimal("790.83"));
        row.setFirstSeen(LocalDateTime.of(2025, 7, 1, 6, 0));
        row.setLastSeen(LocalDateTime.of(2025, 7, 2, 6, 0));
        return row;
    }

    @Test
    void aCouponSurvivesTheRoundTrip() {
        assertThat(DeskMap.rowToCoupon(DeskMap.couponToRow(coupon()))).isEqualTo(coupon());
    }

    @Test
    void aUsedCouponSurvivesTheRoundTrip() {
        Coupon used = coupon(0, "FRA", "JFK", CouponStatus.USED);
        assertThat(DeskMap.rowToCoupon(DeskMap.couponToRow(used))).isEqualTo(used);
    }

    @Test
    void aUsedCouponIsMarkedFlownOnTheRowAsWell() {
        assertThat(DeskMap.couponToRow(coupon(0, "FRA", "JFK", CouponStatus.USED))
                        .isFlown())
                .isTrue();
        assertThat(DeskMap.couponToRow(coupon()).isFlown()).isFalse();
    }

    @Test
    void aTicketSurvivesTheRoundTrip() {
        Ticket made = ticket(coupon(0, "FRA", "JFK"), coupon(1, "JFK", "FRA"));
        assertThat(DeskMap.rowToTicket(DeskMap.ticketToRow(made))).isEqualTo(made);
    }

    @Test
    void aTicketRowTakesItsMarketOffItsOwnCoupons() {
        TicketRow row = DeskMap.ticketToRow(ticket(coupon(0, "FRA", "MUC"), coupon(1, "MUC", "JFK")));
        assertThat(row.getOrigin()).isEqualTo("FRA");
        assertThat(row.getDestination()).isEqualTo("JFK");
        assertThat(row.getDeparture()).isEqualTo(DEPARTURE);
    }

    @Test
    void aTicketKeepsItsCouponsInOrder() {
        Ticket made = ticket(coupon(0, "FRA", "JFK"), coupon(1, "JFK", "FRA"));
        assertThat(DeskMap.ticketToRow(made).getCoupons())
                .extracting(CouponRow::getPosition)
                .containsExactly(0, 1);
    }

    @Test
    void aFareCalculationTooLongForTheColumnIsCutRatherThanRefused() {
        TicketRow row = DeskMap.ticketToRow(ticket("Z".repeat(900)));
        assertThat(row.getFareCalculation()).hasSize(512);
    }

    @Test
    void aWatchSurvivesTheRoundTrip() {
        Watch made = watch().build();
        assertThat(DeskMap.rowToWatch(onItsTicket(made)))
                .usingRecursiveComparison()
                .isEqualTo(made);
    }

    @Test
    void aRetiredWatchKeepsItsReason() {
        Watch made = watch().build();
        made.retire(Retired.FLOWN);
        Watch back = DeskMap.rowToWatch(onItsTicket(made));
        assertThat(back.isActive()).isFalse();
        assertThat(back.retiredReason()).containsSame(Retired.FLOWN);
    }

    @Test
    void aReasonNobodyCanReadLeavesTheWatchRetired() {
        // A reason we cannot read is not a reason to start spending shopping requests on the watch
        // again.
        WatchRow row = onItsTicket(watch().build());
        row.setActive(false);
        row.setRetiredReason("somebody typed this");
        Watch back = DeskMap.rowToWatch(row);
        assertThat(back.isActive()).isFalse();
        assertThat(back.retiredReason()).containsSame(Retired.BY_HAND);
    }

    @Test
    void aWatchComesBackWithNoHistoryUnlessItIsGivenSome() {
        assertThat(DeskMap.rowToWatch(onItsTicket(watch().build())).history()).isEmpty();
    }

    @Test
    void aWatchTakesWhateverHistoryTheCallerDecidedOn() {
        WatchRow row = onItsTicket(watch().build());
        List<Observation> readings = List.of(new Observation(LocalDate.of(2025, 7, 1), Money.of("700.00", "EUR")));
        assertThat(DeskMap.rowToWatch(row, readings).history()).isEqualTo(readings);
    }

    @Test
    void aWatchInAnotherCabinSurvivesTheRoundTrip() {
        Watch made = watch().cabin(Cabin.BUSINESS).build();
        assertThat(DeskMap.rowToWatch(onItsTicket(made)).cabin()).isSameAs(Cabin.BUSINESS);
    }

    @Test
    void anObservationRowReadsAsWhatTheCustomerWouldPay() {
        // The total and not the base fare. A drop in the fare that a rise in tax swallows is not a
        // drop the desk can quote.
        Observation reading = DeskMap.observationRowToReading(anObservationRow());
        assertThat(reading.amount()).isEqualTo(Money.of("790.83", "EUR"));
        assertThat(reading.seenOn()).isEqualTo(LocalDate.of(2025, 7, 2));
        assertThat(reading.source()).isEqualTo("gds");
    }

    @Test
    void aRecommendationSurvivesTheRoundTrip() {
        Recommendation made = recommendation().build();
        assertThat(roundTrip(made)).isEqualTo(made);
    }

    @Test
    void aSettledRecommendationSurvivesTheRoundTrip() {
        Recommendation made = recommendation().build();
        made.take(LocalDate.of(2025, 7, 2), "anna", "customer agreed");
        Recommendation back = roundTrip(made);
        assertThat(back.state()).isSameAs(State.TAKEN);
        assertThat(back.decidedBy()).isEqualTo("anna");
        assertThat(back.note()).isEqualTo("customer agreed");
    }

    @Test
    void theFareBasesSurviveAsAListAndNotAsOneString() {
        Recommendation made = recommendation().build();
        RecommendationRow row = DeskMap.recommendationToRow(made);
        assertThat(row.getFareBases()).isEqualTo("KLWAP7RT/MLWAP7RT");
        Recommendation back = DeskMap.rowToRecommendation(row, made.ticketNumber(), made.seriesKey());
        assertThat(back.fareBases()).containsExactly("KLWAP7RT", "MLWAP7RT");
    }

    @Test
    void aRecommendationWithNoFareBasesComesBackWithNone() {
        Recommendation made = recommendation().fareBases(List.of()).build();
        assertThat(roundTrip(made).fareBases()).isEmpty();
    }

    @Test
    void theWatchIdIsSetWhenItIsGiven() {
        assertThat(DeskMap.recommendationToRow(recommendation().build(), 7).getWatchId())
                .isEqualTo(7);
    }

    @Test
    void movingAStoredRecommendationWritesOnlyWhatCanMove() {
        Recommendation made = recommendation().build();
        RecommendationRow row = DeskMap.recommendationToRow(made);
        made.kill(LocalDate.of(2025, 7, 3), "bruno");
        DeskMap.applyRecommendation(row, made);
        assertThat(row.getState()).isEqualTo("killed");
        assertThat(row.getDecidedBy()).isEqualTo("bruno");
        // What the desk said is copied once and never rewritten.
        assertThat(row.getBenefitAmount()).isEqualTo(Money.of("90.00", "EUR").amount());
    }

    @Test
    void aSeriesKeyIsWorkedOutFromTheWatchOwnFields() {
        WatchRow row = DeskMap.watchToRow(watch().build());
        assertThat(DeskMap.seriesKeyForRow(row)).isEqualTo("FRA-NYC-2025-08-04-Y");
        DeskMap.checkSeriesKey(row);
    }

    @Test
    void aSeriesKeyThatDisagreesWithItsWatchIsRefused() {
        // A key that does not match its own market makes the watch invisible to every observation the
        // collectors store for it.
        WatchRow row = DeskMap.watchToRow(watch().build());
        row.setSeriesKey("FRA-LON-2025-08-04-Y");
        assertThatThrownBy(() -> DeskMap.checkSeriesKey(row))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void theLastCheckReadsAsADayOrAsNothing() {
        WatchRow row = DeskMap.watchToRow(watch().build());
        assertThat(DeskMap.latestCheck(row)).isEmpty();
        row.setLastChecked(LocalDateTime.of(2025, 7, 2, 6, 0));
        assertThat(DeskMap.latestCheck(row)).contains(LocalDate.of(2025, 7, 2));
    }

    @Test
    void theAllowanceSurvivesTheRoundTrip() {
        Recommendation made = recommendation().baggage("2PC").build();
        assertThat(roundTrip(made).baggage()).isEqualTo("2PC");
    }

    @Test
    void theAllowanceDoesNotSwallowTheNoteItRidesWith() {
        Recommendation made = recommendation().baggage("1PC").build();
        made.take(LocalDate.of(2025, 7, 2), "anna", "customer agreed");
        Recommendation back = roundTrip(made);
        assertThat(back.note()).isEqualTo("customer agreed");
        assertThat(back.baggage()).isEqualTo("1PC");
    }

    @Test
    void aRecommendationWithNoAllowanceReadsBackWithNone() {
        Recommendation back = roundTrip(recommendation().build());
        assertThat(back.baggage()).isEmpty();
        assertThat(back.note()).isEmpty();
    }

    @Test
    void aReadingCarriesWhatTheFareIncluded() {
        ObservationRow row = anObservationRow();
        row.setBagPieces(2);
        Observation reading = DeskMap.observationRowToReading(row);
        assertThat(reading.baggage().pieces()).contains(2);
        assertThat(reading.toString()).contains("2PC");
    }
}
