package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.reports.Filing.build;
import static io.github.rkaushikdev.gussetline.reports.Filing.identity;
import static io.github.rkaushikdev.gussetline.reports.Filing.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.reports.Filing.Amendment;
import io.github.rkaushikdev.gussetline.reports.Filing.Move;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReportsFilingTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2025, 1, 1);
    private static final LocalDate DISCONTINUE = LocalDate.of(2025, 12, 31);

    /** A filed LH fare from Frankfurt to New York on KLWAP7RT at 420.00 EUR, unless told otherwise. */
    private static FareRow fare() {
        return fare("KLWAP7RT", "420.00", row -> {});
    }

    private static FareRow fare(String basis) {
        return fare(basis, "420.00", row -> {});
    }

    private static FareRow fare(String basis, String amount) {
        return fare(basis, amount, row -> {});
    }

    private static FareRow fare(Consumer<FareRow> over) {
        return fare("KLWAP7RT", "420.00", over);
    }

    /** The fare at another amount. */
    private static FareRow costing(String amount) {
        return fare("KLWAP7RT", amount, row -> {});
    }

    private static FareRow costing(String amount, Consumer<FareRow> over) {
        return fare("KLWAP7RT", amount, over);
    }

    private static FareRow fare(String basis, String amount, Consumer<FareRow> over) {
        FareRow made = new FareRow();
        made.setCarrier("LH");
        made.setOrigin("FRA");
        made.setDestination("NYC");
        made.setFareBasis(basis);
        made.setAmount(new BigDecimal(amount));
        made.setCurrency("EUR");
        made.setOwrt("2");
        made.setGlobalIndicator("AT");
        made.setRuleNumber("0100");
        made.setRoutingNumber("0011");
        made.setTariffNumber("1");
        made.setFootnote("");
        made.setCabin("Y");
        made.setFareType("published");
        made.setEffective(EFFECTIVE);
        made.setDiscontinue(DISCONTINUE);
        made.setFirstTravel(EFFECTIVE);
        made.setLastTravel(DISCONTINUE);
        over.accept(made);
        return made;
    }

    /** Forty, or however many, fares on bases numbered from K000, at one amount. */
    private static List<FareRow> numbered(int count, String amount) {
        return IntStream.range(0, count)
                .mapToObj(index -> fare(String.format(Locale.ROOT, "K%03d", index), amount))
                .toList();
    }

    private static List<String> bases(List<Move> moves) {
        return moves.stream().map(move -> move.fare().fareBasis()).toList();
    }

    @Test
    void twoFilingsOfTheSameFareShowNothingMoving() {
        Filing report = build(List.of(fare()), List.of(fare()));
        assertThat(report.moves()).isEmpty();
        assertThat(report.withdrawn()).isEmpty();
        assertThat(report.added()).isZero();
        assertThat(report.unchanged()).isEqualTo(1);
    }

    @Test
    void aPriceThatMovedIsAMoveAndCarriesBothNumbers() {
        Filing report = build(List.of(costing("420.00")), List.of(costing("380.00")));
        assertThat(report.moves()).hasSize(1);
        Move move = report.moves().get(0);
        assertThat(move.was()).isEqualTo(Money.of("420.00", "EUR"));
        assertThat(move.now()).isEqualTo(Money.of("380.00", "EUR"));
        assertThat(move.difference()).isEqualTo(Money.of("-40.00", "EUR"));
    }

    @Test
    void aPriceThatFellSaysSoAndSaysHowFar() {
        Move move = build(List.of(costing("400.00")), List.of(costing("300.00")))
                .moves()
                .get(0);
        assertThat(move.fell()).isTrue();
        assertThat(move.percent()).isEqualByComparingTo("-25.00");
    }

    @Test
    void aPriceThatRoseIsNotAFall() {
        Move move = build(List.of(costing("400.00")), List.of(costing("500.00")))
                .moves()
                .get(0);
        assertThat(move.fell()).isFalse();
        assertThat(move.percent()).isEqualByComparingTo("25.00");
    }

    @Test
    void aFareThatWasFreeDoesNotDivideByZero() {
        Move move = build(List.of(costing("0.00")), List.of(costing("10.00")))
                .moves()
                .get(0);
        assertThat(move.percent()).isEqualByComparingTo("0");
    }

    @Test
    void aFareFiledInAnotherCurrencyCountsAsAMove() {
        // Not an amendment. The number is the same and what the passenger pays
        // is not, which is the whole difference between the two lists.
        Filing report = build(List.of(costing("420.00")), List.of(costing("420.00", row -> row.setCurrency("USD"))));
        assertThat(report.moves()).hasSize(1);
    }

    @Test
    void aFareTheCarrierStoppedFilingIsWithdrawn() {
        Filing report = build(List.of(fare(), fare("MLWAP7RT")), List.of(fare()));
        assertThat(report.withdrawn().stream()
                        .map(gone -> gone.fare().fareBasis())
                        .toList())
                .containsExactly("MLWAP7RT");
        assertThat(report.withdrawn().get(0).was()).isEqualTo(Money.of("420.00", "EUR"));
    }

    @Test
    void aFareThatWasNotThereBeforeIsAdded() {
        Filing report = build(List.of(fare()), List.of(fare(), fare("MLWAP7RT")));
        assertThat(report.added()).isEqualTo(1);
        assertThat(report.withdrawn()).isEmpty();
    }

    @Test
    void aNewFareIsCountedAndNotListed() {
        // Carriers file promotional fares constantly and most of them are for
        // markets nobody watches. Listing them buries the two lists that matter.
        Filing report = build(List.of(), numbered(40, "420.00"));
        assertThat(report.added()).isEqualTo(40);
        assertThat(render(report)).contains("added");
        assertThat(render(report)).doesNotContain("K000");
    }

    @Test
    void aFareIsTheSameFareAcrossAChangeOfRule() {
        Filing report = build(List.of(fare()), List.of(fare(row -> row.setRuleNumber("0200"))));
        assertThat(report.withdrawn()).isEmpty();
        assertThat(report.added()).isZero();
        assertThat(report.amendments()).hasSize(1);
    }

    @Test
    void aRuleChangeOnAnUnchangedPriceIsReportedAndNotSwallowed() {
        // The amendment that gets missed, because nothing about the price moved.
        Amendment amendment = build(List.of(fare()), List.of(fare(row -> row.setRuleNumber("0200"))))
                .amendments()
                .get(0);
        assertThat(amendment.field()).isEqualTo("rule number");
        assertThat(amendment.was()).isEqualTo("0100");
        assertThat(amendment.now()).isEqualTo("0200");
    }

    @Test
    void aFareThatMovedOnPriceIsNotAlsoReportedAsAmended() {
        // It is one event. Reporting it twice makes a refile look like two.
        Filing report = build(List.of(fare()), List.of(costing("380.00", row -> row.setRuleNumber("0200"))));
        assertThat(report.moves()).hasSize(1);
        assertThat(report.amendments()).isEmpty();
    }

    @Test
    void severalTermsMovingAtOnceAreSeveralAmendments() {
        Filing report = build(List.of(fare()), List.of(fare(row -> {
            row.setRuleNumber("0200");
            row.setRoutingNumber("0022");
        })));
        assertThat(report.amendments().stream().map(Amendment::field).toList())
                .containsExactly("rule number", "routing number");
    }

    @Test
    void aRefileThatOnlyExtendsTheSaleWindowIsNothingHappening() {
        // The most common single edit in a tariff. Treating it as a withdrawal
        // plus an addition would bury everything else.
        Filing report = build(List.of(fare()), List.of(fare(row -> row.setDiscontinue(LocalDate.of(2026, 12, 31)))));
        assertThat(report.moves()).isEmpty();
        assertThat(report.amendments()).isEmpty();
        assertThat(report.withdrawn()).isEmpty();
        assertThat(report.added()).isZero();
    }

    @Test
    void theSameBasisInAnotherMarketIsAnotherFare() {
        Filing report = build(List.of(fare()), List.of(fare(row -> row.setDestination("LON"))));
        assertThat(report.added()).isEqualTo(1);
        assertThat(report.withdrawn()).hasSize(1);
    }

    @Test
    void theSameBasisInAnotherCabinIsAnotherFare() {
        Filing report = build(List.of(fare()), List.of(fare(row -> row.setCabin("C"))));
        assertThat(report.added()).isEqualTo(1);
    }

    @Test
    void aOneWayAndARoundTripFilingAreTwoFares() {
        Filing report = build(
                List.of(fare(row -> row.setOwrt("1")), fare(row -> row.setOwrt("2"))),
                List.of(fare(row -> row.setOwrt("1")), fare(row -> row.setOwrt("2"))));
        assertThat(report.unchanged()).isEqualTo(2);
    }

    @Test
    void theFallsComeBackSteepestFirst() {
        List<FareRow> before = List.of(fare("A", "100.00"), fare("B", "100.00"));
        List<FareRow> after = List.of(fare("A", "90.00"), fare("B", "50.00"));
        assertThat(bases(build(before, after).falls())).containsExactly("B", "A");
    }

    @Test
    void theRisesComeBackSteepestFirstToo() {
        List<FareRow> before = List.of(fare("A", "100.00"), fare("B", "100.00"));
        List<FareRow> after = List.of(fare("A", "110.00"), fare("B", "150.00"));
        assertThat(bases(build(before, after).rises())).containsExactly("B", "A");
    }

    @Test
    void aFilingThatOnlyGrewNeedsNobodyBeforeTheDeskOpens() {
        assertThat(build(List.of(fare()), List.of(fare(), fare("MLWAP7RT"))).worthALook())
                .isFalse();
    }

    @Test
    void aFilingWithAFallInItNeedsSomebody() {
        assertThat(build(List.of(fare()), List.of(costing("380.00"))).worthALook())
                .isTrue();
    }

    @Test
    void aFilingWithAWithdrawalInItNeedsSomebody() {
        // The dangerous one. The old fare still prices and the carrier will
        // refuse it at the ticket desk.
        assertThat(build(List.of(fare(), fare("MLWAP7RT")), List.of(fare())).worthALook())
                .isTrue();
    }

    @Test
    void aFilingThatOnlyRoseDoesNotPageAnybody() {
        assertThat(build(List.of(fare()), List.of(costing("500.00"))).worthALook())
                .isFalse();
    }

    @Test
    void theReportReadsBackInOneLine() {
        Filing report = build(List.of(fare(), fare("B")), List.of(costing("380.00"), fare("C")));
        assertThat(report.toString()).isEqualTo("1 moved, 1 withdrawn, 1 added over 2 fares");
    }

    @Test
    void theReportKnowsWhoseFilingItIs() {
        assertThat(build(List.of(fare()), List.of(fare())).carrier()).isEqualTo("LH");
    }

    @Test
    void aComparisonAcrossCarriersSaysSoRatherThanPickingOne() {
        assertThat(build(List.of(fare(row -> row.setCarrier("LH"))), List.of(fare(row -> row.setCarrier("UA"))))
                        .carrier())
                .isEqualTo("LH+UA");
    }

    @Test
    void anEmptyComparisonNamesNobody() {
        assertThat(build(List.of(), List.of()).carrier()).isEmpty();
    }

    @Test
    void theRenderedReportCountsEveryKindOfChange() {
        Filing report = build(List.of(fare(), fare("B")), List.of(costing("380.00"), fare("C")));
        String out = render(report);
        assertThat(out).contains("filing for LH");
        assertThat(out).contains("moved      1");
        assertThat(out).contains("withdrawn  1");
        assertThat(out).contains("added      1");
    }

    @Test
    void theRenderedReportLeadsWithTheFalls() {
        String out = render(build(List.of(fare()), List.of(costing("380.00"))));
        assertThat(out).contains("reprice these");
        assertThat(out.indexOf("reprice these")).isLessThan(out.indexOf("380.00 EUR"));
    }

    @Test
    void theRenderedReportSaysTheWithdrawnOnesStillPrice() {
        String out = render(build(List.of(fare(), fare("MLWAP7RT")), List.of(fare())));
        assertThat(out).contains("still price and should not");
        assertThat(out).contains("MLWAP7RT");
    }

    @Test
    void theRenderedReportLeavesOutTheListsItHasNothingFor() {
        String out = render(build(List.of(fare()), List.of(fare())));
        assertThat(out).doesNotContain("reprice these");
        assertThat(out).doesNotContain("rose");
    }

    @Test
    void theRenderedReportSaysHowMuchOfALongListItShowed() {
        List<FareRow> before = numbered(20, "100.00");
        List<FareRow> after = numbered(20, "90.00");
        assertThat(render(build(before, after), 3)).contains("showing 3 of 20");
    }

    @Test
    void theIdentityIsTheFareAndNeverThePrice() {
        assertThat(identity(costing("420.00"))).isEqualTo(identity(costing("1.00")));
    }
}
