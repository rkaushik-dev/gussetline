package io.github.rkaushikdev.gussetline.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The rules about money that have to hold for every amount, not three of them.
 *
 * <p>Everything else in the suite tests a rounding against a number somebody chose, which catches
 * a rule written backwards and misses a rule that is right in the middle of its range and wrong at
 * the edges. These walk a generated table instead: every currency the desk sells, every amount
 * across the interesting band, and the awkward ones written out by hand.
 *
 * <p>The direction of each rounding is the whole point and each one is wrong in a different way. A
 * fare rounded down is money the desk gave away on every ticket. A tax rounded either way by a rule
 * that is not half up disagrees with the government's own arithmetic. A refund rounded up promises a
 * fraction of a cent nobody can pay.
 */
class MoneyInvariantsTest {

    /** Every currency the desk sells in, so a rounding that assumes two decimals fails here. */
    static final List<String> CODES =
            Currency.SELLING.keySet().stream().sorted().toList();

    /** The band that matters, plus the ends. */
    static final List<String> AMOUNTS = List.of(
            "0",
            "0.001",
            "0.004",
            "0.005",
            "0.006",
            "0.01",
            "0.49",
            "0.50",
            "0.51",
            "0.99",
            "1",
            "1.005",
            "1.015",
            "1.025",
            "7.777",
            "42.425",
            "42.435",
            "99.995",
            "420.00",
            "839.995",
            "1234.567",
            "9999.999");

    static final List<UnaryOperator<Money>> ROUNDINGS = List.of(Money::roundFare, Money::roundTax, Money::roundRefund);

    static Stream<Arguments> pairs() {
        return CODES.stream()
                .flatMap(code -> AMOUNTS.stream().map(amount -> Arguments.of(code, new BigDecimal(amount))));
    }

    static Stream<BigDecimal> amounts() {
        return AMOUNTS.stream().map(BigDecimal::new);
    }

    static Money money(String code, BigDecimal amount) {
        return new Money(amount, code);
    }

    static boolean onUnit(BigDecimal amount, BigDecimal unit) {
        return amount.remainder(unit).signum() == 0;
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedFareIsNeverBelowWhatItStartedAt(String code, BigDecimal amount) {
        assertThat(Money.roundFare(money(code, amount)).amount()).isGreaterThanOrEqualTo(amount);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedFareLandsOnTheCurrencyOwnFareUnit(String code, BigDecimal amount) {
        BigDecimal unit = Currency.of(code).fareUnit();
        assertThat(onUnit(Money.roundFare(money(code, amount)).amount(), unit)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedFareNeverAddsMoreThanOneUnit(String code, BigDecimal amount) {
        BigDecimal unit = Currency.of(code).fareUnit();
        assertThat(Money.roundFare(money(code, amount)).amount().subtract(amount))
                .isLessThan(unit);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void roundingAFareTwiceChangesNothing(String code, BigDecimal amount) {
        Money once = Money.roundFare(money(code, amount));
        assertThat(Money.roundFare(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedTaxMatchesTheGovernmentsOwnArithmetic(String code, BigDecimal amount) {
        int places = Currency.of(code).exponent();
        assertThat(Money.roundTax(money(code, amount)).amount())
                .isEqualByComparingTo(amount.setScale(places, RoundingMode.HALF_UP));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedTaxNeverMovesByMoreThanHalfAUnit(String code, BigDecimal amount) {
        BigDecimal quantum = Currency.of(code).quantum();
        BigDecimal moved =
                Money.roundTax(money(code, amount)).amount().subtract(amount).abs();
        assertThat(moved).isLessThanOrEqualTo(Numbers.divide(quantum, BigDecimal.TWO));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void roundingATaxTwiceChangesNothing(String code, BigDecimal amount) {
        Money once = Money.roundTax(money(code, amount));
        assertThat(Money.roundTax(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedRefundIsNeverAboveWhatItStartedAt(String code, BigDecimal amount) {
        assertThat(Money.roundRefund(money(code, amount)).amount()).isLessThanOrEqualTo(amount);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundedRefundLandsOnAMinorUnit(String code, BigDecimal amount) {
        BigDecimal quantum = Currency.of(code).quantum();
        assertThat(onUnit(Money.roundRefund(money(code, amount)).amount(), quantum))
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void roundingARefundTwiceChangesNothing(String code, BigDecimal amount) {
        Money once = Money.roundRefund(money(code, amount));
        assertThat(Money.roundRefund(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRefundIsNeverDearerThanTheTaxOnTheSameAmount(String code, BigDecimal amount) {
        assertThat(Money.roundRefund(money(code, amount)).isAtMost(Money.roundTax(money(code, amount))))
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aTaxIsNeverDearerThanTheFareOnTheSameAmount(String code, BigDecimal amount) {
        assertThat(Money.roundTax(money(code, amount)).isAtMost(Money.roundFare(money(code, amount))))
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void neutralUnitsDropTheThirdDecimalRatherThanRoundingIt(BigDecimal amount) {
        assertThat(Money.truncateNuc(amount)).isEqualByComparingTo(amount.setScale(2, RoundingMode.DOWN));
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void truncatingNeutralUnitsTwiceChangesNothing(BigDecimal amount) {
        BigDecimal once = Money.truncateNuc(amount);
        assertThat(Money.truncateNuc(once)).isEqualByComparingTo(once);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void aTruncatedAmountIsNeverAboveWhatItStartedAt(BigDecimal amount) {
        assertThat(Money.truncateNuc(amount)).isLessThanOrEqualTo(amount);
    }

    @Test
    void theThreeRoundingsDisagreeWhereTheyAreMeantTo() {
        Money awkward = new Money(new BigDecimal("42.425"), "EUR");
        assertThat(Set.of(Money.roundFare(awkward), Money.roundTax(awkward), Money.roundRefund(awkward)))
                .hasSize(3);
    }

    @Test
    void everyCurrencyHasAFareUnitAtLeastAsBigAsItsMinorUnit() {
        for (String code : CODES) {
            Currency held = Currency.of(code);
            assertThat(held.fareUnit()).isGreaterThanOrEqualTo(held.quantum());
        }
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void addingTwoRoundedTaxesStaysOnAMinorUnit(String code, BigDecimal amount) {
        BigDecimal quantum = Currency.of(code).quantum();
        Money total = Money.roundTax(money(code, amount)).plus(Money.roundTax(money(code, amount)));
        assertThat(onUnit(total.amount(), quantum)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void aRoundingNeverChangesTheCurrency(String code, BigDecimal amount) {
        for (UnaryOperator<Money> rounded : ROUNDINGS) {
            assertThat(rounded.apply(money(code, amount)).currency()).isEqualTo(code);
        }
    }

    @ParameterizedTest
    @MethodSource("codes")
    void roundingNothingGivesNothing(String code) {
        for (UnaryOperator<Money> rounded : ROUNDINGS) {
            assertThat(rounded.apply(Money.zero(code)).isZero()).isTrue();
        }
    }

    static Stream<String> codes() {
        return CODES.stream();
    }

    /**
     * What two of the roundings do to a sign that should never reach them.
     *
     * <p>A fare and a refund are both positive by construction, and a negative one only turns up
     * where an arithmetic above has already gone wrong. A magnitude that never grows means a broken
     * arithmetic upstream produces a smaller wrong number rather than a larger one, and a rounding
     * that never crosses zero cannot turn a refund into a charge. Not the tax: half up means half
     * away from zero, so a tax of minus half a cent rounds to minus a whole one, which is right for
     * a tax and covered by the half unit test above.
     */
    @ParameterizedTest
    @MethodSource("pairs")
    void aNegativeAmountMovesTowardsZeroAndNeverPastIt(String code, BigDecimal amount) {
        if (amount.signum() == 0) {
            return;
        }
        Money negative = money(code, amount.negate());
        for (UnaryOperator<Money> rounded : List.<UnaryOperator<Money>>of(Money::roundFare, Money::roundRefund)) {
            BigDecimal result = rounded.apply(negative).amount();
            assertThat(result.abs()).isLessThanOrEqualTo(negative.amount().abs());
            assertThat(result.signum()).isLessThanOrEqualTo(0);
        }
    }

    @Test
    void theGeneratedTableIsWideEnoughToBeWorthHaving() {
        assertThat(CODES).hasSizeGreaterThanOrEqualTo(4);
        assertThat(pairs().count()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void aCurrencyTheDeskDoesNotSellIsRefused() {
        assertThatThrownBy(() -> Currency.of("ZZZ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void theCeilingAndTheFloorAreNotTheSameRounding() {
        BigDecimal awkward = new BigDecimal("1.001");
        assertThat(awkward.setScale(2, RoundingMode.CEILING))
                .isNotEqualByComparingTo(awkward.setScale(2, RoundingMode.DOWN));
    }
}
