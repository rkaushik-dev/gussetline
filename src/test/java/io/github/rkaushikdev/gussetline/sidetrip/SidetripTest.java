package io.github.rkaushikdev.gussetline.sidetrip;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.sidetrip.SideTrip.find;
import static io.github.rkaushikdev.gussetline.sidetrip.SideTrip.milesOff;
import static io.github.rkaushikdev.gussetline.sidetrip.SideTrip.permitted;
import static io.github.rkaushikdev.gussetline.sidetrip.SideTrip.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SidetripTest {

    /** An LH400 on a day in August 2025, two hours long, leaving at ten unless another hour is given. */
    private static Segment leg(String origin, String destination, int day) {
        return leg(origin, destination, day, 10);
    }

    private static Segment leg(String origin, String destination, int day, int hour) {
        return segment(
                "LH",
                "400",
                origin,
                destination,
                String.format(Locale.ROOT, "2025-08-%02d %02d:00", day, hour),
                String.format(Locale.ROOT, "2025-08-%02d %02d:00", day, hour + 2));
    }

    private static Itinerary journey(Element... elements) {
        return new Itinerary(List.of(elements));
    }

    /** Frankfurt to New York, by way of a week in Vienna. */
    private static Itinerary oneWayWithASideTrip() {
        return journey(leg("FRA", "VIE", 4), leg("VIE", "FRA", 11), leg("FRA", "JFK", 12));
    }

    /** Out by way of Vienna, back by way of Boston: two excursions on a round trip. */
    private static Itinerary twoExcursions() {
        return journey(
                leg("FRA", "VIE", 4),
                leg("VIE", "FRA", 11),
                leg("FRA", "JFK", 12),
                leg("JFK", "BOS", 20),
                leg("BOS", "JFK", 26),
                leg("JFK", "FRA", 28));
    }

    @Test
    void aPlainOneWayHasNoSideTrip() {
        assertThat(find(journey(leg("FRA", "JFK", 4)))).isEmpty();
    }

    @Test
    void aRoundTripIsNotASideTrip() {
        // It comes home, which is what a round trip does, and it prices as one.
        Itinerary made = journey(leg("FRA", "JFK", 4), leg("JFK", "FRA", 18));
        assertThat(made.journeyType()).isSameAs(JourneyType.ROUND_TRIP);
        assertThat(find(made)).isEmpty();
    }

    @Test
    void anExcursionInTheMiddleIsASideTrip() {
        List<SideTrip> trips = find(oneWayWithASideTrip());
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).at()).isEqualTo("FRA");
        assertThat(trips.get(0).turnaround()).isEqualTo("VIE");
        assertThat(trips.get(0).size()).isEqualTo(2);
    }

    @Test
    void aSideTripNamesOnlyThePointsItVisits() {
        assertThat(find(oneWayWithASideTrip()).get(0).points()).containsExactly("VIE");
    }

    @Test
    void aSideTripReadsBackInOneLine() {
        assertThat(find(oneWayWithASideTrip()).get(0)).hasToString("side trip to VIE off FRA");
    }

    @Test
    void aConnectionThroughAPointIsNotASideTrip() {
        // Frankfurt twice in an afternoon is a connection, whatever the shape of
        // the point list says.
        Itinerary made = journey(
                leg("MUC", "FRA", 4, 6), leg("FRA", "VIE", 4, 9), leg("VIE", "FRA", 4, 13), leg("FRA", "JFK", 4, 17));
        assertThat(find(made)).isEmpty();
    }

    @Test
    void theTimeAwayIsWhatDecidesIt() {
        Itinerary quick = journey(leg("FRA", "VIE", 4, 6), leg("VIE", "FRA", 4, 14), leg("FRA", "JFK", 5));
        assertThat(find(quick)).isEmpty();
        assertThat(find(quick, Duration.ofHours(4))).hasSize(1);
    }

    @Test
    void takingTheSideTripOutLeavesAJourneyThatJoinsUp() {
        Itinerary made = oneWayWithASideTrip();
        Itinerary left = without(made, find(made));
        assertThat(left.elements()).extracting(Element::origin).containsExactly("FRA");
        assertThat(left.destination()).isEqualTo("JFK");
    }

    @Test
    void whatIsLeftIsAPlainOneWay() {
        // Not a circle trip and not two round trips, whatever the point list looks
        // like before the excursion comes out.
        Itinerary made = oneWayWithASideTrip();
        assertThat(without(made, find(made)).journeyType()).isSameAs(JourneyType.ONE_WAY);
    }

    @Test
    void takingNothingOutGivesBackTheSameJourney() {
        Itinerary made = oneWayWithASideTrip();
        assertThat(without(made, List.of())).isSameAs(made);
    }

    @Test
    void aJourneyThatIsAllSideTripIsRefused() {
        // Not reachable through find, which never calls the whole journey an
        // excursion. Reachable from a caller that worked the indices out itself.
        Itinerary made = journey(leg("FRA", "VIE", 4), leg("VIE", "FRA", 11));
        SideTrip everything = new SideTrip("FRA", 0, 1, made.elements());
        assertThatThrownBy(() -> without(made, List.of(everything)))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("all side trip");
    }

    @Test
    void aJourneyThatTouchesItsOwnOriginAndCarriesOnHasLeftAgain() {
        // The whole distinction in one shape. Coming back to Frankfurt in the
        // middle is an excursion; coming back at the end is a round trip.
        Itinerary made = journey(leg("FRA", "VIE", 4), leg("VIE", "FRA", 11), leg("FRA", "JFK", 12));
        assertThat(find(made)).hasSize(1);
        Itinerary home = journey(leg("FRA", "VIE", 4), leg("VIE", "FRA", 11));
        assertThat(find(home)).isEmpty();
    }

    @Test
    void twoExcursionsAreTwoSideTrips() {
        assertThat(find(twoExcursions())).extracting(SideTrip::turnaround).containsExactly("VIE", "BOS");
    }

    @Test
    void takingTwoOutLeavesTheRoundTripUnderneath() {
        Itinerary made = twoExcursions();
        Itinerary left = without(made, find(made));
        assertThat(left.journeyType()).isSameAs(JourneyType.ROUND_TRIP);
        assertThat(left.elements()).hasSize(2);
    }

    @Test
    void theFurthestPointOfALongerExcursionIsItsTurnaround() {
        Itinerary made =
                journey(leg("FRA", "VIE", 4), leg("VIE", "PRG", 6), leg("PRG", "FRA", 11), leg("FRA", "JFK", 12));
        List<SideTrip> trips = find(made);
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).points()).containsExactly("VIE", "PRG");
        assertThat(trips.get(0).turnaround()).isEqualTo("PRG");
    }

    @Test
    void anExcursionDrivenOneWayIsStillAnExcursion() {
        Itinerary made =
                journey(leg("FRA", "VIE", 4), surface("VIE", "MUC"), leg("MUC", "FRA", 11), leg("FRA", "JFK", 12));
        List<SideTrip> trips = find(made);
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).turnaround()).isEqualTo("MUC");
    }

    @Test
    void theMilesAnExcursionAccountsForAreCountedOnTheirOwn() {
        // The difference between the mileage a naive check would use and the one
        // the transatlantic component is entitled to.
        Itinerary made = oneWayWithASideTrip();
        List<SideTrip> trips = find(made);
        assertThat(milesOff(made, trips)).isGreaterThan(700);
        assertThat(milesOff(made, List.of())).isZero();
        assertThat(without(made, trips).totalMiles()).isLessThan(made.totalMiles());
    }

    @Test
    void aFareThatPermitsASideTripPermitsIt() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(Combinability.builder("LH", "KLWAP7RT").sideTrip(true).build());
        assertThat(permitted(find(oneWayWithASideTrip()), "LH", "KLWAP7RT", store))
                .isEmpty();
    }

    @Test
    void aFareThatDoesNotPermitOneRefusesIt() {
        CombinabilityStore store = new CombinabilityStore();
        store.add(Combinability.builder("LH", "KLWAP7RT").build());
        assertThat(permitted(find(oneWayWithASideTrip()), "LH", "KLWAP7RT", store))
                .containsExactly("LH KLWAP7RT does not permit a side trip");
    }

    @Test
    void aFareWithNoCategory10FiledRefusesIt() {
        List<String> refusals = permitted(find(oneWayWithASideTrip()), "LH", "KLWAP7RT", new CombinabilityStore());
        assertThat(refusals.get(0)).contains("has no combinability filed");
    }

    @Test
    void aJourneyWithNoExcursionAsksNothingOfCategory10() {
        assertThat(permitted(List.of(), "LH", "KLWAP7RT", new CombinabilityStore()))
                .isEmpty();
    }

    @Test
    void aSideTripThatRunsBackwardsIsRefused() {
        assertThatThrownBy(() -> new SideTrip("FRA", 3, 1, List.of(leg("FRA", "VIE", 4))))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("runs forwards");
    }

    @Test
    void aSideTripThatGoesNowhereIsRefused() {
        assertThatThrownBy(() -> new SideTrip("FRA", 0, 0, List.of()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("goes somewhere");
    }
}
