package io.github.rkaushikdev.gussetline.reissue;

import static io.github.rkaushikdev.gussetline.reissue.TaxReissue.assess;
import static io.github.rkaushikdev.gussetline.reissue.TaxReissue.creditFor;
import static io.github.rkaushikdev.gussetline.reissue.TaxReissue.net;
import static io.github.rkaushikdev.gussetline.reissue.TaxReissue.pointsFlown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReissueTest {

    private static AppliedTax tax() {
        return tax("DE", "FRA", "32.50");
    }

    private static AppliedTax tax(String code, String point, String amount) {
        return tax(code, point, amount, "EUR", false);
    }

    private static AppliedTax amounting(String amount) {
        return tax("DE", "FRA", amount);
    }

    private static AppliedTax in(String currency) {
        return tax("DE", "FRA", "32.50", currency, false);
    }

    private static AppliedTax imposed(String code, String point, String amount) {
        return tax(code, point, amount, "EUR", true);
    }

    private static AppliedTax tax(String code, String point, String amount, String currency, boolean imposed) {
        return new AppliedTax(code, Money.of(amount, currency), point, imposed);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void aTaxThatDidNotMoveIsLeftAlone() {
        TaxReissue report = assess(List.of(tax()), List.of(tax()));
        assertThat(report.of(Treatment.UNCHANGED)).isNotEmpty();
        assertThat(report.collect()).isEqualTo(Money.zero("EUR"));
        assertThat(report.refund()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aTaxThatWentUpIsCollected() {
        TaxReissue report = assess(List.of(amounting("32.50")), List.of(amounting("40.00")));
        assertThat(report.collect()).isEqualTo(eur("7.50"));
        assertThat(report.of(Treatment.COLLECTED).get(0).code()).isEqualTo("DE");
    }

    @Test
    void aTaxThatWentUpIsCollectedWhateverTheFareDid() {
        // No netting against a fare that came down. The government is owed what it is owed and the
        // residual is between the passenger and the carrier.
        TaxReissue report = assess(List.of(amounting("32.50")), List.of(amounting("40.00")), Residual.FORFEITED);
        assertThat(report.collect()).isEqualTo(eur("7.50"));
    }

    @Test
    void aTaxThatCameDownComesBack() {
        TaxReissue report = assess(List.of(amounting("40.00")), List.of(amounting("32.50")));
        assertThat(report.refund()).isEqualTo(eur("7.50"));
    }

    @Test
    void aTaxOnAPointTheNewItineraryDoesNotTouchComesBackWhole() {
        // Not because the fare came down. The taxable event did not happen.
        TaxReissue report = assess(List.of(tax("AT", "VIE", "12.00")), List.of(tax()));
        assertThat(report.refund()).isEqualTo(eur("12.00"));
        assertThat(report.collect()).isEqualTo(eur("32.50"));
    }

    @Test
    void aTaxOnANewPointIsCollectedWhole() {
        TaxReissue report = assess(List.of(tax()), List.of(tax(), tax("AT", "VIE", "12.00")));
        assertThat(report.collect()).isEqualTo(eur("12.00"));
    }

    @Test
    void aTaxAtAPointAlreadyDepartedIsGone() {
        // The single most expensive mistake available here. It looks correct on every screen and the
        // carrier finds it at the year end.
        TaxReissue report = assess(List.of(tax()), List.of(), pointsFlown(List.of("FRA")));
        assertThat(report.refund()).isEqualTo(Money.zero("EUR"));
        assertThat(report.flown()).isEqualTo(eur("32.50"));
        assertThat(report.of(Treatment.FLOWN).get(0).point()).isEqualTo("FRA");
    }

    @Test
    void aTaxAtAPointNotYetDepartedStillComesBack() {
        TaxReissue report = assess(List.of(tax("AT", "VIE", "32.50")), List.of(), pointsFlown(List.of("FRA")));
        assertThat(report.refund()).isEqualTo(eur("32.50"));
    }

    @Test
    void aPartialReductionAtAFlownPointIsGoneToo() {
        TaxReissue report =
                assess(List.of(amounting("40.00")), List.of(amounting("32.50")), pointsFlown(List.of("FRA")));
        assertThat(report.refund()).isEqualTo(Money.zero("EUR"));
        assertThat(report.of(Treatment.FLOWN)).isNotEmpty();
    }

    @Test
    void anIncreaseAtAFlownPointIsStillCollected() {
        TaxReissue report =
                assess(List.of(amounting("32.50")), List.of(amounting("40.00")), pointsFlown(List.of("FRA")));
        assertThat(report.collect()).isEqualTo(eur("7.50"));
    }

    @Test
    void aCarrierSurchargeFollowsTheFareAndNotTheGovernment() {
        // YQ is the carrier's own money in a tax shaped box.
        TaxReissue report = assess(List.of(imposed("YQ", "FRA", "180.00")), List.of(), Residual.FORFEITED);
        assertThat(report.refund()).isEqualTo(Money.zero("EUR"));
        assertThat(report.kept()).isEqualTo(eur("180.00"));
    }

    @Test
    void aCarrierSurchargeComesBackWhenTheResidualIsRefundable() {
        TaxReissue report = assess(List.of(imposed("YQ", "FRA", "180.00")), List.of(), Residual.REFUNDABLE);
        assertThat(report.refund()).isEqualTo(eur("180.00"));
    }

    @Test
    void aCarrierSurchargeAtAFlownPointStillFollowsTheFare() {
        // It is not a departure tax, so departing does not make it owed.
        TaxReissue report = assess(
                List.of(imposed("YQ", "FRA", "180.00")), List.of(), pointsFlown(List.of("FRA")), Residual.REFUNDABLE);
        assertThat(report.refund()).isEqualTo(eur("180.00"));
        assertThat(report.flown()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aRetainedResidualKeepsTheSurchargeAsWell() {
        TaxReissue report = assess(List.of(imposed("YR", "FRA", "40.00")), List.of(), Residual.RETAINED);
        assertThat(report.kept()).isEqualTo(eur("40.00"));
    }

    @Test
    void theSameCodeAtTwoPointsIsTwoLines() {
        // Totalling first gives the right answer on the easy cases and quietly wrong ones on every
        // itinerary that changed a country.
        TaxReissue report = assess(
                List.of(tax("XT", "FRA", "10.00"), tax("XT", "VIE", "12.00")), List.of(tax("XT", "FRA", "10.00")));
        assertThat(report.size()).isEqualTo(2);
        assertThat(report.refund()).isEqualTo(eur("12.00"));
    }

    @Test
    void twoChargesAtOnePointAreAddedTogether() {
        // A round trip through Denver pays the facility charge there twice. The new ticket only
        // passes once, so one of the two comes back.
        TaxReissue report =
                assess(List.of(tax("XF", "DEN", "4.50"), tax("XF", "DEN", "4.50")), List.of(tax("XF", "DEN", "4.50")));
        assertThat(report.lines()).hasSize(1);
        assertThat(report.lines().get(0).was()).isEqualTo(eur("9.00"));
        assertThat(report.lines().get(0).treatment()).isEqualTo(Treatment.REFUNDED);
        assertThat(report.refund()).isEqualTo(eur("4.50"));
    }

    @Test
    void aTaxEarnedOnIssueStaysWithTheGovernment() {
        TaxReissue report =
                assess(List.of(tax("AT", "VIE", "32.50")), List.of(), Set.of(), Residual.REFUNDABLE, Set.of("at"));
        assertThat(report.lines().get(0).treatment()).isEqualTo(Treatment.NOT_REFUNDABLE);
        assertThat(report.refund()).isEqualTo(Money.zero("EUR"));
        assertThat(report.retained()).isEqualTo(eur("32.50"));
        TaxReissue refundable = assess(List.of(tax("AT", "VIE", "32.50")), List.of());
        assertThat(refundable.retained()).isEqualTo(Money.zero("EUR"));
        assertThat(refundable.refund()).isEqualTo(eur("32.50"));
    }

    @Test
    void aTaxEarnedOnIssueThatWentUpIsStillCollected() {
        TaxReissue report = assess(
                List.of(tax("AT", "VIE", "32.50")),
                List.of(tax("AT", "VIE", "40.00")),
                Set.of(),
                Residual.REFUNDABLE,
                Set.of("AT"));
        assertThat(report.lines().get(0).treatment()).isEqualTo(Treatment.COLLECTED);
        assertThat(report.collect()).isEqualTo(eur("7.50"));
    }

    @Test
    void aFlownTaxIsFlownBeforeItIsRetained() {
        TaxReissue report =
                assess(List.of(tax("AT", "VIE", "32.50")), List.of(), Set.of("VIE"), Residual.REFUNDABLE, Set.of("AT"));
        assertThat(report.lines().get(0).treatment()).isEqualTo(Treatment.FLOWN);
        assertThat(report.retained()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aCarrierSurchargeIsNeverRetainedAsATax() {
        TaxReissue report =
                assess(List.of(imposed("YQ", "FRA", "180.00")), List.of(), Set.of(), Residual.REFUNDABLE, Set.of("YQ"));
        assertThat(report.lines().get(0).treatment()).isEqualTo(Treatment.REFUNDED);
        assertThat(report.refund()).isEqualTo(eur("180.00"));
    }

    @Test
    void aRetainedLineMovesNoMoneyAndReadsBackByName() {
        assertThat(Treatment.NOT_REFUNDABLE.movesMoney()).isFalse();
        assertThat(Treatment.of("not_refundable")).isEqualTo(Treatment.NOT_REFUNDABLE);
    }

    @Test
    void theLinesComeBackInASettledOrder() {
        TaxReissue report = assess(List.of(tax("XT", "VIE", "32.50"), tax("DE", "FRA", "32.50")), List.of());
        assertThat(report.lines()).extracting(Line::code).containsExactly("DE", "XT");
    }

    @Test
    void aLineReadsBackInOneLine() {
        TaxReissue report = assess(List.of(tax()), List.of(amounting("40.00")));
        assertThat(report.lines().get(0)).hasToString("DE at FRA collected 32.50 EUR to 40.00 EUR");
    }

    @Test
    void aReportReadsBackWithTheTwoTotals() {
        TaxReissue report = assess(List.of(tax()), List.of(amounting("40.00")));
        assertThat(report).hasToString("collect 7.50 EUR, refund 0 EUR, 1 lines");
    }

    @Test
    void theNetSaysWhatTheTaxesCost() {
        TaxReissue report = assess(List.of(amounting("32.50")), List.of(amounting("40.00")));
        assertThat(net(report)).isEqualTo(eur("7.50"));
    }

    @Test
    void aNetThatComesBackKeepsItsSign() {
        // A reissue whose taxes come back further than its fare goes up is a real shape and the desk
        // needs to see the sign.
        TaxReissue report = assess(List.of(amounting("40.00")), List.of(amounting("32.50")));
        assertThat(net(report).isNegative()).isTrue();
    }

    @Test
    void aRefundableResidualPaysNoVoucher() {
        TaxReissue report = assess(List.of(amounting("40.00")), List.of(amounting("32.50")));
        assertThat(creditFor(report, Residual.REFUNDABLE)).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aRetainedResidualValuesTheRefundAsAVoucher() {
        // The same discount the fare side uses, so a recommendation cannot be worth more by moving
        // money between the fare and the taxes.
        TaxReissue report = assess(List.of(amounting("40.00")), List.of(amounting("30.00")));
        assertThat(creditFor(report, Residual.RETAINED)).isEqualTo(eur("8.00"));
    }

    @Test
    void comparingTwoCurrenciesIsRefused() {
        assertThatThrownBy(() -> assess(List.of(in("EUR")), List.of(in("GBP"))))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("one currency");
    }

    @Test
    void comparingNothingAtAllIsRefused() {
        assertThatThrownBy(() -> assess(List.of(), List.of()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("needs a tax");
    }

    @Test
    void thePointsAlreadyDepartedAreReadOffTheFlownCoupons() {
        assertThat(pointsFlown(List.of("fra", "", "vie"))).isEqualTo(Set.of("FRA", "VIE"));
        assertThat(pointsFlown(List.of())).isEmpty();
    }

    @Test
    void onlyTheTwoMoneyMovingTreatmentsMoveMoney() {
        assertThat(Treatment.COLLECTED.movesMoney()).isTrue();
        assertThat(Treatment.REFUNDED.movesMoney()).isTrue();
        assertThat(Treatment.KEPT.movesMoney()).isFalse();
        assertThat(Treatment.FLOWN.movesMoney()).isFalse();
        assertThat(Treatment.UNCHANGED.movesMoney()).isFalse();
    }

    @Test
    void aWholeReissueAddsUpTheWayTheDeskReadsIt() {
        List<AppliedTax> was =
                List.of(tax("DE", "FRA", "32.50"), tax("US", "JFK", "18.00"), imposed("YQ", "FRA", "180.00"));
        List<AppliedTax> now =
                List.of(tax("DE", "FRA", "32.50"), tax("US", "BOS", "18.00"), imposed("YQ", "FRA", "150.00"));
        TaxReissue report = assess(was, now, pointsFlown(List.of("FRA")), Residual.FORFEITED);
        assertThat(report.collect()).isEqualTo(eur("18.00"));
        assertThat(report.refund()).isEqualTo(eur("18.00"));
        assertThat(report.kept()).isEqualTo(eur("30.00"));
        assertThat(net(report)).isEqualTo(Money.zero("EUR"));
    }
}
