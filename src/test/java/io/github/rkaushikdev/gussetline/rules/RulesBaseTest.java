package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoRuleFound;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.discounts.Passenger;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesBaseTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary roundTrip() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static Context context() {
        return context(roundTrip());
    }

    private static Context context(Itinerary itinerary) {
        return new Context(itinerary, out(), TICKETING);
    }

    /** Passes whatever it is shown. */
    private record Always() implements Provision {
        @Override
        public Category category() {
            return Category.ADVANCE_RESERVATION;
        }

        @Override
        public Outcome evaluate(Context context) {
            return passes(category(), "nothing to check");
        }
    }

    /** Refuses whatever it is shown, and says by how much. */
    private record Never() implements Provision {
        @Override
        public Category category() {
            return Category.MINIMUM_STAY;
        }

        @Override
        public Outcome evaluate(Context context) {
            return fails(category(), "three nights and it wants seven", Details.of("needed", 7, "had", 3));
        }
    }

    /** Has nothing filed. */
    private record Quiet() implements Provision {
        @Override
        public Category category() {
            return Category.BLACKOUTS;
        }

        @Override
        public Outcome evaluate(Context context) {
            return silent(category());
        }
    }

    @Test
    void theCategoryNumbersAreTheTariffsOwn() {
        assertThat(Category.ADVANCE_RESERVATION.value()).isEqualTo(5);
        assertThat(Category.MINIMUM_STAY.value()).isEqualTo(6);
        assertThat(Category.PENALTIES.value()).isEqualTo(16);
        assertThat(Category.VOLUNTARY_CHANGES.value()).isEqualTo(31);
        assertThat(Category.MINIMUM_STAY.label()).isEqualTo("cat 6");
    }

    @Test
    void theThreeAnswersAreDistinguishable() {
        assertThat(passes(Category.MINIMUM_STAY, "x").answer()).isSameAs(Answer.PASSES);
        assertThat(fails(Category.MINIMUM_STAY, "x").failed()).isTrue();
        assertThat(silent(Category.MINIMUM_STAY).applied()).isFalse();
        assertThat(silent(Category.MINIMUM_STAY).failed()).isFalse();
    }

    @Test
    void aProvisionThatDoesNotApplyIsNotAFailure() {
        Outcome outcome = silent(Category.BLACKOUTS);
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.applied()).isFalse();
        assertThat(Rules.permitted(List.of(outcome))).isTrue();
    }

    @Test
    void anOutcomeReadsBackAsItsCategoryAndVerdict() {
        assertThat(fails(Category.MINIMUM_STAY, "wants seven")).hasToString("cat 6 fails, wants seven");
    }

    @Test
    void theContextReadsTheDepartureOffTheComponent() {
        assertThat(context().departure()).isEqualTo(LocalDate.of(2025, 3, 4));
        assertThat(context().journeyDeparture()).isEqualTo(LocalDate.of(2025, 3, 4));
    }

    @Test
    void theReturnDepartureIsTheFirstSectorHome() {
        assertThat(context().returnDeparture()).contains(LocalDate.of(2025, 3, 18));
    }

    @Test
    void aOneWayHasNoReturnDeparture() {
        Itinerary oneWay =
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
        assertThat(context(oneWay).returnDeparture()).isEmpty();
    }

    @Test
    void theReturnDepartureOfATwoSectorLegHomeIsTheFirstOfThem() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
                segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35")));
        assertThat(context(trip).returnDeparture()).contains(LocalDate.of(2025, 3, 18));
    }

    @Test
    void theContextDefaultsToAnAdult() {
        assertThat(context().passenger().kind()).isSameAs(PassengerType.ADULT);
        assertThat(context()
                        .withPassenger(new Passenger(PassengerType.CHILD))
                        .passenger()
                        .kind())
                .isSameAs(PassengerType.CHILD);
    }

    @Test
    void aRuleHasANumber() {
        assertThatThrownBy(() -> new RuleSet("LH", "  ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRuleListsTheCategoriesItCarries() {
        RuleSet rule = new RuleSet("LH", "1100", List.of(new Always(), new Never(), new Quiet()));
        assertThat(rule.categories())
                .containsExactly(Category.ADVANCE_RESERVATION, Category.MINIMUM_STAY, Category.BLACKOUTS);
        assertThat(rule.has(Category.MINIMUM_STAY)).isTrue();
        assertThat(rule.has(Category.PENALTIES)).isFalse();
        assertThat(rule.size()).isEqualTo(3);
    }

    @Test
    void provisionsCanBeReadBackByCategory() {
        RuleSet rule = new RuleSet("LH", "1100", List.of(new Always(), new Always(), new Never()));
        assertThat(rule.forCategory(Category.ADVANCE_RESERVATION)).hasSize(2);
    }

    @Test
    void aRuleReadsBackWithItsCategories() {
        assertThat(new RuleSet("LH", "1100", List.of(new Always(), new Never())))
                .hasToString("LH rule 1100 [5,6]");
        assertThat(new RuleSet("LH", "1100")).hasToString("LH rule 1100 [empty]");
    }

    @Test
    void theStoreFindsARuleByCarrierNumberAndTariff() {
        RuleStore store = new RuleStore();
        store.add(new RuleSet("LH", "1100"));
        assertThat(store.get("lh", "1100").number()).isEqualTo("1100");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aRuleThatIsNotFiledRaisesAndNamesWhatWasAskedFor() {
        NoRuleFound caught = catchThrowableOfType(NoRuleFound.class, () -> new RuleStore().get("LH", "9999"));
        assertThat(caught.details()).containsEntry("number", "9999");
    }

    @Test
    void findAnswersNothingInsteadOfRaising() {
        assertThat(new RuleStore().find("LH", "9999")).isEmpty();
    }

    @Test
    void theTariffNumberIsPartOfTheKey() {
        RuleStore store = new RuleStore();
        store.add(new RuleSet("LH", "1100", List.of(), "1"));
        store.add(new RuleSet("LH", "1100", List.of(), "8"));
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("LH", "1100", "3")).isEmpty();
    }

    @Test
    void everyProvisionRunsAndNothingShortCircuits() {
        List<Outcome> outcomes = Rules.evaluate(List.of(new Never(), new Always(), new Never()), context());
        assertThat(outcomes).hasSize(3);
        assertThat(Rules.refusals(outcomes)).hasSize(2);
        assertThat(Rules.permitted(outcomes)).isFalse();
    }

    @Test
    void aRuleWithNothingFiledPermitsEverything() {
        assertThat(Rules.permitted(Rules.evaluate(List.of(), context()))).isTrue();
        assertThat(Rules.permitted(Rules.evaluate(List.of(new Quiet()), context())))
                .isTrue();
    }

    @Test
    void failuresAndPassesAreBothRecorded() {
        Trace trace = new Trace();
        Rules.evaluate(List.of(new Always(), new Never()), context(), trace);
        List<Step> steps = trace.forStage(Stage.RULE);
        assertThat(steps).hasSize(2);
        assertThat(trace.stageFailed(Stage.RULE)).isTrue();
    }

    @Test
    void aProvisionThatDoesNotApplyIsNotRecorded() {
        Trace trace = new Trace();
        Rules.evaluate(List.of(new Quiet()), context(), trace);
        assertThat(trace.forStage(Stage.RULE)).isEmpty();
    }

    @Test
    void theDetailsRideAlongToTheTrace() {
        Trace trace = new Trace();
        Rules.evaluate(List.of(new Never()), context(), trace);
        assertThat(trace.forStage(Stage.RULE).get(0).details()).isEqualTo(Map.of("needed", 7, "had", 3));
    }

    @Test
    void aFareNamesTheRuleItPointsAt() {
        assertThat(fare().ruleNumber()).isEqualTo("1100");
    }
}
