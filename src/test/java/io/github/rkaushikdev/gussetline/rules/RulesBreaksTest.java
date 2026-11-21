package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesBreaksTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    /** One short break in London, so a transfer and not a stopover. */
    private static Itinerary connectingTrip() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
    }

    /** The same routing with two days in London, so a stopover. */
    private static Itinerary stoppingTrip() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00")));
    }

    /** Out with two days in London, then home again. */
    private static Itinerary roundTrip() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static FareComponent component(Itinerary itinerary) {
        return new FareComponent(itinerary.elements());
    }

    private static Context context(Itinerary itinerary) {
        return new Context(itinerary, component(itinerary), TICKETING);
    }

    @Test
    void aFilingWithNothingInItDoesNotApply() {
        assertThat(new Stopovers().evaluate(context(connectingTrip())).applied())
                .isFalse();
        assertThat(new Transfers().evaluate(context(connectingTrip())).applied())
                .isFalse();
    }

    @Test
    void negativeAllowancesAreRefused() {
        assertThatThrownBy(() -> new Stopovers(-1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Stopovers.builder().permitted(1).free(-1).build())
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Stopovers.builder()
                        .permitted(1)
                        .chargeNuc(BigDecimal.valueOf(-1))
                        .build())
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new Transfers(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theSameRoutingCountsInTwoDifferentBuckets() {
        // This is the point of the file. Nothing in either rule changed.
        Stopovers noStops = new Stopovers(0);
        Transfers oneTransfer = new Transfers(1);

        Context shortBreak = context(connectingTrip());
        assertThat(noStops.evaluate(shortBreak).failed()).isFalse();
        assertThat(oneTransfer.evaluate(shortBreak).failed()).isFalse();

        Context longBreak = context(stoppingTrip());
        assertThat(noStops.evaluate(longBreak).failed()).isTrue();
        assertThat(oneTransfer.evaluate(longBreak).failed()).isFalse();
    }

    @Test
    void aStopoverOverTheAllowanceFailsWithTheCount() {
        Outcome outcome = new Stopovers(0).evaluate(context(stoppingTrip()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("taken", 1, "permitted", 0));
    }

    @Test
    void aStopoverInsideTheAllowancePasses() {
        Outcome outcome = new Stopovers(2).evaluate(context(stoppingTrip()));
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.details()).containsEntry("taken", 1);
    }

    @Test
    void aStopoverAtAPointTheFareDoesNotNameFails() {
        Stopovers rule =
                Stopovers.builder().permitted(2).atPoints(List.of("AMS")).build();
        Outcome outcome = rule.evaluate(context(stoppingTrip()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("at", "LON");
    }

    @Test
    void aStopoverAtANamedPointPasses() {
        Stopovers rule =
                Stopovers.builder().permitted(2).atPoints(List.of("LHR", "AMS")).build();
        assertThat(rule.evaluate(context(stoppingTrip())).failed()).isFalse();
    }

    @Test
    void theNamedPointsAreFoldedToCities() {
        Stopovers rule =
                Stopovers.builder().permitted(2).atPoints(List.of("LGW")).build();
        assertThat(rule.evaluate(context(stoppingTrip())).failed()).isFalse();
    }

    @Test
    void aFreeAllowanceMeansNoCharge() {
        Stopovers rule = Stopovers.builder()
                .permitted(2)
                .free(1)
                .chargeNuc(new BigDecimal("50.00"))
                .build();
        assertThat(rule.chargeFor(context(stoppingTrip()))).isEqualByComparingTo("0");
    }

    @Test
    void aStopoverPastTheFreeAllowanceIsCharged() {
        Stopovers rule = Stopovers.builder()
                .permitted(2)
                .free(0)
                .chargeNuc(new BigDecimal("50.00"))
                .build();
        assertThat(rule.chargeFor(context(stoppingTrip()))).isEqualByComparingTo("50.00");
    }

    @Test
    void noChargeFiledMeansNothingToCharge() {
        Stopovers rule = Stopovers.builder().permitted(2).free(0).build();
        assertThat(rule.chargeFor(context(stoppingTrip()))).isEqualByComparingTo("0");
    }

    @Test
    void aTransferOverTheAllowanceFails() {
        Outcome outcome = new Transfers(0).evaluate(context(connectingTrip()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("taken", 1, "permitted", 0));
    }

    @Test
    void aTransferAtAPointTheFareDoesNotNameFails() {
        Transfers rule =
                Transfers.builder().permitted(2).atPoints(List.of("AMS")).build();
        Outcome outcome = rule.evaluate(context(connectingTrip()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("at", "LON");
    }

    @Test
    void anOnlineOnlyFilingPassesASingleCarrierComponent() {
        Transfers rule = Transfers.builder().permitted(2).onlineOnly(true).build();
        assertThat(rule.evaluate(context(connectingTrip())).failed()).isFalse();
    }

    @Test
    void anOnlineOnlyFilingRefusesAnInterlineComponent() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("BA", "175", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
        Transfers rule = Transfers.builder().permitted(2).onlineOnly(true).build();
        Outcome outcome = rule.evaluate(context(trip));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("carriers", List.of("BA", "LH"));
    }

    @Test
    void aPerDirectionCountOnlySeesThePointsOnItsOwnComponent() {
        Itinerary trip = roundTrip();
        FareComponent outbound = new FareComponent(trip.elements().subList(0, 2));
        FareComponent homebound = new FareComponent(trip.elements().subList(2, 3));
        Stopovers rule = Stopovers.builder().permitted(0).perDirection(true).build();
        assertThat(rule.evaluate(new Context(trip, outbound, TICKETING)).failed())
                .isTrue();
        assertThat(rule.evaluate(new Context(trip, homebound, TICKETING)).failed())
                .isFalse();
    }

    @Test
    void aWholeJourneyCountSeesEveryPoint() {
        Itinerary trip = roundTrip();
        FareComponent homebound = new FareComponent(trip.elements().subList(2, 3));
        Stopovers rule = Stopovers.builder().permitted(0).perDirection(false).build();
        assertThat(rule.evaluate(new Context(trip, homebound, TICKETING)).failed())
                .isTrue();
    }
}
