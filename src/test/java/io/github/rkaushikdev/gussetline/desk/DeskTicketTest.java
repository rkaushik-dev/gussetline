package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskTicketTest {

    private static final LocalDate BEFORE = LocalDate.of(2025, 6, 20);
    private static final LocalDate BETWEEN = LocalDate.of(2025, 7, 10);
    private static final LocalDate AFTER = LocalDate.of(2025, 8, 1);

    private static Coupon coupon(int position) {
        return coupon(position, "FRA", "JFK", "2025-07-04 10:25");
    }

    private static Coupon coupon(int position, String origin, String destination, String when) {
        return new Coupon(position, "LH", "400", origin, destination, at(when), "K", "KLWAP7RT");
    }

    private static Coupon homeward() {
        return coupon(1, "JFK", "FRA", "2025-07-18 18:30").withFlightNumber("401");
    }

    private static Ticket ticket() {
        return ticket(List.of(coupon(0), homeward()));
    }

    private static Ticket ticket(List<Coupon> coupons) {
        return ticket(coupons, Money.of("840.00", "EUR"));
    }

    private static Ticket ticket(List<Coupon> coupons, Money baseFare) {
        return new Ticket(
                "220-1234567890",
                "ABC123",
                "MUELLER/ANNA",
                coupons,
                LocalDate.of(2025, 2, 14),
                "LH",
                baseFare,
                Money.of("1090.83", "EUR"));
    }

    private static List<Coupon> threeCoupons() {
        return List.of(
                coupon(0), coupon(1, "JFK", "ORD", "2025-07-10 08:00"), coupon(2, "ORD", "FRA", "2025-07-18 16:20"));
    }

    @Test
    void aCouponPositionIsNotNegative() {
        assertThatThrownBy(() -> coupon(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aCouponGoesSomewhere() {
        assertThatThrownBy(() -> coupon(0, "FRA", "FRA", "2025-07-04 10:25")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aCouponReadsBackWithItsStatus() {
        assertThat(coupon(0)).hasToString("0 LH400 FRAJFK OK");
    }

    @Test
    void anOpenCouponBeforeItsDateHasNotFlown() {
        assertThat(coupon(0).flownBy(BEFORE)).isFalse();
    }

    @Test
    void anOpenCouponAfterItsDateCountsAsFlown() {
        // The carrier has not sent the used message yet and the passenger has still gone, and the
        // penalty band has still moved.
        assertThat(coupon(0).flownBy(AFTER)).isTrue();
    }

    @Test
    void aUsedStatusWinsOverTheCalendar() {
        assertThat(coupon(0).withStatus(CouponStatus.USED).flownBy(BEFORE)).isTrue();
    }

    @Test
    void aRefundedCouponIsNotFlownHoweverOldItIs() {
        assertThat(coupon(0).withStatus(CouponStatus.REFUNDED).flownBy(AFTER)).isFalse();
    }

    @Test
    void aVoidCouponIsNotFlown() {
        assertThat(coupon(0).withStatus(CouponStatus.VOID).flownBy(AFTER)).isFalse();
    }

    @Test
    void onlyTheOpenStatusIsOpen() {
        assertThat(CouponStatus.OPEN.isOpen()).isTrue();
        assertThat(CouponStatus.USED.isOpen()).isFalse();
        assertThat(CouponStatus.VOID.isOpen()).isFalse();
    }

    @Test
    void aTicketNeedsACoupon() {
        assertThatThrownBy(() -> ticket(List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void couponsHaveToBeInOrder() {
        assertThatThrownBy(() -> ticket(List.of(coupon(1), coupon(0)))).isInstanceOf(ValidationError.class);
    }

    @Test
    void twoCouponsAtTheSamePositionAreRefused() {
        assertThatThrownBy(() -> ticket(List.of(coupon(0), coupon(0).withFlightNumber("401"))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theEndsComeOffTheFirstAndLastCoupons() {
        Ticket row = ticket();
        assertThat(row.origin()).isEqualTo("FRA");
        assertThat(row.destination()).isEqualTo("FRA");
        assertThat(row.market()).isEqualTo(new Market("FRA", "FRA"));
        assertThat(row.departure()).isEqualTo(LocalDate.of(2025, 7, 4));
    }

    @Test
    void aTicketReadsBackWithWhatWasPaid() {
        assertThat(ticket()).hasToString("220-1234567890 FRAFRA 1090.83 EUR");
    }

    @Test
    void nothingFlownYetIsUntouched() {
        Ticket row = ticket();
        assertThat(row.isUntouched(BEFORE)).isTrue();
        assertThat(row.isPartiallyFlown(BEFORE)).isFalse();
        assertThat(row.isFinished(BEFORE)).isFalse();
        assertThat(row.openCoupons(BEFORE)).hasSize(2);
    }

    @Test
    void oneCouponGoneIsPartiallyFlown() {
        Ticket row = ticket();
        assertThat(row.isUntouched(BETWEEN)).isFalse();
        assertThat(row.isPartiallyFlown(BETWEEN)).isTrue();
        assertThat(row.isFinished(BETWEEN)).isFalse();
        assertThat(row.openCoupons(BETWEEN)).hasSize(1);
        assertThat(row.flownCoupons(BETWEEN)).hasSize(1);
    }

    @Test
    void everythingFlownIsFinished() {
        Ticket row = ticket();
        assertThat(row.isFinished(AFTER)).isTrue();
        assertThat(row.isPartiallyFlown(AFTER)).isFalse();
        assertThat(row.openCoupons(AFTER)).isEmpty();
    }

    @Test
    void theWholeFareIsRepriceableWhileNothingHasFlown() {
        assertThat(ticket().repriceableValue(BEFORE)).isEqualTo(Money.of("840.00", "EUR"));
    }

    @Test
    void halfTheFareIsRepriceableOnceHalfHasFlown() {
        assertThat(ticket().repriceableValue(BETWEEN)).isEqualTo(Money.of("420.00", "EUR"));
    }

    @Test
    void aFinishedTicketHasNothingLeftOnTheTable() {
        assertThat(ticket().repriceableValue(AFTER)).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aThreeCouponTicketApportionsIntoThirds() {
        Ticket row = ticket(threeCoupons(), Money.of("900.00", "EUR"));
        assertThat(row.repriceableValue(BEFORE)).isEqualTo(Money.of("900.00", "EUR"));
        assertThat(row.repriceableValue(LocalDate.of(2025, 7, 8))).isEqualTo(Money.of("600.00", "EUR"));
    }

    @Test
    void theNextDepartureIsTheFirstOpenCoupon() {
        Ticket row = ticket();
        assertThat(row.nextDeparture(BEFORE)).contains(LocalDate.of(2025, 7, 4));
        assertThat(row.nextDeparture(BETWEEN)).contains(LocalDate.of(2025, 7, 18));
        assertThat(row.nextDeparture(AFTER)).isEmpty();
    }

    @Test
    void theFareBasesComeBackOnceEach() {
        Ticket row = ticket();
        assertThat(row.fareBases()).containsExactly("KLWAP7RT");
        Ticket mixed = ticket(
                List.of(coupon(0), coupon(1, "JFK", "FRA", "2025-07-18 18:30").withFareBasis("QLWAP14RT")));
        assertThat(mixed.fareBases()).containsExactly("KLWAP7RT", "QLWAP14RT");
    }

    @Test
    void aReissuedTicketNamesTheOneItCameFrom() {
        assertThat(ticket().withReissuedFrom("220-0000000001").reissuedFrom()).isEqualTo("220-0000000001");
    }

    @Test
    void theCabinComesOffTheFirstCoupon() {
        Ticket row = ticket(List.of(coupon(0).withCabin(Cabin.BUSINESS)));
        assertThat(row.cabin()).isSameAs(Cabin.BUSINESS);
    }

    @Test
    void apportioningNeverProducesAFractionOfACent() {
        Ticket row = ticket(threeCoupons(), Money.of("1000.00", "EUR"));
        Money value = row.repriceableValue(LocalDate.of(2025, 7, 8));
        assertThat(value.amount()).isEqualByComparingTo(new BigDecimal("666.67"));
    }
}
