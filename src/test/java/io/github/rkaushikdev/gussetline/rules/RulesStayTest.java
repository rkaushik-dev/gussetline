package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.rules.Stay.crossesWeekday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesStayTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary trip(String backOut, String backIn) {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", backOut, backIn)));
    }

    private static Context context() {
        return context("2025-03-18 18:30", "2025-03-19 08:05");
    }

    private static Context context(String backOut, String backIn) {
        return new Context(trip(backOut, backIn), out(), TICKETING);
    }

    private static Context oneWayContext() {
        return new Context(
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                out(),
                TICKETING);
    }

    private static MaximumStay maximumDays(int days) {
        return MaximumStay.builder().days(days).build();
    }

    private static MaximumStay maximumMonths(int months) {
        return MaximumStay.builder().months(months).build();
    }

    @Test
    void aWeekOrMoreCoversEveryWeekday() {
        for (int day = 0; day < 7; day++) {
            assertThat(crossesWeekday(LocalDate.of(2025, 3, 4), LocalDate.of(2025, 3, 11), day))
                    .as("weekday %d", day)
                    .isTrue();
        }
    }

    @Test
    void aShortStayCoversOnlyTheDaysItTouches() {
        // Tuesday the fourth to Friday the seventh.
        LocalDate start = LocalDate.of(2025, 3, 4);
        LocalDate end = LocalDate.of(2025, 3, 7);
        assertThat(crossesWeekday(start, end, 1)).isTrue();
        assertThat(crossesWeekday(start, end, 3)).isTrue();
        assertThat(crossesWeekday(start, end, 4)).isFalse();
        assertThat(crossesWeekday(start, end, 6)).isFalse();
    }

    @Test
    void aStayThatEndsBeforeItStartsCoversNothing() {
        assertThat(crossesWeekday(LocalDate.of(2025, 3, 7), LocalDate.of(2025, 3, 4), 1))
                .isFalse();
        assertThat(crossesWeekday(LocalDate.of(2025, 3, 4), LocalDate.of(2025, 3, 4), 1))
                .isFalse();
    }

    @Test
    void aFilingWithNothingInItDoesNotApply() {
        assertThat(new MinimumStay().evaluate(context()).applied()).isFalse();
        assertThat(new MaximumStay().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aNegativeOrZeroPeriodIsRefused() {
        assertThatThrownBy(() -> new MinimumStay(-1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> maximumDays(0)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> maximumMonths(0)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new MinimumStay("funday")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aOneWayHasNoStayToMeasure() {
        assertThat(new MinimumStay(7).evaluate(oneWayContext()).failed()).isFalse();
        assertThat(maximumDays(7).evaluate(oneWayContext()).failed()).isFalse();
    }

    @Test
    void aLongEnoughStayPassesAndSaysHowLong() {
        Outcome outcome = new MinimumStay(7).evaluate(context());
        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.details()).containsEntry("nights", 14);
    }

    @Test
    void aShortStayFailsAndSaysByHowMuch() {
        Outcome outcome = new MinimumStay(7).evaluate(context("2025-03-07 18:30", "2025-03-08 08:05"));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("needed", 7, "had", 3));
    }

    @Test
    void aStayExactlyOnTheMinimumPasses() {
        assertThat(new MinimumStay(3)
                        .evaluate(context("2025-03-07 18:30", "2025-03-08 08:05"))
                        .failed())
                .isFalse();
    }

    @Test
    void theSundayRuleIsNotANumberOfDays() {
        // Out Tuesday the fourth, home Friday the seventh. Three nights and no
        // Sunday in them.
        Context shortStay = context("2025-03-07 18:30", "2025-03-08 08:05");
        assertThat(new MinimumStay("sun").evaluate(shortStay).failed()).isTrue();
        // Out Tuesday, home Monday the tenth. Six nights, and a Sunday.
        Context across = context("2025-03-10 18:30", "2025-03-11 08:05");
        assertThat(new MinimumStay("sun").evaluate(across).failed()).isFalse();
    }

    @Test
    void aLongerStayCanStillMissTheWeekdayItWants() {
        // Out Tuesday the fourth, home Saturday the eighth. Four nights, no Sunday.
        assertThat(new MinimumStay("sun")
                        .evaluate(context("2025-03-08 18:30", "2025-03-09 08:05"))
                        .failed())
                .isTrue();
    }

    @Test
    void aWeekdayIsReadFromThreeLettersInAnyCase() {
        assertThat(new MinimumStay("SUNDAY").evaluate(context()).failed()).isFalse();
        assertThat(new MinimumStay(" sun ").evaluate(context()).failed()).isFalse();
    }

    @Test
    void daysAndAWeekdayBothHaveToBeSatisfied() {
        MinimumStay rule = new MinimumStay(2, "sun");
        // Three nights, Friday to Monday, so the days pass and the Sunday passes.
        assertThat(rule.evaluate(context("2025-03-10 18:30", "2025-03-11 08:05"))
                        .failed())
                .isFalse();
        // Three nights, Tuesday to Friday, so the days pass and the Sunday fails.
        assertThat(rule.evaluate(context("2025-03-07 18:30", "2025-03-08 08:05"))
                        .failed())
                .isTrue();
    }

    @Test
    void aMaximumInDaysCountsFromTheStartOfTheJourney() {
        assertThat(maximumDays(30).limitFrom(LocalDate.of(2025, 3, 4))).isEqualTo(LocalDate.of(2025, 4, 3));
    }

    @Test
    void aMaximumInMonthsLandsOnTheSameDayNumber() {
        assertThat(maximumMonths(3).limitFrom(LocalDate.of(2025, 3, 4))).isEqualTo(LocalDate.of(2025, 6, 4));
        assertThat(maximumMonths(12).limitFrom(LocalDate.of(2025, 3, 4))).isEqualTo(LocalDate.of(2026, 3, 4));
    }

    @Test
    void aMonthThatIsTooShortTakesItsLastDay() {
        assertThat(maximumMonths(1).limitFrom(LocalDate.of(2025, 1, 31))).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(maximumMonths(1).limitFrom(LocalDate.of(2025, 3, 31))).isEqualTo(LocalDate.of(2025, 4, 30));
    }

    @Test
    void aReturnInsideTheMaximumPasses() {
        assertThat(maximumMonths(1).evaluate(context()).failed()).isFalse();
    }

    @Test
    void aReturnPastTheMaximumFailsAndNamesBothDates() {
        Outcome outcome = maximumDays(7).evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("latest", "2025-03-11", "returned", "2025-03-18"));
    }

    @Test
    void aMaximumMeasuredFromTheTurnaroundIsADifferentDate() {
        MaximumStay fromOrigin = maximumDays(10);
        MaximumStay fromTurn =
                MaximumStay.builder().days(10).fromTurnaround(true).build();
        assertThat(fromOrigin.evaluate(context()).failed()).isTrue();
        assertThat(fromTurn.evaluate(context()).failed()).isTrue();
        assertThat(fromOrigin.limitFrom(LocalDate.of(2025, 3, 4))).isEqualTo(LocalDate.of(2025, 3, 14));
    }

    @Test
    void daysAndMonthsTogetherTakeTheLaterLimit() {
        MaximumStay rule = MaximumStay.builder().days(7).months(6).build();
        assertThat(rule.limitFrom(LocalDate.of(2025, 3, 4))).isEqualTo(LocalDate.of(2025, 9, 4));
    }
}
