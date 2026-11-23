package io.github.rkaushikdev.gussetline.itinerary;

import static io.github.rkaushikdev.gussetline.Fixtures.frankfurtNewYork;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.Fixtures.withAConnection;
import static io.github.rkaushikdev.gussetline.Fixtures.withAStopover;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItineraryTest {

    @Test
    void anItineraryNeedsAnElement() {
        assertThatThrownBy(() -> new Itinerary(List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void anItineraryThatDoesNotJoinUpIsRefused() {
        ValidationError caught = catchThrowableOfType(
                ValidationError.class,
                () -> new Itinerary(List.of(
                        segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        segment("LH", "401", "BOS", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"))));
        assertThat(caught.details()).containsEntry("arrives", "JFK").containsEntry("departs", "BOS");
    }

    @Test
    void aSurfaceSectorGoesSomewhere() {
        assertThatThrownBy(() -> new SurfaceSector("JFK", "JFK")).isInstanceOf(ValidationError.class);
    }

    @Test
    void anItineraryDoesNotOpenOrCloseOnTheGround() {
        assertThatThrownBy(() -> new Itinerary(List.of(
                        surface("FRA", "MUC"),
                        segment("LH", "400", "MUC", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new Itinerary(List.of(
                        segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                        surface("JFK", "BOS"))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void endsAndPoints() {
        Itinerary trip = frankfurtNewYork();
        assertThat(trip.origin()).isEqualTo("FRA");
        assertThat(trip.destination()).isEqualTo("FRA");
        assertThat(trip.points()).containsExactly("FRA", "JFK", "FRA");
    }

    @Test
    void carriersAreListedOnceInOrder() {
        assertThat(withAConnection().carriers()).containsExactly("LH");
    }

    @Test
    void aShortWaitIsAConnection() {
        Itinerary trip = withAConnection();
        List<Break> breaks = trip.breaks();
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).point()).isEqualTo("LHR");
        assertThat(breaks.get(0).stopover()).isFalse();
        assertThat(trip.connections()).containsExactly("LHR");
        assertThat(trip.stopovers()).isEmpty();
    }

    @Test
    void aLongWaitIsAStopover() {
        Itinerary trip = withAStopover();
        assertThat(trip.stopovers()).containsExactly("LHR");
        assertThat(trip.connections()).isEmpty();
    }

    @Test
    void theBreakCarriesTheGroundTimeInMinutes() {
        // Lands 07:40 London, leaves 11:05 London, and London is one hour behind Frankfurt so the
        // wall clocks are already in the right place.
        assertThat(withAConnection().breaks().get(0).minutes()).isEqualTo(205);
    }

    @Test
    void theThresholdIsFourHoursDomesticallyAndADayInternationally() {
        Itinerary domestic = new Itinerary(List.of(
                segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
                segment("UA", "200", "ORD", "LAX", "2025-03-04 15:00", "2025-03-04 17:30")));
        assertThat(domestic.stopoverThreshold().toSeconds()).isEqualTo(4 * 3600);
        assertThat(domestic.stopovers()).containsExactly("ORD");

        Itinerary international = new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 15:00", "2025-03-04 18:00")));
        assertThat(international.stopoverThreshold().toSeconds()).isEqualTo(24 * 3600);
        assertThat(international.stopovers()).isEmpty();
    }

    @Test
    void anInternationalJourneyUsesTheLongThresholdForItsDomesticBreaks() {
        Itinerary trip = new Itinerary(List.of(
                segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
                segment("UA", "900", "ORD", "FRA", "2025-03-04 18:00", "2025-03-05 09:20")));
        assertThat(trip.isInternational()).isTrue();
        assertThat(trip.stopovers()).isEmpty();
    }

    @Test
    void aSurfaceSectorIsAStopoverHoweverShortItIs() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                surface("JFK", "BOS"),
                segment("LH", "423", "BOS", "FRA", "2025-03-04 17:00", "2025-03-05 06:20")));
        assertThat(trip.hasSurface()).isTrue();
        assertThat(trip.stopovers()).contains("JFK");
    }

    @Test
    void aSectorThatLeavesBeforeTheLastOneLandsIsRefused() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 06:00", "2025-03-04 09:00")));
        assertThatThrownBy(trip::breaks).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPlainOutAndBackIsARoundTrip() {
        assertThat(frankfurtNewYork().journeyType()).isSameAs(JourneyType.ROUND_TRIP);
    }

    @Test
    void outAndBackThroughAConnectionIsStillARoundTrip() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
                segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
                segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35")));
        assertThat(trip.journeyType()).isSameAs(JourneyType.ROUND_TRIP);
    }

    @Test
    void aLoopThroughTwoDifferentTurnPointsIsACircleTrip() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
                segment("LH", "457", "LAX", "FRA", "2025-03-12 15:30", "2025-03-13 11:20")));
        assertThat(trip.journeyType()).isSameAs(JourneyType.CIRCLE_TRIP);
    }

    @Test
    void aSingleSectorIsAOneWay() {
        Itinerary trip =
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
        assertThat(trip.journeyType()).isSameAs(JourneyType.ONE_WAY);
    }

    @Test
    void aJourneyHomeToADifferentAirportIsAnOpenJaw() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "417", "JFK", "MUC", "2025-03-18 18:30", "2025-03-19 08:05")));
        assertThat(trip.journeyType()).isSameAs(JourneyType.OPEN_JAW);
    }

    @Test
    void theTurnaroundIsTheFurthestPointAndNotTheLastOne() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("AA", "100", "JFK", "LAX", "2025-03-08 08:00", "2025-03-08 11:15"),
                segment("AA", "200", "LAX", "ORD", "2025-03-12 07:00", "2025-03-12 13:00"),
                segment("LH", "431", "ORD", "FRA", "2025-03-14 16:20", "2025-03-15 07:45")));
        assertThat(trip.turnaround()).isEqualTo("LAX");
    }

    @Test
    void metroMembersCloseARoundTrip() {
        Itinerary trip = new Itinerary(List.of(
                segment("BA", "112", "LHR", "JFK", "2025-03-04 08:25", "2025-03-04 11:20"),
                segment("BA", "184", "JFK", "LGW", "2025-03-18 21:00", "2025-03-19 09:05")));
        assertThat(trip.journeyType()).isSameAs(JourneyType.ROUND_TRIP);
    }

    @Test
    void totalMilesAddsTheSurfaceSectorIn() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                surface("JFK", "BOS"),
                segment("LH", "423", "BOS", "FRA", "2025-03-06 17:00", "2025-03-07 06:20")));
        assertThat(trip.totalMiles()).isGreaterThan(7000);
    }

    @Test
    void stringFormShowsTheSurfaceSectorWithSlashes() {
        Itinerary trip = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                surface("JFK", "BOS"),
                segment("LH", "423", "BOS", "FRA", "2025-03-06 17:00", "2025-03-07 06:20")));
        assertThat(trip.toString()).contains("//JFKBOS");
    }

    @Test
    void lengthAndIteration() {
        Itinerary trip = frankfurtNewYork();
        assertThat(trip.size()).isEqualTo(2);
        List<String> origins = new ArrayList<>();
        for (Element element : trip) {
            origins.add(element.origin());
        }
        assertThat(origins).containsExactly("FRA", "JFK");
    }

    private static Itinerary through(String point, String lands, String leaves, String from, String to) {
        return new Itinerary(List.of(
                segment("LH", "1", from, point, "2025-03-04 06:00", "2025-03-04 " + lands),
                segment("LH", "2", point, to, "2025-03-04 " + leaves, "2025-03-04 23:00")));
    }

    @Test
    void aConnectionUnderTheAirportsMinimumIsNamed() {
        // Heathrow wants an hour and a half when either sector crosses a border.
        assertThat(through("LHR", "08:00", "09:00", "FRA", "JFK").shortConnections())
                .containsExactly("LHR 60 minutes, 90 needed");
        assertThat(through("LHR", "08:00", "09:30", "FRA", "JFK").shortConnections())
                .isEmpty();
    }

    @Test
    void aDomesticConnectionReadsTheShorterMinimum() {
        // Munich asks thirty minutes between two German sectors and forty five across a border.
        assertThat(through("MUC", "08:00", "08:40", "FRA", "HAM").shortConnections())
                .isEmpty();
        assertThat(through("MUC", "08:00", "08:40", "FRA", "VIE").shortConnections())
                .containsExactly("MUC 40 minutes, 45 needed");
    }

    @Test
    void anAirportWithNothingFiledReadsTheCommonMinimums() {
        assertThat(MinimumConnectingTime.between(
                        segment("UA", "1", "LAX", "DEN", "2025-03-04 06:00", "2025-03-04 09:00"),
                        segment("UA", "2", "DEN", "SFO", "2025-03-04 10:00", "2025-03-04 11:30")))
                .isEqualTo(MinimumConnectingTime.DOMESTIC_DEFAULT);
        assertThat(MinimumConnectingTime.between(
                        segment("UA", "1", "LAX", "DEN", "2025-03-04 06:00", "2025-03-04 09:00"),
                        segment("AC", "2", "DEN", "YYZ", "2025-03-04 10:00", "2025-03-04 15:30")))
                .isEqualTo(MinimumConnectingTime.INTERNATIONAL_DEFAULT);
    }

    @Test
    void aStopoverOrASurfaceBreakIsNoConnectionToMake() {
        Itinerary overland = new Itinerary(List.of(
                segment("LH", "1", "FRA", "LHR", "2025-03-04 06:00", "2025-03-04 07:00"),
                surface("LHR", "LGW"),
                segment("BA", "2", "LGW", "JFK", "2025-03-04 07:10", "2025-03-04 10:00")));
        assertThat(overland.shortConnections()).isEmpty();
        assertThat(overland.connectionAfter(0)).isEmpty();
        assertThat(overland.connectionAfter(1)).isEmpty();
        assertThat(overland.connectionAfter(-1)).isEmpty();
        assertThat(withAConnection().connectionAfter(0)).map(Break::point).contains("LHR");
        assertThat(withAConnection().connectionAfter(1)).isEmpty();
    }
}
