package io.github.rkaushikdev.gussetline.desk;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.expireStale;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.openOn;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.openOnes;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.raiseFrom;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.realisedTotal;
import static io.github.rkaushikdev.gussetline.desk.Recommendation.takeRate;
import static io.github.rkaushikdev.gussetline.desk.Reprice.decide;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskRecommendTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 24);
    private static final LocalDate DEPARTURE = LocalDate.of(2025, 7, 4);
    private static final List<String> SEEN = List.of("700.00", "700.00");

    private static Ticket ticket() {
        return new Ticket(
                "220-1234567890",
                "ABC123",
                "MUELLER/ANNA",
                List.of(new Coupon(0, "LH", "400", "FRA", "JFK", at("2025-07-04 10:25"), "K", "KLWAP7RT")),
                LocalDate.of(2025, 2, 14),
                "LH",
                eur("840.00"),
                eur("1090.83"));
    }

    /** A watch with a hysteresis of two that has recorded one reading a day from 20 June. */
    private static Watch watch(List<String> seen) {
        Watch row = Watch.builder()
                .ticket(ticket())
                .origin("FRA")
                .destination("JFK")
                .departure(DEPARTURE)
                .threshold(eur("50.00"))
                .hysteresis(2)
                .build();
        for (int index = 0; index < seen.size(); index++) {
            row.record(new Observation(LocalDate.of(2025, 6, 20 + index), eur(seen.get(index)), "f".repeat(32)));
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

    private static Decision aDecision(List<String> seen) {
        return decide(
                watch(seen),
                VoluntaryChange.builder().residual(Residual.REFUNDABLE).build(),
                List.of(new Penalty(Action.CHANGE, eur("50.00"))),
                context(),
                TODAY);
    }

    private static Recommendation recommendation() {
        return raiseFrom(aDecision(SEEN), TODAY);
    }

    private static Recommendation recommendation(int lifetime) {
        return raiseFrom(aDecision(SEEN), TODAY, lifetime);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void aDecisionThatIsNotActionableDoesNotBecomeARecommendation() {
        assertThatThrownBy(() -> raiseFrom(aDecision(List.of("840.00")), TODAY)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRecommendationCopiesEveryNumberItWasMadeOn() {
        Recommendation made = recommendation();
        assertThat(made.paid()).isEqualTo(eur("840.00"));
        assertThat(made.newFare()).isEqualTo(eur("700.00"));
        assertThat(made.penalty()).isEqualTo(eur("50.00"));
        assertThat(made.benefit()).isEqualTo(eur("90.00"));
        assertThat(made.fareBases()).containsExactly("KLWAP7RT");
        assertThat(made.fingerprint()).isEqualTo("f".repeat(32));
    }

    @Test
    void aFreshRecommendationIsOpenAndSavesNothingYet() {
        Recommendation made = recommendation();
        assertThat(made.state()).isSameAs(State.OPEN);
        assertThat(made.state().isSettled()).isFalse();
        assertThat(made.realised()).isEqualTo(Money.zero("EUR"));
        assertThat(made).hasToString("220-1234567890 open, worth 90.00 EUR");
    }

    @Test
    void aRecommendationIsInOneCurrency() {
        assertThatThrownBy(() -> new Recommendation(
                        "x",
                        "k",
                        TODAY,
                        eur("840.00"),
                        Money.of("700.00", "USD"),
                        eur("50.00"),
                        eur("90.00"),
                        Residual.REFUNDABLE))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aRecommendationLastsAtLeastADay() {
        assertThatThrownBy(() -> recommendation(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void takingARecommendationRecordsWhoAndWhen() {
        Recommendation made = recommendation();
        made.take(LocalDate.of(2025, 6, 25), "anna", "reissued on LH");
        assertThat(made.state()).isSameAs(State.TAKEN);
        assertThat(made.decidedBy()).isEqualTo("anna");
        assertThat(made.decidedOn()).contains(LocalDate.of(2025, 6, 25));
        assertThat(made.realised()).isEqualTo(eur("90.00"));
        assertThat(made).hasToString("220-1234567890 taken by anna");
    }

    @Test
    void killingARecommendationSavesNothingAndIsNotAFailure() {
        Recommendation made = recommendation();
        made.kill(LocalDate.of(2025, 6, 25), "anna", "customer wanted the later flight");
        assertThat(made.state()).isSameAs(State.KILLED);
        assertThat(made.realised()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aDecisionNamesWhoMadeIt() {
        Recommendation made = recommendation();
        assertThatThrownBy(() -> made.take(TODAY, "  ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSettledRecommendationCannotBeSettledAgain() {
        Recommendation made = recommendation();
        made.take(TODAY, "anna");
        ConflictError caught = catchThrowableOfType(ConflictError.class, () -> made.kill(TODAY, "bruno"));
        assertThat(caught.details().get("by")).isEqualTo("anna");
    }

    @Test
    void aRecommendationExpiresAfterItsLifetime() {
        Recommendation made = recommendation();
        assertThat(made.expiresOn()).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(made.staleOn(LocalDate.of(2025, 6, 27))).isFalse();
        assertThat(made.staleOn(LocalDate.of(2025, 6, 28))).isTrue();
    }

    @Test
    void expiringRecordsTheClockAsTheDecider() {
        Recommendation made = recommendation();
        made.expire(LocalDate.of(2025, 6, 28));
        assertThat(made.state()).isSameAs(State.EXPIRED);
        assertThat(made.decidedBy()).isEqualTo("the clock");
        assertThat(made.realised()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aSettledRecommendationDoesNotExpire() {
        Recommendation made = recommendation();
        made.take(TODAY, "anna");
        assertThatThrownBy(() -> made.expire(LocalDate.of(2025, 6, 28))).isInstanceOf(ConflictError.class);
    }

    @Test
    void aLongerLifetimeMovesTheExpiry() {
        assertThat(recommendation(7).expiresOn()).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void theStaleOnesAreClosedTogether() {
        Recommendation fresh = recommendation();
        Recommendation old = recommendation().withRaisedOn(LocalDate.of(2025, 6, 10));
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        assertThat(expireStale(List.of(fresh, old, taken), TODAY)).isEqualTo(1);
        assertThat(old.state()).isSameAs(State.EXPIRED);
        assertThat(fresh.state()).isSameAs(State.OPEN);
        assertThat(taken.state()).isSameAs(State.TAKEN);
    }

    @Test
    void theOpenOnesCanBeListed() {
        Recommendation fresh = recommendation();
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        assertThat(openOnes(List.of(fresh, taken))).containsExactly(fresh);
    }

    @Test
    void onlyTheTakenOnesCountTowardSavings() {
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        Recommendation killed = recommendation();
        killed.kill(TODAY, "anna");
        Recommendation expired = recommendation();
        expired.expire(LocalDate.of(2025, 6, 28));
        assertThat(realisedTotal(List.of(taken, killed, expired, recommendation()), "EUR"))
                .isEqualTo(eur("90.00"));
    }

    @Test
    void nothingSettledSavesNothing() {
        assertThat(realisedTotal(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
        assertThat(realisedTotal(List.of(recommendation()), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void theTakeRateIsMeasuredAgainstTheOnesAPersonDecided() {
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        Recommendation killed = recommendation();
        killed.kill(TODAY, "anna");
        Recommendation stillOpen = recommendation();
        assertThat(takeRate(List.of(taken, killed, stillOpen))).isEqualByComparingTo("0.50");
    }

    @Test
    void theTakeRateOfNothingDecidedIsNothing() {
        assertThat(takeRate(List.of())).isEqualByComparingTo("0");
        assertThat(takeRate(List.of(recommendation()))).isEqualByComparingTo("0");
    }

    @Test
    void anExpiredRecommendationIsNobodyDecisionAndIsLeftOut() {
        // The clock settled it. Counting it as a refusal makes the take rate fall
        // when the desk is short staffed rather than when it is choosing badly.
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        Recommendation expired = recommendation();
        expired.expire(LocalDate.of(2025, 6, 28));
        assertThat(takeRate(List.of(taken, expired))).isEqualByComparingTo("1.00");
    }

    @Test
    void aWithdrawnRecommendationIsLeftOutOfTheTakeRateToo() {
        Recommendation taken = recommendation();
        taken.take(TODAY, "anna");
        Recommendation replaced = recommendation();
        replaced.withdraw(TODAY, "abc123");
        assertThat(takeRate(List.of(taken, replaced))).isEqualByComparingTo("1.00");
    }

    @Test
    void withdrawingNamesTheFareThatReplacedItAndNobodyElse() {
        Recommendation made = recommendation();
        made.withdraw(TODAY, "abc123");
        assertThat(made.state()).isSameAs(State.SUPERSEDED);
        assertThat(made.state().isSettled()).isTrue();
        assertThat(made.state().countsAsSaving()).isFalse();
        assertThat(made.state().aPersonDecided()).isFalse();
        assertThat(made.decidedBy()).isEqualTo("a better fare");
        assertThat(made.replacedBy()).isEqualTo("abc123");
        assertThat(made.realised()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aSettledRecommendationCannotBeWithdrawn() {
        Recommendation made = recommendation();
        made.kill(TODAY, "anna");
        assertThatThrownBy(() -> made.withdraw(TODAY)).isInstanceOf(ConflictError.class);
    }

    @Test
    void aWithdrawnRecommendationCannotThenBeTaken() {
        Recommendation made = recommendation();
        made.withdraw(TODAY);
        assertThatThrownBy(() -> made.take(TODAY, "anna")).isInstanceOf(ConflictError.class);
    }

    @Test
    void theOpenOneOnASeriesIsTheNewestOfThem() {
        Recommendation old = recommendation().withRaisedOn(LocalDate.of(2025, 6, 20));
        Recommendation newer = recommendation().withRaisedOn(LocalDate.of(2025, 6, 27));
        Recommendation settled = recommendation();
        settled.take(TODAY, "anna");
        assertThat(openOn(List.of(old, newer, settled), old.seriesKey())).containsSame(newer);
    }

    @Test
    void aSeriesWithNothingOpenHasNothingToSupersede() {
        Recommendation made = recommendation();
        made.take(TODAY, "anna");
        assertThat(openOn(List.of(made), made.seriesKey())).isEmpty();
        assertThat(openOn(List.of(), "FRA-NYC-2025-08-04-Y")).isEmpty();
    }

    @Test
    void aRetainedResidualCarriesThroughToTheRecommendation() {
        Recommendation made = raiseFrom(
                decide(
                        watch(SEEN),
                        VoluntaryChange.builder().residual(Residual.RETAINED).build(),
                        List.of(new Penalty(Action.CHANGE, eur("50.00"))),
                        context(),
                        TODAY),
                TODAY);
        assertThat(made.residual()).isSameAs(Residual.RETAINED);
        assertThat(made.benefit()).isEqualTo(eur("72.00"));
    }
}
