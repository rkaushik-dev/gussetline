package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulesApplicationTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static Segment outSegment() {
        return segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05");
    }

    private static FareComponent out() {
        return new FareComponent(List.of(outSegment()));
    }

    private static FareComponent codeshareOut() {
        return new FareComponent(List.of(outSegment().withOperatingCarrier("UA")));
    }

    /** Out on a Tuesday, home on a Saturday. */
    private static Itinerary trip() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-08 18:30", "2025-03-09 08:05")));
    }

    private static Context context() {
        return context(out());
    }

    private static Context context(FareComponent component) {
        return new Context(trip(), component, TICKETING);
    }

    private static TimeWindow window(int fromHour, int toHour) {
        return new TimeWindow(LocalTime.of(fromHour, 0), LocalTime.of(toHour, 0));
    }

    @Test
    void aWindowInsideADayCoversItsEnds() {
        TimeWindow window = window(6, 12);
        assertThat(window.covers(LocalTime.of(6, 0))).isTrue();
        assertThat(window.covers(LocalTime.of(12, 0))).isTrue();
        assertThat(window.covers(LocalTime.of(5, 59))).isFalse();
        assertThat(window.covers(LocalTime.of(12, 1))).isFalse();
        assertThat(window.wrapsMidnight()).isFalse();
    }

    @Test
    void aWindowThatWrapsMidnightCoversBothSides() {
        TimeWindow window = window(22, 4);
        assertThat(window.wrapsMidnight()).isTrue();
        assertThat(window.covers(LocalTime.of(23, 30))).isTrue();
        assertThat(window.covers(LocalTime.of(2, 0))).isTrue();
        assertThat(window.covers(LocalTime.of(12, 0))).isFalse();
    }

    @Test
    void aWindowReadsBackInTimetableForm() {
        assertThat(window(6, 12)).hasToString("0600 to 1200");
    }

    @Test
    void aDayAndTimeFilingWithNothingInItDoesNotApply() {
        assertThat(new DayAndTime().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aDayThatIsNotADayIsRefused() {
        assertThatThrownBy(() -> new DayAndTime(List.of("funday"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aJourneyOnThePermittedDaysPasses() {
        DayAndTime rule = new DayAndTime(List.of("tue", "sat"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aSectorOnARefusedDayFailsAndNamesIt() {
        DayAndTime rule = new DayAndTime(List.of("tue"));
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("sector", "LH401", "day", "sat"));
    }

    @Test
    void anOutboundOnlyFilingIgnoresTheReturn() {
        DayAndTime rule = new DayAndTime(List.of("tue"), Optional.empty(), true);
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aDepartureInsideTheTimeWindowPasses() {
        DayAndTime rule = new DayAndTime(window(6, 20));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aDepartureOutsideTheTimeWindowFails() {
        DayAndTime rule = new DayAndTime(window(6, 12));
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("sector", "LH401").containsEntry("window", "0600 to 1200");
    }

    @Test
    void daysAndAWindowBothHaveToHold() {
        DayAndTime rule = new DayAndTime(List.of("tue", "sat"), window(6, 12));
        assertThat(rule.evaluate(context()).failed()).isTrue();
    }

    @Test
    void aFlightApplicationWithNothingInItDoesNotApply() {
        assertThat(new FlightApplication().evaluate(context()).applied()).isFalse();
    }

    @Test
    void ownMetalOnlyHasToSayWhose() {
        assertThatThrownBy(() -> FlightApplication.builder().ownMetalOnly(true).build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aFlightOnTheListPasses() {
        FlightApplication rule = new FlightApplication(List.of("LH400", "LH401"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aFlightOffTheListFailsAndSaysWhatWasPermitted() {
        FlightApplication rule = new FlightApplication(List.of("LH440"));
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("permitted", List.of("LH440"));
    }

    @Test
    void theFlightListIsCaseInsensitive() {
        FlightApplication rule = new FlightApplication(List.of("lh400"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aCodeshareIsRefusedWhenTheFareBansThem() {
        FlightApplication rule = FlightApplication.builder().noCodeshare(true).build();
        Outcome outcome = rule.evaluate(context(codeshareOut()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("operated_by", "UA");
    }

    @Test
    void aFlightTheCarrierOperatesItselfIsNotACodeshare() {
        FlightApplication rule = FlightApplication.builder().noCodeshare(true).build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void ownMetalRefusesTheFilingCarriersOwnFlightNumberOnSomebodyElsesAircraft() {
        FlightApplication rule =
                FlightApplication.builder().ownMetalOnly(true).carrier("LH").build();
        Outcome outcome = rule.evaluate(context(codeshareOut()));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("operated_by", "UA");
    }

    @Test
    void ownMetalPassesWhenTheFilingCarrierFliesIt() {
        FlightApplication rule =
                FlightApplication.builder().ownMetalOnly(true).carrier("LH").build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void flightApplicationReadsTheComponentAndNotTheJourney() {
        // The return is not on the list and the component does not contain it.
        FlightApplication rule = new FlightApplication(List.of("LH400"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }
}
