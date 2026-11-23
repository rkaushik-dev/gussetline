package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.desk.Reprice.decide;
import static io.github.rkaushikdev.gussetline.desk.Reprice.toRetire;
import static io.github.rkaushikdev.gussetline.desk.Reprice.totalBenefit;
import static io.github.rkaushikdev.gussetline.desk.Reprice.worthReporting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Applies;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskRepriceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 22);
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 7, 4);
    private static final List<String> SEEN = List.of("700.00", "700.00");

    private static Coupon coupon(int position) {
        return coupon(position, "FRA", "JFK", "2025-07-04 10:25");
    }

    private static Coupon coupon(int position, String origin, String destination, String when) {
        return new Coupon(position, "LH", "400", origin, destination, at(when), "K", "KLWAP7RT");
    }

    private static Ticket ticket() {
        return ticket(List.of(coupon(0)));
    }

    private static Ticket ticket(List<Coupon> coupons) {
        return new Ticket(
                "220-1234567890",
                "ABC123",
                "MUELLER/ANNA",
                coupons,
                LocalDate.of(2025, 2, 14),
                "LH",
                Money.of("840.00", "EUR"),
                Money.of("1090.83", "EUR"));
    }

    private static Watch watch() {
        return watch(SEEN);
    }

    private static Watch watch(List<String> seen) {
        return watch(ticket(), seen, "50.00");
    }

    private static Watch watch(List<String> seen, String threshold) {
        return watch(ticket(), seen, threshold);
    }

    /** A watch with a hysteresis of two that has recorded one reading a day from 18 June. */
    private static Watch watch(Ticket ticket, List<String> seen, String threshold) {
        Watch row = Watch.builder()
                .ticket(ticket)
                .origin("FRA")
                .destination("JFK")
                .departure(DEPARTURE)
                .threshold(eur(threshold))
                .hysteresis(2)
                .build();
        for (int index = 0; index < seen.size(); index++) {
            row.record(new Observation(LocalDate.of(2025, 6, 18 + index), eur(seen.get(index))));
        }
        return row;
    }

    private static Context context() {
        return new Context(
                        new Itinerary(
                                List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                        new FareComponent(
                                List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                        LocalDate.of(2025, 2, 14))
                .withToday(TODAY)
                .withReissue(true);
    }

    /** A change filed at fifty euro before departure and two hundred after. */
    private static Penalty change() {
        return change("50.00").build();
    }

    private static Penalty.Builder change(String fee) {
        return Penalty.builder().action(Action.CHANGE).beforeDeparture(eur(fee)).afterDeparture(eur("200.00"));
    }

    /** Changes permitted at the current tariff with the residual refunded. */
    private static VoluntaryChange refundable() {
        return refundableBut().build();
    }

    private static VoluntaryChange.Builder refundableBut() {
        return VoluntaryChange.builder().residual(Residual.REFUNDABLE);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void aFinishedTicketIsCheckedBeforeAnythingElse() {
        Watch row = watch(ticket(List.of(coupon(0).withStatus(CouponStatus.USED))), SEEN, "50.00");
        Decision made = decide(row, refundable(), List.of(change()), context(), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOTHING_OPEN);
        assertThat(made.actionable()).isFalse();
    }

    @Test
    void aFareThatCannotBeChangedStopsThere() {
        Decision made = decide(watch(), refundableBut().permitted(false).build(), List.of(change()), context(), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_CHANGEABLE);
        assertThat(made.reason()).contains("does not permit");
    }

    @Test
    void aHistoricallyPricedFareStopsThere() {
        Decision made = decide(
                watch(), refundableBut().basis(RepricingBasis.HISTORICAL).build(), List.of(change()), context(), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.PRICED_HISTORICALLY);
    }

    @Test
    void aForfeitedResidualStopsThereHoweverBigTheDrop() {
        Decision made = decide(
                watch(List.of("300.00", "300.00")),
                VoluntaryChange.builder().residual(Residual.FORFEITED).build(),
                List.of(change()),
                context(),
                TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.RESIDUAL_FORFEITED);
        assertThat(made.worth().isZero()).isTrue();
    }

    @Test
    void noHeldDropIsNotWorthIt() {
        Decision made = decide(watch(List.of("840.00")), refundable(), List.of(change()), context(), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
        assertThat(made.reason()).contains("no drop has held");
    }

    @Test
    void aDropThatClearsTheFeeAndTheThresholdIsAReissue() {
        Decision made = decide(watch(), refundable(), List.of(change()), context(), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.REISSUE);
        assertThat(made.drop()).contains(eur("140.00"));
        assertThat(made.penalty()).contains(eur("50.00"));
        assertThat(made.benefit()).contains(eur("90.00"));
        assertThat(made.actionable()).isTrue();
    }

    @Test
    void aDropSmallerThanTheFeeIsNotWorthIt() {
        Decision made = decide(
                watch(List.of("810.00", "810.00"), "10.00"),
                refundable(),
                List.of(change("50.00").build()),
                context(),
                TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
        assertThat(made.reason()).contains("does not cover");
        assertThat(made.worth().isZero()).isTrue();
    }

    @Test
    void aDropThatExactlyCoversTheFeeIsNotWorthIt() {
        Decision made = decide(
                watch(List.of("790.00", "790.00"), "0"),
                refundable(),
                List.of(change("50.00").build()),
                context(),
                TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
    }

    @Test
    void aBenefitUnderTheDesksThresholdIsNotWorthIt() {
        Decision made = decide(
                watch(List.of("770.00", "770.00"), "50.00"),
                refundable(),
                List.of(change("50.00").build()),
                context(),
                TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
        assertThat(made.reason()).contains("under the 50.00 EUR");
    }

    @Test
    void aRetainedResidualIsWorthLessThanARefundableOne() {
        Decision cash = decide(watch(), refundable(), List.of(change()), context(), TODAY);
        Decision credit = decide(
                watch(), refundableBut().residual(Residual.RETAINED).build(), List.of(change()), context(), TODAY);
        assertThat(credit.benefit()).isPresent();
        assertThat(cash.benefit()).isPresent();
        assertThat(credit.benefit().get().isLessThan(cash.benefit().get())).isTrue();
        assertThat(credit.benefit()).contains(eur("72.00"));
        assertThat(credit.notes().get(0)).contains("counted at a discount");
    }

    @Test
    void theFeeAfterDepartureIsTheOneUsedOnceTravelStarts() {
        Decision made = decide(watch(), refundable(), List.of(change()), context(), LocalDate.of(2025, 7, 10));
        // The ticket is finished by then, so the first check fires first.
        assertThat(made.verdict()).isSameAs(Verdict.NOTHING_OPEN);
    }

    @Test
    void aPerComponentFeeMultipliesAndCanKillTheSaving() {
        Ticket twoCoupons = ticket(List.of(coupon(0), coupon(1, "JFK", "FRA", "2025-07-18 18:30")));
        Decision made = decide(
                watch(twoCoupons, List.of("740.00", "740.00"), "50.00"),
                refundable(),
                List.of(change("50.00").applies(Applies.PER_COMPONENT).build()),
                context(),
                TODAY);
        assertThat(made.penalty()).contains(eur("100.00"));
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
    }

    @Test
    void aFareWithNoChangeFeeFiledKeepsTheWholeDrop() {
        Decision made = decide(watch(), refundable(), List.of(), context(), TODAY);
        assertThat(made.penalty()).contains(Money.zero("EUR"));
        assertThat(made.benefit()).contains(eur("140.00"));
    }

    @Test
    void aFeeInAnotherCurrencyIsRefused() {
        Penalty dollars =
                change("50.00").beforeDeparture(Money.of("50.00", "USD")).build();
        assertThatThrownBy(() -> decide(watch(), refundable(), List.of(dollars), context(), TODAY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aDecisionReadsBackInOneLine() {
        Decision made = decide(watch(), refundable(), List.of(change()), context(), TODAY);
        assertThat(made).hasToString("FRA-NYC-2025-07-04-Y reissue worth 90.00 EUR");
        Decision refused =
                decide(watch(), refundableBut().permitted(false).build(), List.of(change()), context(), TODAY);
        assertThat(refused.toString()).startsWith("FRA-NYC-2025-07-04-Y not_changeable,");
    }

    @Test
    void onlyTheActionableDecisionsAreReportedDearestFirst() {
        Decision small = decide(watch(List.of("720.00", "720.00")), refundable(), List.of(change()), context(), TODAY);
        Decision large = decide(watch(List.of("600.00", "600.00")), refundable(), List.of(change()), context(), TODAY);
        Decision nothing = decide(watch(List.of("840.00")), refundable(), List.of(change()), context(), TODAY);
        List<Decision> reported = worthReporting(List.of(small, nothing, large));
        assertThat(reported).extracting(Decision::worth).containsExactly(eur("190.00"), eur("70.00"));
    }

    @Test
    void theBenefitOfARunOnlyCountsTheActionableOnes() {
        Decision good = decide(watch(), refundable(), List.of(change()), context(), TODAY);
        Decision bad = decide(watch(), refundableBut().permitted(false).build(), List.of(change()), context(), TODAY);
        assertThat(totalBenefit(List.of(good, bad), "EUR")).isEqualTo(eur("90.00"));
        assertThat(totalBenefit(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aFareThatCanNeverBecomeActionableRetiresItsWatch() {
        Decision unchangeable =
                decide(watch(), refundableBut().permitted(false).build(), List.of(change()), context(), TODAY);
        Decision historical = decide(
                watch(), refundableBut().basis(RepricingBasis.HISTORICAL).build(), List.of(change()), context(), TODAY);
        assertThat(toRetire(List.of(unchangeable, historical)))
                .extracting(Retirement::reason)
                .containsExactly(Retired.NOT_CHANGEABLE, Retired.HISTORICAL);
    }

    @Test
    void aPriceThatIsMerelyTooSmallDoesNotRetireTheWatch() {
        // Prices move. That one is worth keeping.
        Decision made = decide(watch(List.of("810.00", "810.00")), refundable(), List.of(change()), context(), TODAY);
        assertThat(toRetire(List.of(made))).isEmpty();
    }

    @Test
    void aForfeitedResidualDoesNotRetireTheWatchEither() {
        // The carrier can refile category 31, and some of them do quarterly.
        Decision made = decide(
                watch(),
                VoluntaryChange.builder().residual(Residual.FORFEITED).build(),
                List.of(change()),
                context(),
                TODAY);
        assertThat(toRetire(List.of(made))).isEmpty();
    }

    @Test
    void aDecisionWithNoBenefitIsWorthNothingRatherThanEmpty() {
        Decision made = new Decision(Verdict.NOT_CHANGEABLE, watch());
        assertThat(made.worth()).isEqualTo(Money.zero("EUR"));
    }

    /** The usual watch, but every reading came with this allowance. */
    private static Watch seeing(Baggage allowance, String threshold) {
        Watch row = Watch.builder()
                .ticket(ticket())
                .origin("FRA")
                .destination("JFK")
                .departure(DEPARTURE)
                .threshold(eur(threshold))
                .hysteresis(2)
                .build();
        for (int index = 0; index < SEEN.size(); index++) {
            row.record(new Observation(LocalDate.of(2025, 6, 18 + index), eur(SEEN.get(index)), "", "", allowance));
        }
        return row;
    }

    private static Terms holding(Baggage carried, String perPiece) {
        return new Terms(refundable(), List.of(change()), context()).withBaggage(carried, eur(perPiece));
    }

    @Test
    void aLostCheckedPieceComesOffTheBenefit() {
        Decision made = decide(seeing(Baggage.ofPieces(1), "50.00"), holding(Baggage.ofPieces(2), "40.00"), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.REISSUE);
        assertThat(made.benefit()).contains(eur("50.00"));
        assertThat(made.notes()).containsExactly("2PC to 1PC: 1 fewer checked pieces, worth 40.00 EUR");
    }

    @Test
    void aDropThatOnlyPaysForTheLostBagIsNotWorthIt() {
        Decision made = decide(seeing(Baggage.ofPieces(1), "50.00"), holding(Baggage.ofPieces(2), "60.00"), TODAY);
        assertThat(made.verdict()).isSameAs(Verdict.NOT_WORTH_IT);
        assertThat(made.benefit()).contains(eur("30.00"));
        assertThat(made.reason()).contains("under the 50.00 EUR");
    }

    @Test
    void theSameOrALargerAllowanceCostsNothingAndSaysNothing() {
        Decision same = decide(seeing(Baggage.ofPieces(2), "50.00"), holding(Baggage.ofPieces(2), "40.00"), TODAY);
        assertThat(same.benefit()).contains(eur("90.00"));
        assertThat(same.notes()).isEmpty();
        Decision more = decide(seeing(Baggage.ofPieces(2), "50.00"), holding(Baggage.ofPieces(1), "40.00"), TODAY);
        assertThat(more.benefit()).contains(eur("90.00"));
        assertThat(more.notes()).isEmpty();
    }

    @Test
    void piecesAgainstWeightIsSaidAndNotPriced() {
        Decision made = decide(seeing(Baggage.ofKilos(23), "50.00"), holding(Baggage.ofPieces(1), "40.00"), TODAY);
        assertThat(made.benefit()).contains(eur("90.00"));
        assertThat(made.notes()).containsExactly("1PC to 23KG: 1PC and 23KG are not the same kind of allowance");
    }

    @Test
    void anOfferThatStatesNoAllowanceIsSaidAndNotPriced() {
        Decision made = decide(seeing(new Baggage(), "50.00"), holding(Baggage.ofPieces(2), "40.00"), TODAY);
        assertThat(made.benefit()).contains(eur("90.00"));
        assertThat(made.notes()).containsExactly("2PC to unstated: one of the fares does not state an allowance");
    }

    @Test
    void aTicketWithNoAllowanceRecordedIsNotComparedAtAll() {
        Decision made = decide(
                seeing(Baggage.ofPieces(1), "50.00"), new Terms(refundable(), List.of(change()), context()), TODAY);
        assertThat(made.benefit()).contains(eur("90.00"));
        assertThat(made.notes()).isEmpty();
    }

    @Test
    void aRatePerPieceInAnotherCurrencyIsRefused() {
        Terms dollars = new Terms(refundable(), List.of(change()), context())
                .withBaggage(Baggage.ofPieces(2), Money.of("40.00", "USD"));
        assertThatThrownBy(() -> decide(seeing(Baggage.ofPieces(1), "50.00"), dollars, TODAY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("one currency");
    }
}
