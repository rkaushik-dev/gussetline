package io.github.rkaushikdev.gussetline.involuntary;

import static io.github.rkaushikdev.gussetline.involuntary.Involuntary.assess;
import static io.github.rkaushikdev.gussetline.involuntary.Involuntary.isWithinReaccommodation;
import static io.github.rkaushikdev.gussetline.involuntary.Involuntary.residualFor;
import static io.github.rkaushikdev.gussetline.involuntary.Involuntary.stillApplies;
import static io.github.rkaushikdev.gussetline.involuntary.Involuntary.waives;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.reissue.TaxReissue;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class InvoluntaryTest {

    private static final Money PAID = Money.of("840.00", "EUR");

    private static AppliedTax tax(String amount) {
        return tax("DE", "FRA", amount);
    }

    private static AppliedTax tax(String code, String point, String amount) {
        return new AppliedTax(code, Money.of(amount, "EUR"), point, false);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void theFareIsTheOneThePassengerBought() {
        // The most common way a desk overcharges somebody already let down.
        Involuntary made = assess(PAID, Cause.CANCELLED);
        assertThat(made.newFare()).isEqualTo(PAID);
    }

    @Test
    void thereIsNoChangeFee() {
        assertThat(assess(PAID, Cause.CANCELLED).penalty()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void nothingIsCollectedWhenTheTaxesDidNotMove() {
        Involuntary made = assess(PAID, Cause.CANCELLED);
        assertThat(made.addCollect()).isEqualTo(Money.zero("EUR"));
        assertThat(made.refund()).isEqualTo(Money.zero("EUR"));
        assertThat(made.net()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aTaxThatWentUpIsStillCollected() {
        TaxReissue taxes = TaxReissue.assess(List.of(tax("32.50")), List.of(tax("40.00")));
        Involuntary made = assess(PAID, Cause.CANCELLED, taxes);
        assertThat(made.addCollect()).isEqualTo(eur("7.50"));
    }

    @Test
    void aTaxThatCameBackComesBack() {
        TaxReissue taxes = TaxReissue.assess(List.of(tax("40.00")), List.of(tax("32.50")));
        Involuntary made = assess(PAID, Cause.CANCELLED, taxes);
        assertThat(made.refund()).isEqualTo(eur("7.50"));
        assertThat(made.net().isNegative()).isTrue();
    }

    @Test
    void aCancelledFlightSaysWhyItIsNotRepriced() {
        Involuntary made = assess(PAID, Cause.CANCELLED);
        assertThat(made.notes().get(0)).contains("the carrier did not fly it");
        assertThat(made.notes().get(1)).contains("nobody changed their mind");
    }

    @Test
    void aDowngradeOwesTheDifferenceBetweenTheTwoCabins() {
        Involuntary made = assess(PAID, Cause.DOWNGRADED, Cabin.BUSINESS, Cabin.ECONOMY, eur("1200.00"));
        assertThat(made.downgradeRefund()).isEqualTo(eur("1200.00"));
        assertThat(made.refund()).isEqualTo(eur("1200.00"));
        assertThat(made.notes().get(made.notes().size() - 1)).contains("downgraded from C to Y");
    }

    @Test
    void deniedBoardingOwesADowngradeToo() {
        Involuntary made = assess(PAID, Cause.DENIED_BOARDING, Cabin.BUSINESS, Cabin.ECONOMY, eur("900.00"));
        assertThat(made.downgradeRefund()).isEqualTo(eur("900.00"));
    }

    @Test
    void aScheduleChangeThatHappensToSeatThemLowerOwesNothing() {
        Involuntary made = assess(PAID, Cause.SCHEDULE_CHANGE, Cabin.BUSINESS, Cabin.ECONOMY);
        assertThat(made.downgradeRefund()).isEqualTo(Money.zero("EUR"));
        assertThat(made.notes().get(made.notes().size() - 1)).contains("without a downgrade owed");
    }

    @Test
    void aDowngradeWithNoDifferencePricedIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> assess(PAID, Cause.DOWNGRADED, Cabin.BUSINESS, Cabin.ECONOMY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("difference between the two cabins");
    }

    @Test
    void beingMovedUpACabinOwesNothingEitherWay() {
        Involuntary made = assess(PAID, Cause.CANCELLED, Cabin.ECONOMY, Cabin.BUSINESS);
        assertThat(made.downgradeRefund()).isEqualTo(Money.zero("EUR"));
        assertThat(made.net()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aNegativeRefundIsRefused() {
        assertThatThrownBy(() -> new Involuntary(PAID, Cause.CANCELLED, Optional.empty(), eur("-1.00"), List.of()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not negative");
    }

    @Test
    void anInvoluntaryReissueReadsBackInOneLine() {
        Involuntary made = assess(PAID, Cause.CANCELLED);
        assertThat(made).hasToString("cancelled: collect 0 EUR, refund 0 EUR");
    }

    @Test
    void everyCauseIsTheCarriersDoing() {
        // Which is the whole reason this module exists rather than a flag on the voluntary path.
        assertThat(Cause.values()).allMatch(Cause::isTheCarriersDoing);
    }

    @Test
    void onlyTwoCausesOweADowngrade() {
        Set<Cause> owed = Arrays.stream(Cause.values())
                .filter(Cause::owesADowngradeRefund)
                .collect(Collectors.toSet());
        assertThat(owed).isEqualTo(Set.of(Cause.DOWNGRADED, Cause.DENIED_BOARDING));
    }

    @Test
    void theConditionsThePassengerKeptAreWaived() {
        // Promises about how they would buy and travel, which they kept.
        List<String> given = waives(Cause.CANCELLED);
        assertThat(given).contains("advance purchase");
        assertThat(given).contains("minimum stay");
        assertThat(given).contains("penalties");
    }

    @Test
    void theCarriersOwnLimitsStillApply() {
        List<String> left = stillApplies(Cause.CANCELLED);
        assertThat(left).contains("flight application");
        assertThat(left).doesNotContain("advance purchase");
    }

    @Test
    void theTwoListsDoNotOverlap() {
        Set<String> both = new HashSet<>(waives(Cause.CANCELLED));
        both.retainAll(stillApplies(Cause.CANCELLED));
        assertThat(both).isEmpty();
    }

    @Test
    void aResidualOnAnInvoluntaryReissueAlwaysComesBack() {
        // A forfeited residual is a term accepted for a cheaper fare, and none of that applies when
        // the carrier is the one who did not fly.
        assertThat(Cause.values()).allMatch(cause -> residualFor(cause) == Residual.REFUNDABLE);
    }

    @Test
    void aReaccommodationPermitsTheCarriersOwnFlights() {
        Reaccommodation offer = new Reaccommodation(Cause.CANCELLED);
        assertThat(offer.permits("LH", "LH")).isTrue();
        assertThat(offer.permits("UA", "LH")).isFalse();
    }

    @Test
    void anInterlineReaccommodationPermitsAnAgreedCarrier() {
        Reaccommodation offer = new Reaccommodation(Cause.CANCELLED)
                .withSameCarrierOnly(false)
                .withInterlineAgreed(List.of("UA", "AC"));
        assertThat(offer.permits("UA", "LH")).isTrue();
        assertThat(offer.permits("BA", "LH")).isFalse();
    }

    @Test
    void aCancelledSeatDoesNotBecomeABetterOneByDefault() {
        Reaccommodation offer = new Reaccommodation(Cause.CANCELLED);
        assertThat(offer.permitsCabin(Cabin.ECONOMY, Cabin.ECONOMY)).isTrue();
        assertThat(offer.permitsCabin(Cabin.ECONOMY, Cabin.BUSINESS)).isTrue();
        assertThat(offer.permitsCabin(Cabin.BUSINESS, Cabin.ECONOMY)).isFalse();
    }

    @Test
    void aCarrierThatWillSeatThemAnywhereSaysSo() {
        Reaccommodation offer = new Reaccommodation(Cause.CANCELLED).withSameCabinOrBetter(false);
        assertThat(offer.permitsCabin(Cabin.BUSINESS, Cabin.ECONOMY)).isTrue();
    }

    @Test
    void aReaccommodationReadsBackInOneLine() {
        assertThat(new Reaccommodation(Cause.CANCELLED)).hasToString("cancelled, same carrier");
    }

    @Test
    void aNearbyFlightIsAReaccommodation() {
        assertThat(isWithinReaccommodation(Cause.CANCELLED, LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 6)))
                .isTrue();
    }

    @Test
    void theDayBeforeIsAReaccommodationToo() {
        assertThat(isWithinReaccommodation(Cause.CANCELLED, LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 3)))
                .isTrue();
    }

    @Test
    void twoMonthsLaterIsANewJourneyAndNotAReaccommodation() {
        // Pricing it as involuntary hands them a fare they did not buy.
        assertThat(isWithinReaccommodation(Cause.CANCELLED, LocalDate.of(2025, 8, 4), LocalDate.of(2025, 10, 4)))
                .isFalse();
    }

    @Test
    void theWindowCanBeNamed() {
        assertThat(isWithinReaccommodation(Cause.CANCELLED, LocalDate.of(2025, 8, 4), LocalDate.of(2025, 10, 4), 90))
                .isTrue();
    }

    @Test
    void aNegativeWindowIsRefused() {
        assertThatThrownBy(() -> isWithinReaccommodation(
                        Cause.CANCELLED, LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 6), -1))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not negative");
    }

    @Test
    void aWholeInvoluntaryReissueAddsUp() {
        TaxReissue taxes = TaxReissue.assess(
                List.of(tax("DE", "FRA", "32.50"), tax("US", "JFK", "18.00")),
                List.of(tax("DE", "FRA", "40.00")),
                residualFor(Cause.CANCELLED));
        Involuntary made = assess(
                PAID,
                Cause.DOWNGRADED,
                Optional.of(taxes),
                Cabin.BUSINESS,
                Optional.of(Cabin.ECONOMY),
                Optional.of(eur("1200.00")));
        assertThat(made.newFare()).isEqualTo(PAID);
        assertThat(made.penalty()).isEqualTo(Money.zero("EUR"));
        assertThat(made.addCollect()).isEqualTo(eur("7.50"));
        assertThat(made.refund()).isEqualTo(eur("1218.00"));
        assertThat(made.net()).isEqualTo(eur("-1210.50"));
    }
}
