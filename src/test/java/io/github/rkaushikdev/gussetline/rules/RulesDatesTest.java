package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.rules.Dates.rangesFrom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesDatesTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary trip() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static Context context() {
        return context(trip());
    }

    private static Context context(Itinerary itinerary) {
        return new Context(itinerary, out(), TICKETING);
    }

    private static List<DateRange> ranges(String... ends) {
        List<List<String>> pairs = new ArrayList<>();
        for (int i = 0; i < ends.length; i += 2) {
            pairs.add(List.of(ends[i], ends[i + 1]));
        }
        return rangesFrom(pairs);
    }

    @Test
    void aRangeClosesAfterItOpens() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 5, 1)))
                .isInstanceOf(ValidationError.class);
        assertThat(new DateRange(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 1)).covers(LocalDate.of(2025, 5, 1)))
                .isTrue();
    }

    @Test
    void aRangeIncludesBothEnds() {
        DateRange window = new DateRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31));
        assertThat(window.covers(LocalDate.of(2025, 3, 1))).isTrue();
        assertThat(window.covers(LocalDate.of(2025, 3, 31))).isTrue();
        assertThat(window.covers(LocalDate.of(2025, 2, 28))).isFalse();
        assertThat(window.covers(LocalDate.of(2025, 4, 1))).isFalse();
    }

    @Test
    void twoRangesOverlapWhenTheyTouch() {
        DateRange left = new DateRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31));
        assertThat(left.overlaps(new DateRange(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 30))))
                .isTrue();
        assertThat(left.overlaps(new DateRange(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30))))
                .isFalse();
    }

    @Test
    void aRangeReadsBackAsItsEnds() {
        assertThat(new DateRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31)))
                .hasToString("2025-03-01 to 2025-03-31");
    }

    @Test
    void rangesAreReadFromTheLoaderShape() {
        List<DateRange> windows = ranges("2025-03-01", "2025-03-31", "2025-06-01", "2025-08-31");
        assertThat(windows).hasSize(2);
        assertThat(windows.get(1).covers(LocalDate.of(2025, 7, 4))).isTrue();
    }

    @Test
    void aSeasonWithNothingFiledDoesNotApply() {
        assertThat(new Seasonality().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aDepartureInsideTheSeasonPasses() {
        Seasonality rule = new Seasonality(ranges("2025-03-01", "2025-03-31"), "low");
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.details()).containsEntry("window", "2025-03-01 to 2025-03-31");
    }

    @Test
    void aDepartureOutsideEveryWindowFailsAndListsThem() {
        Seasonality rule = new Seasonality(ranges("2025-06-01", "2025-08-31", "2025-12-15", "2025-12-31"), "high");
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details().get("windows")).asInstanceOf(LIST).hasSize(2);
        assertThat(outcome.details()).containsEntry("departure", "2025-03-04");
    }

    @Test
    void seasonalityReadsTheComponentAndNotTheJourney() {
        // The component leaves in March and the journey comes home in April. The
        // season is checked on the component, so April is not consulted.
        Itinerary late = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-04-18 18:30", "2025-04-19 08:05")));
        Seasonality rule = new Seasonality(ranges("2025-03-01", "2025-03-31"));
        assertThat(rule.evaluate(context(late)).failed()).isFalse();
    }

    @Test
    void aBlackoutWithNothingFiledDoesNotApply() {
        assertThat(new Blackout().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aJourneyClearOfTheBlackoutPasses() {
        Blackout rule = new Blackout(ranges("2025-12-20", "2025-12-27"));
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aBlackoutOnTheOutboundFails() {
        Blackout rule = new Blackout(ranges("2025-03-01", "2025-03-07"));
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("sector", "LH400");
    }

    @Test
    void aBlackoutOnTheReturnAloneStillFails() {
        // This is the one a component level check misses. The outbound is clear
        // and the journey is not.
        Blackout rule = new Blackout(ranges("2025-03-15", "2025-03-20"));
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("sector", "LH401");
    }

    @Test
    void travelDatesWithNothingFiledDoNotApply() {
        assertThat(new TravelDates().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aDepartureWindowThatClosesBeforeItOpensIsRefused() {
        assertThatThrownBy(() -> TravelDates.builder()
                        .firstDeparture(LocalDate.of(2025, 6, 1))
                        .lastDeparture(LocalDate.of(2025, 5, 1))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void travelStartingTooEarlyFails() {
        TravelDates rule =
                TravelDates.builder().firstDeparture(LocalDate.of(2025, 4, 1)).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("earliest", "2025-04-01");
    }

    @Test
    void travelStartingTooLateFails() {
        TravelDates rule =
                TravelDates.builder().lastDeparture(LocalDate.of(2025, 2, 28)).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("latest", "2025-02-28");
    }

    @Test
    void travelInsideTheDepartureWindowPasses() {
        TravelDates rule = TravelDates.builder()
                .firstDeparture(LocalDate.of(2025, 1, 1))
                .lastDeparture(LocalDate.of(2025, 6, 30))
                .build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void travelThatFinishesTooLateFailsOnTheCompletionDate() {
        TravelDates rule =
                TravelDates.builder().completeBy(LocalDate.of(2025, 3, 10)).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("ends", "2025-03-19");
    }

    @Test
    void travelThatFinishesInTimePasses() {
        TravelDates rule =
                TravelDates.builder().completeBy(LocalDate.of(2025, 3, 31)).build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void theDepartureWindowIsCheckedBeforeTheCompletionDate() {
        TravelDates rule = TravelDates.builder()
                .firstDeparture(LocalDate.of(2025, 4, 1))
                .completeBy(LocalDate.of(2025, 3, 10))
                .build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.details()).containsEntry("earliest", "2025-04-01");
    }
}
