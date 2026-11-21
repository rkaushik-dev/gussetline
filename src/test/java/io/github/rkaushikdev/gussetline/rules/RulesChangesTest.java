package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.rules.Changes.pricedFromToday;
import static io.github.rkaushikdev.gussetline.rules.Changes.reissueFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesChangesTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Context context() {
        return context(true);
    }

    private static Context context(boolean reissue) {
        return new Context(
                        new Itinerary(
                                List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                        out(),
                        TICKETING)
                .withReissue(reissue);
    }

    private static Money money(String amount) {
        return Money.of(amount, "EUR");
    }

    private static VoluntaryChange residual(Residual handling) {
        return VoluntaryChange.builder().residual(handling).build();
    }

    @Test
    void theProvisionIsSilentWhenNothingIsBeingReissued() {
        assertThat(new VoluntaryChange().evaluate(context(false)).applied()).isFalse();
    }

    @Test
    void aFareThatRefusesChangesFails() {
        Outcome outcome = VoluntaryChange.notPermitted().evaluate(context());
        assertThat(outcome.failed()).isTrue();
    }

    @Test
    void aFareThatPermitsChangesRecordsHowItReprices() {
        Outcome outcome = new VoluntaryChange(RepricingBasis.CURRENT, Residual.REFUNDABLE).evaluate(context());
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.details()).isEqualTo(Map.of("basis", "current", "residual", "refundable"));
    }

    @Test
    void aRuleReadsBackInOneLine() {
        assertThat(VoluntaryChange.notPermitted()).hasToString("changes not permitted");
        assertThat(new VoluntaryChange()).hasToString("changes permitted, current pricing, residual forfeited");
    }

    @Test
    void aReissueHappensInOneCurrency() {
        assertThatThrownBy(() -> reissueFor(money("500"), Money.of("400", "USD"), money("50"), new VoluntaryChange()))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aNegativeAmountIsRefused() {
        assertThatThrownBy(() -> reissueFor(money("500"), money("-400"), money("50"), new VoluntaryChange()))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aDearerNewFareCollectsTheDifferenceAndTheFee() {
        Reissue change = reissueFor(money("500"), money("620"), money("50"), new VoluntaryChange());
        assertThat(change.difference()).isEqualTo(money("120"));
        assertThat(change.addCollect()).isEqualTo(money("170"));
        assertThat(change.worthDoing()).isFalse();
    }

    @Test
    void aCheaperNewFareStillCollectsTheFee() {
        Reissue change = reissueFor(money("500"), money("380"), money("50"), new VoluntaryChange());
        assertThat(change.difference()).isEqualTo(money("-120"));
        assertThat(change.addCollect()).isEqualTo(money("50"));
    }

    @Test
    void aForfeitedResidualIsWorthNothingHoweverLargeTheDrop() {
        Reissue change = reissueFor(money("800"), money("400"), money("50"), residual(Residual.FORFEITED));
        assertThat(change.refund().isZero()).isTrue();
        assertThat(change.credit().isZero()).isTrue();
        assertThat(change.worthDoing()).isFalse();
    }

    @Test
    void aRefundableResidualComesBackAsCashLessTheFee() {
        Reissue change = reissueFor(money("800"), money("400"), money("50"), residual(Residual.REFUNDABLE));
        assertThat(change.refund()).isEqualTo(money("350"));
        assertThat(change.benefit()).isEqualTo(money("350"));
        assertThat(change.worthDoing()).isTrue();
    }

    @Test
    void aRefundSmallerThanTheFeeLeavesNothingAndNotADebt() {
        Reissue change = reissueFor(money("500"), money("480"), money("50"), residual(Residual.REFUNDABLE));
        assertThat(change.refund().isZero()).isTrue();
    }

    @Test
    void aRetainedResidualBecomesACreditCountedAtADiscount() {
        Reissue change = reissueFor(money("800"), money("400"), money("50"), residual(Residual.RETAINED));
        assertThat(change.credit()).isEqualTo(money("350"));
        assertThat(change.refund().isZero()).isTrue();
        assertThat(change.benefit()).isEqualTo(money("280.00"));
        assertThat(change.worthDoing()).isTrue();
    }

    @Test
    void aCreditIsWorthLessThanTheSameAmountOfCash() {
        Reissue refundable = reissueFor(money("800"), money("400"), money("50"), residual(Residual.REFUNDABLE));
        Reissue retained = reissueFor(money("800"), money("400"), money("50"), residual(Residual.RETAINED));
        assertThat(retained.benefit().isLessThan(refundable.benefit())).isTrue();
    }

    @Test
    void aFareThatRefusesChangesIsWorthNothingEvenOnABigDrop() {
        VoluntaryChange refused = VoluntaryChange.builder()
                .permitted(false)
                .residual(Residual.REFUNDABLE)
                .build();
        Reissue change = reissueFor(money("800"), money("300"), money("0"), refused);
        assertThat(change.benefit().isZero()).isTrue();
        assertThat(change.worthDoing()).isFalse();
        assertThat(change).hasToString("not permitted");
    }

    @Test
    void aReissueThatCostsMoneyReportsANegativeBenefit() {
        Reissue change = reissueFor(money("500"), money("620"), money("50"), new VoluntaryChange());
        assertThat(change.benefit()).isEqualTo(money("-170"));
        assertThat(change).hasToString("costs 170 EUR");
    }

    @Test
    void aReissueThatPaysReadsBackAsWorthIt() {
        Reissue change = reissueFor(money("800"), money("400"), money("50"), residual(Residual.REFUNDABLE));
        assertThat(change).hasToString("worth 350 EUR");
    }

    @Test
    void aDropExactlyEqualToTheFeeIsNotWorthDoing() {
        Reissue change = reissueFor(money("500"), money("450"), money("50"), residual(Residual.REFUNDABLE));
        assertThat(change.refund().isZero()).isTrue();
        assertThat(change.worthDoing()).isFalse();
    }

    @Test
    void aHistoricalFilingCannotReachTodaysFares() {
        assertThat(pricedFromToday(new VoluntaryChange(RepricingBasis.HISTORICAL, Residual.FORFEITED)))
                .isFalse();
        assertThat(pricedFromToday(new VoluntaryChange(RepricingBasis.CURRENT, Residual.FORFEITED)))
                .isTrue();
    }

    @Test
    void aFareThatRefusesChangesIsNeverPricedFromToday() {
        assertThat(pricedFromToday(VoluntaryChange.notPermitted())).isFalse();
    }
}
