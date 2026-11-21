package io.github.rkaushikdev.gussetline.baggage;

import static io.github.rkaushikdev.gussetline.baggage.Allowances.compare;
import static io.github.rkaushikdev.gussetline.baggage.Allowances.netBenefit;
import static io.github.rkaushikdev.gussetline.baggage.Allowances.smallest;
import static io.github.rkaushikdev.gussetline.baggage.Allowances.worthOf;
import static io.github.rkaushikdev.gussetline.offers.Baggage.ofKilos;
import static io.github.rkaushikdev.gussetline.offers.Baggage.ofPieces;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaggageTest {

    private static final Money PIECE = Money.of("60.00", "EUR");
    private static final Money KILO = Money.of("12.00", "EUR");

    private static Baggage unstated() {
        return new Baggage();
    }

    @Test
    void theSameAllowanceIsTheSame() {
        Change change = compare(ofPieces(1), ofPieces(1));
        assertThat(change.verdict()).isSameAs(Verdict.SAME);
        assertThat(change.worthSaying()).isFalse();
    }

    @Test
    void aLargerAllowanceIsBetter() {
        assertThat(compare(ofPieces(1), ofPieces(2)).verdict()).isSameAs(Verdict.BETTER);
    }

    @Test
    void aSmallerAllowanceIsWorseAndSaysByHowMuch() {
        Change change = compare(ofPieces(2), ofPieces(1));
        assertThat(change.verdict()).isSameAs(Verdict.WORSE);
        assertThat(change.lostPieces()).isEqualTo(1);
        assertThat(change.why()).isEqualTo("1 fewer checked pieces");
    }

    @Test
    void aSmallerWeightAllowanceSaysTheKilos() {
        Change change = compare(ofKilos(30), ofKilos(23));
        assertThat(change.verdict()).isSameAs(Verdict.WORSE);
        assertThat(change.lostKilos()).isEqualTo(7);
        assertThat(change.why()).isEqualTo("7 kilos less");
    }

    @Test
    void weightAgainstWeightComparesFine() {
        assertThat(compare(ofKilos(23), ofKilos(30)).verdict()).isSameAs(Verdict.BETTER);
        assertThat(compare(ofKilos(23), ofKilos(23)).verdict()).isSameAs(Verdict.SAME);
    }

    @Test
    void piecesAgainstWeightIsNotComparable() {
        // Whether thirty kilos beats two pieces depends on what the passenger
        // packed, and inventing a conversion invents an answer.
        Change change = compare(ofPieces(2), ofKilos(30));
        assertThat(change.verdict()).isSameAs(Verdict.NOT_COMPARABLE);
        assertThat(change.why()).contains("not the same kind");
        assertThat(change.worthSaying()).isTrue();
    }

    @Test
    void aFareWithNothingFiledIsUnknownAndNotNothing() {
        // A fare with no allowance filed may include a bag or may not, and
        // quoting a saving against it without saying so is the complaint.
        Change change = compare(ofPieces(2), unstated());
        assertThat(change.verdict()).isSameAs(Verdict.UNKNOWN);
        assertThat(change.worthSaying()).isTrue();
        assertThat(change.why()).contains("does not state an allowance");
    }

    @Test
    void neitherSideStatedIsUnknownToo() {
        assertThat(compare(unstated(), unstated()).verdict()).isSameAs(Verdict.UNKNOWN);
    }

    @Test
    void aChangeReadsBackInOneLine() {
        assertThat(compare(ofPieces(2), ofPieces(1))).hasToString("2PC to 1PC: 1 fewer checked pieces");
    }

    @Test
    void onlyTheBadVerdictsAreWorthSaying() {
        assertThat(Verdict.SAME.costsThePassenger()).isFalse();
        assertThat(Verdict.BETTER.costsThePassenger()).isFalse();
        assertThat(Verdict.WORSE.costsThePassenger()).isTrue();
        assertThat(Verdict.NOT_COMPARABLE.costsThePassenger()).isTrue();
        assertThat(Verdict.UNKNOWN.costsThePassenger()).isTrue();
    }

    @Test
    void aJourneyCarriesTheLeastOfItsSectors() {
        // The bag is checked through, so the passenger meets the strictest
        // sector's allowance at the first desk.
        assertThat(smallest(List.of(ofPieces(2), ofPieces(1)))).isEqualTo(ofPieces(1));
    }

    @Test
    void aJourneyOfOneSectorCarriesThatSector() {
        assertThat(smallest(List.of(ofKilos(23)))).isEqualTo(ofKilos(23));
    }

    @Test
    void aSectorWithNothingFiledDoesNotDragTheJourneyDown() {
        assertThat(smallest(List.of(ofPieces(2), unstated()))).isEqualTo(ofPieces(2));
    }

    @Test
    void aJourneyWithNothingFiledAnywhereStatesNothing() {
        assertThat(smallest(List.of(unstated(), unstated())).isStated()).isFalse();
        assertThat(smallest(List.of()).isStated()).isFalse();
    }

    @Test
    void twoKindsAcrossAJourneyStateNothingRatherThanGuessing() {
        // The honest answer to what a two piece and a thirty kilo journey carries
        // is that somebody has to look.
        assertThat(smallest(List.of(ofPieces(2), ofKilos(30))).isStated()).isFalse();
    }

    @Test
    void aLostPieceCostsTheRatePerPiece() {
        Change change = compare(ofPieces(2), ofPieces(1));
        assertThat(worthOf(change, PIECE)).isEqualTo(Money.of("60.00", "EUR"));
    }

    @Test
    void twoLostPiecesCostTwice() {
        Change change = compare(ofPieces(3), ofPieces(1));
        assertThat(worthOf(change, PIECE)).isEqualTo(Money.of("120.00", "EUR"));
    }

    @Test
    void lostKilosCostTheRatePerKilo() {
        Change change = compare(ofKilos(30), ofKilos(23));
        assertThat(worthOf(change, PIECE, KILO)).isEqualTo(Money.of("84.00", "EUR"));
    }

    @Test
    void lostKilosWithNoRateAreRefusedRatherThanPricedAtNothing() {
        Change change = compare(ofKilos(30), ofKilos(23));
        assertThatThrownBy(() -> worthOf(change, PIECE))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("rate per kilo");
    }

    @Test
    void aBetterAllowanceIsNotASavingToBeAdded() {
        // The passenger was not going to be paid for the bag they did not check.
        Change change = compare(ofPieces(1), ofPieces(2));
        assertThat(worthOf(change, PIECE)).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void theSameAllowanceCostsNothing() {
        assertThat(worthOf(compare(ofPieces(1), ofPieces(1)), PIECE)).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void anUnknownAllowanceCostsNothingButIsStillWorthSaying() {
        Change change = compare(ofPieces(2), unstated());
        assertThat(worthOf(change, PIECE)).isEqualTo(Money.zero("EUR"));
        assertThat(change.worthSaying()).isTrue();
    }

    @Test
    void theBenefitIsTheSavingWithTheLostBagTakenOff() {
        // A hundred euro drop onto a fare with one fewer bag is a saving of
        // about forty, and quoting the hundred is how it becomes a complaint.
        Change change = compare(ofPieces(2), ofPieces(1));
        assertThat(netBenefit(Money.of("100.00", "EUR"), change, PIECE)).isEqualTo(Money.of("40.00", "EUR"));
    }

    @Test
    void aSavingThatDoesNotCoverTheLostBagGoesNegative() {
        Change change = compare(ofPieces(3), ofPieces(1));
        assertThat(netBenefit(Money.of("100.00", "EUR"), change, PIECE).isNegative())
                .isTrue();
    }

    @Test
    void aSavingWithNoAllowanceLostIsTheWholeSaving() {
        Change change = compare(ofPieces(1), ofPieces(1));
        assertThat(netBenefit(Money.of("100.00", "EUR"), change, PIECE)).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void aBenefitWorkedOutAcrossCurrenciesIsRefused() {
        Change change = compare(ofPieces(2), ofPieces(1));
        assertThatThrownBy(() -> netBenefit(Money.of("100.00", "GBP"), change, PIECE))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("one currency");
    }
}
