package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.reports.Savings.build;
import static io.github.rkaushikdev.gussetline.reports.Savings.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.reports.Savings.Line;
import io.github.rkaushikdev.gussetline.rules.Residual;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportsSavingsTest {

    private static final LocalDate JUNE = LocalDate.of(2025, 6, 10);
    private static final LocalDate JULY = LocalDate.of(2025, 7, 10);

    private static Recommendation made() {
        return made("90.00", JUNE, State.OPEN, "anna");
    }

    private static Recommendation made(String benefit) {
        return made(benefit, JUNE, State.OPEN, "anna");
    }

    private static Recommendation made(String benefit, State state) {
        return made(benefit, JUNE, state, "anna");
    }

    private static Recommendation made(State state) {
        return made("90.00", JUNE, state, "anna");
    }

    private static Recommendation made(State state, String by) {
        return made("90.00", JUNE, state, by);
    }

    private static Recommendation made(LocalDate raisedOn) {
        return made("90.00", raisedOn, State.OPEN, "anna");
    }

    /** A recommendation raised on a day and settled that same day into a state, by someone. */
    private static Recommendation made(String benefit, LocalDate raisedOn, State state, String by) {
        Recommendation row = new Recommendation(
                "220-1234567890",
                "FRA-NYC-2025-08-04-Y",
                raisedOn,
                eur("840.00"),
                eur("700.00"),
                eur("50.00"),
                eur(benefit),
                Residual.REFUNDABLE);
        switch (state) {
            case TAKEN -> row.take(raisedOn, by);
            case KILLED -> row.kill(raisedOn, by);
            case EXPIRED -> row.expire(raisedOn);
            case SUPERSEDED -> row.withdraw(raisedOn);
            case OPEN -> {}
        }
        return row;
    }

    private static List<String> labels(List<Line> lines) {
        return lines.stream().map(Line::label).toList();
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void anEmptyReportIsAllZeroes() {
        Savings report = build(List.of(), "EUR");
        assertThat(report.overall().raised()).isZero();
        assertThat(report.overall().found()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().takeRate()).isEqualByComparingTo("0");
        assertThat(report.byMonth()).isEmpty();
    }

    @Test
    void whatWasFoundCountsEveryRecommendation() {
        Savings report = build(List.of(made("90.00"), made("40.00", State.KILLED)), "EUR");
        assertThat(report.overall().found()).isEqualTo(eur("130.00"));
        assertThat(report.overall().raised()).isEqualTo(2);
    }

    @Test
    void whatWasTakenCountsOnlyTheTakenOnes() {
        Savings report = build(List.of(made("90.00", State.TAKEN), made("40.00", State.KILLED)), "EUR");
        assertThat(report.overall().realised()).isEqualTo(eur("90.00"));
    }

    @Test
    void whatWasMissedCountsOnlyTheExpiredOnes() {
        Savings report = build(
                List.of(made("90.00", State.TAKEN), made("40.00", State.KILLED), made("70.00", State.EXPIRED)), "EUR");
        assertThat(report.overall().missed()).isEqualTo(eur("70.00"));
    }

    @Test
    void aKilledRecommendationIsNotAMiss() {
        // Somebody looked and said no, usually because the customer wanted the
        // flight they had. Counting that as a miss blames the desk for a correct
        // decision.
        Savings report = build(List.of(made("40.00", State.KILLED)), "EUR");
        assertThat(report.overall().missed()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().killed()).isEqualTo(1);
    }

    @Test
    void anOpenRecommendationIsInFoundAndNowhereElse() {
        Savings report = build(List.of(made("90.00")), "EUR");
        assertThat(report.overall().found()).isEqualTo(eur("90.00"));
        assertThat(report.overall().realised()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().missed()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().stillOpen()).isEqualTo(1);
    }

    @Test
    void theTakeRateIsMeasuredAgainstTheOnesAPersonDecided() {
        Savings report = build(List.of(made(State.TAKEN), made(State.KILLED), made()), "EUR");
        assertThat(report.overall().settled()).isEqualTo(2);
        assertThat(report.overall().decided()).isEqualTo(2);
        assertThat(report.overall().takeRate()).isEqualByComparingTo("0.50");
    }

    @Test
    void nothingDecidedGivesNoTakeRateRatherThanADivision() {
        assertThat(build(List.of(made()), "EUR").overall().takeRate()).isEqualByComparingTo("0");
    }

    @Test
    void aSupersededRecommendationIsSettledButNobodyDecidedIt() {
        Savings report = build(List.of(made(State.TAKEN), made(State.SUPERSEDED)), "EUR");
        assertThat(report.overall().settled()).isEqualTo(2);
        assertThat(report.overall().decided()).isEqualTo(1);
        assertThat(report.overall().superseded()).isEqualTo(1);
        assertThat(report.overall().takeRate()).isEqualByComparingTo("1.00");
    }

    @Test
    void aSupersededRecommendationIsInFoundAndInNothingElse() {
        Savings report = build(List.of(made("40.00", State.SUPERSEDED)), "EUR");
        assertThat(report.overall().found()).isEqualTo(eur("40.00"));
        assertThat(report.overall().realised()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().missed()).isEqualTo(Money.zero("EUR"));
        assertThat(report.overall().stillOpen()).isZero();
    }

    @Test
    void aSupersededRecommendationBelongsToNoAgent() {
        Savings report = build(List.of(made(State.SUPERSEDED), made(State.TAKEN, "anna")), "EUR");
        assertThat(labels(report.byAgent())).containsExactly("anna");
    }

    @Test
    void theCountsStillAddUpToWhatWasRaised() {
        Savings report = build(
                List.of(made(State.TAKEN), made(State.KILLED), made(State.EXPIRED), made(State.SUPERSEDED), made()),
                "EUR");
        Line line = report.overall();
        assertThat(line.settled() + line.stillOpen()).isEqualTo(line.raised());
    }

    @Test
    void theRowsAreGroupedByMonth() {
        Savings report = build(List.of(made(JUNE), made(JULY), made(JULY)), "EUR");
        assertThat(labels(report.byMonth())).containsExactly("2025-06", "2025-07");
        assertThat(report.byMonth().stream().map(Line::raised).toList()).containsExactly(1, 2);
    }

    @Test
    void theRowsAreGroupedByWhoeverSettledThem() {
        Savings report = build(
                List.of(made(State.TAKEN, "anna"), made(State.TAKEN, "bruno"), made(State.KILLED, "anna")), "EUR");
        assertThat(labels(report.byAgent())).containsExactly("anna", "bruno");
        assertThat(report.byAgent().stream().map(Line::taken).toList()).containsExactly(1, 1);
    }

    @Test
    void anOpenRecommendationBelongsToNoAgent() {
        Savings report = build(List.of(made(), made(State.TAKEN, "anna")), "EUR");
        assertThat(labels(report.byAgent())).containsExactly("anna");
    }

    @Test
    void anExpiredOneBelongsToTheClockAndIsReportedAsSuch() {
        Savings report = build(List.of(made(State.EXPIRED)), "EUR");
        assertThat(labels(report.byAgent())).containsExactly("the clock");
    }

    @Test
    void theWindowNarrowsWhatIsCounted() {
        List<Recommendation> rows = List.of(made(JUNE), made(JULY));
        assertThat(build(rows, "EUR", Optional.of(JULY), Optional.empty())
                        .overall()
                        .raised())
                .isEqualTo(1);
        assertThat(build(rows, "EUR", Optional.empty(), Optional.of(JUNE))
                        .overall()
                        .raised())
                .isEqualTo(1);
        assertThat(build(rows, "EUR", Optional.of(JUNE), Optional.of(JULY))
                        .overall()
                        .raised())
                .isEqualTo(2);
    }

    @Test
    void aWindowWithNothingInItReportsZeroes() {
        Savings report = build(List.of(made(JUNE)), "EUR", Optional.of(LocalDate.of(2025, 9, 1)), Optional.empty());
        assertThat(report.overall().raised()).isZero();
    }

    @Test
    void aLineReadsBackInOneRow() {
        Savings report = build(List.of(made("90.00", State.TAKEN)), "EUR");
        assertThat(report.overall()).hasToString("overall          raised    1 taken    1 worth 90.00 EUR");
    }

    @Test
    void theReportReadsBackAsItsOverallLine() {
        Savings report = build(List.of(made("90.00", State.TAKEN)), "EUR");
        assertThat(report).hasToString(report.overall().toString());
    }

    @Test
    void theRenderedReportCarriesAllThreeNumbers() {
        Savings report = build(List.of(made("90.00", State.TAKEN), made("70.00", State.EXPIRED), made("40.00")), "EUR");
        String out = render(report);
        assertThat(out).contains("found      200.00 EUR");
        assertThat(out).contains("taken      90.00 EUR");
        assertThat(out).contains("missed     70.00 EUR");
        assertThat(out).contains("take rate  1.00 over 1 decided");
    }

    @Test
    void theRenderedReportLeavesOutTheGroupingsWhenThereAreNone() {
        String out = render(build(List.of(), "EUR"));
        assertThat(out).doesNotContain("by month");
        assertThat(out).doesNotContain("by agent");
    }

    @Test
    void theRenderedReportShowsTheGroupingsWhenThereAreSome() {
        String out = render(build(List.of(made(State.TAKEN, "anna")), "EUR"));
        assertThat(out).contains("by month");
        assertThat(out).contains("by agent");
        assertThat(out).contains("anna");
    }
}
