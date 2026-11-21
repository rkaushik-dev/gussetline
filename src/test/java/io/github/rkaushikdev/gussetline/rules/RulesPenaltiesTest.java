package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.rules.Penalties.highestOf;
import static io.github.rkaushikdev.gussetline.rules.Penalties.isRefundable;
import static io.github.rkaushikdev.gussetline.rules.Penalties.residualValue;
import static io.github.rkaushikdev.gussetline.rules.Penalties.totalPenalty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulesPenaltiesTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Context context() {
        return new Context(
                new Itinerary(List.of(
                        segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"))),
                out(),
                TICKETING);
    }

    private static Context context(LocalDate today) {
        return context().withToday(today);
    }

    /** A change filing at fifty euro before departure and two hundred after; adjust before building. */
    private static Penalty.Builder change() {
        return Penalty.builder()
                .action(Action.CHANGE)
                .beforeDeparture(Money.of("50.00", "EUR"))
                .afterDeparture(Money.of("200.00", "EUR"));
    }

    private static Penalty noShow(String before) {
        return change().action(Action.NO_SHOW)
                .beforeDeparture(Money.of(before, "EUR"))
                .build();
    }

    @Test
    void aNegativePenaltyIsRefused() {
        assertThatThrownBy(() -> change().beforeDeparture(Money.of("-1", "EUR")).build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aFilingWithNothingInItDoesNotApply() {
        assertThat(new Penalty().evaluate(context()).applied()).isFalse();
    }

    @Test
    void travelHasNotStartedBeforeTheFirstDeparture() {
        assertThat(change().build().travelStarted(context(LocalDate.of(2025, 3, 1))))
                .isFalse();
        assertThat(change().build().travelStarted(context(LocalDate.of(2025, 3, 4))))
                .isFalse();
        assertThat(change().build().travelStarted(context(LocalDate.of(2025, 3, 5))))
                .isTrue();
    }

    @Test
    void theSameTicketCostsTwoDifferentAmountsOnTwoDays() {
        Penalty rule = change().build();
        assertThat(rule.amountOn(context(LocalDate.of(2025, 3, 1)))).contains(Money.of("50.00", "EUR"));
        assertThat(rule.amountOn(context(LocalDate.of(2025, 3, 10)))).contains(Money.of("200.00", "EUR"));
    }

    @Test
    void withoutAnAfterDepartureAmountTheBeforeOneStands() {
        Penalty rule = change().afterDeparture(Optional.empty()).build();
        assertThat(rule.amountOn(context(LocalDate.of(2025, 3, 10)))).contains(Money.of("50.00", "EUR"));
    }

    @Test
    void theDateFallsBackToTheTicketingDate() {
        assertThat(change().build().amountOn(context())).contains(Money.of("50.00", "EUR"));
    }

    @Test
    void aPerTicketFeeIsChargedOnceHoweverManyComponents() {
        Penalty rule = change().applies(Applies.PER_TICKET).build();
        assertThat(rule.charge(context(), 3)).contains(Money.of("50.00", "EUR"));
    }

    @Test
    void aPerComponentFeeMultiplies() {
        Penalty rule = change().applies(Applies.PER_COMPONENT).build();
        assertThat(rule.charge(context(), 3)).contains(Money.of("150.00", "EUR"));
    }

    @Test
    void anActionThatIsNotPermittedCostsNothingAndThatIsNotFree() {
        Penalty rule = change().permitted(false).build();
        assertThat(rule.charge(context())).isEmpty();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("action", "change");
    }

    @Test
    void aZeroFeeIsAPriceAndNotARefusal() {
        Penalty rule = change().beforeDeparture(Money.zero("EUR")).build();
        assertThat(rule.charge(context())).contains(Money.zero("EUR"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aPermittedChangeIsRecordedWithItsAmount() {
        Outcome outcome = change().build().evaluate(context());
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.details()).isEqualTo(Map.of("action", "change", "amount", "50.00 EUR"));
    }

    @Test
    void aPenaltyReadsBackInOneLine() {
        assertThat(change().build()).hasToString("change 50.00 EUR before, 200.00 EUR after, per_ticket");
        assertThat(change().permitted(false).build()).hasToString("change not permitted");
    }

    @Test
    void theHighestOfSeveralAmountsWins() {
        assertThat(highestOf(List.of(Money.of("50.00", "EUR"), Money.of("120.00", "EUR"), Money.of("80.00", "EUR"))))
                .contains(Money.of("120.00", "EUR"));
    }

    @Test
    void theHighestOfNothingIsNothing() {
        assertThat(highestOf(List.of())).isEmpty();
    }

    @Test
    void mixedCurrenciesRefuseToCompare() {
        assertThatThrownBy(() -> highestOf(List.of(Money.of("50.00", "EUR"), Money.of("50.00", "USD"))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void twoFilingsAddTogether() {
        Optional<Money> total = totalPenalty(List.of(change().build(), noShow("75.00")), context());
        assertThat(total).contains(Money.of("125.00", "EUR"));
    }

    @Test
    void aHighestOfFilingReplacesTheMultiplicationForItself() {
        Penalty rule = change().applies(Applies.HIGHEST_OF).build();
        assertThat(totalPenalty(List.of(rule), context(), 3)).contains(Money.of("150.00", "EUR"));
    }

    @Test
    void aHighestOfFilingAndAPerTicketOneBothCount() {
        Optional<Money> total =
                totalPenalty(List.of(change().applies(Applies.HIGHEST_OF).build(), noShow("75.00")), context(), 2);
        assertThat(total).contains(Money.of("175.00", "EUR"));
    }

    @Test
    void nothingChargeableTotalsToNothing() {
        assertThat(totalPenalty(List.of(), context())).isEmpty();
        assertThat(totalPenalty(List.of(change().permitted(false).build()), context()))
                .isEmpty();
    }

    @Test
    void penaltiesInTwoCurrenciesRefuseToAdd() {
        Penalty cancel = change().action(Action.CANCEL)
                .beforeDeparture(Money.of("50.00", "USD"))
                .build();
        assertThatThrownBy(() -> totalPenalty(List.of(change().build(), cancel), context()))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aFareWithNoCancelFilingIsRefundable() {
        assertThat(isRefundable(List.of())).isTrue();
        assertThat(isRefundable(List.of(change().build()))).isTrue();
    }

    @Test
    void aCancelFilingThatRefusesMakesItNonRefundable() {
        assertThat(isRefundable(List.of(Penalty.notPermitted(Action.CANCEL)))).isFalse();
    }

    @Test
    void aCancelFilingThatPermitsKeepsItRefundable() {
        assertThat(isRefundable(List.of(new Penalty(Action.CANCEL, Money.of("120.00", "EUR")))))
                .isTrue();
    }

    @Test
    void theResidualIsWhatIsLeftAfterTheFee() {
        assertThat(residualValue(Money.of("500.00", "EUR"), Money.of("120.00", "EUR")))
                .isEqualTo(Money.of("380.00", "EUR"));
    }

    @Test
    void aFeeLargerThanTheFareLeavesNothingAndNotADebt() {
        assertThat(residualValue(Money.of("100.00", "EUR"), Money.of("120.00", "EUR")))
                .isEqualTo(Money.zero("EUR"));
    }

    @Test
    void noFeeAtAllLeavesNothingBecauseNothingMayBeRefunded() {
        assertThat(residualValue(Money.of("500.00", "EUR"), Optional.empty())).isEqualTo(Money.zero("EUR"));
    }
}
