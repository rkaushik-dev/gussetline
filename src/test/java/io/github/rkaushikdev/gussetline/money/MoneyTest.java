package io.github.rkaushikdev.gussetline.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void currencyLookupIsCaseAndSpaceInsensitive() {
        assertThat(Currency.of(" eur ").code()).isEqualTo("EUR");
    }

    @Test
    void nucIsReachableByNameButIsNotASellingCurrency() {
        assertThat(Currency.of("NUC")).isSameAs(Currency.NUC);
        assertThat(Currency.SELLING).doesNotContainKey("NUC");
    }

    @Test
    void unknownCurrencyIsRefused() {
        assertThatThrownBy(() -> Currency.of("XYZ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void minorUnitFollowsTheExponent() {
        assertThat(Currency.of("EUR").minor()).isEqualByComparingTo("0.01");
        assertThat(Currency.of("JPY").minor()).isEqualByComparingTo("1");
        assertThat(Currency.of("KWD").minor()).isEqualByComparingTo("0.001");
    }

    @Test
    void moneyNormalisesTheCodeAndTheAmount() {
        Money m = Money.of("12.50", "eur");
        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.amount()).isEqualByComparingTo("12.50");
    }

    @Test
    void arithmeticRefusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of(1, "EUR").plus(Money.of(1, "USD"))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Money.of(1, "EUR").isLessThan(Money.of(1, "USD")))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void additionAndSubtractionStayExact() {
        assertThat(Money.of("0.10", "EUR").plus(Money.of("0.20", "EUR")).amount())
                .isEqualByComparingTo("0.30");
        assertThat(Money.of("1.00", "EUR").minus(Money.of("0.30", "EUR")).amount())
                .isEqualByComparingTo("0.70");
    }

    @Test
    void multiplicationWorksFromEitherSide() {
        assertThat(Money.of("10", "EUR").times(3).amount()).isEqualByComparingTo("30");
        assertThat(Money.of("10", "EUR").times(new BigDecimal("2")).amount()).isEqualByComparingTo("20");
    }

    @Test
    void orderingAndPredicates() {
        assertThat(Money.of(1, "EUR").isLessThan(Money.of(2, "EUR"))).isTrue();
        assertThat(Money.of(2, "EUR").isAtLeast(Money.of(2, "EUR"))).isTrue();
        assertThat(Money.zero("EUR").isZero()).isTrue();
        assertThat(Money.of(5, "EUR").negate().isNegative()).isTrue();
        assertThat(Money.of(5, "EUR").negate().abs()).isEqualTo(Money.of(5, "EUR"));
    }

    @Test
    void fareRoundsUpToThePublishedUnitNotTheMinorUnit() {
        assertThat(Money.roundFare(Money.of("412.01", "SEK")).amount()).isEqualByComparingTo("415");
        assertThat(Money.roundFare(Money.of("18040", "JPY")).amount()).isEqualByComparingTo("18100");
        assertThat(Money.roundFare(Money.of("1234", "HUF")).amount()).isEqualByComparingTo("1300");
    }

    @Test
    void aFareAlreadyOnTheUnitDoesNotMove() {
        assertThat(Money.roundFare(Money.of("415", "SEK")).amount()).isEqualByComparingTo("415");
        assertThat(Money.roundFare(Money.of("18100", "JPY")).amount()).isEqualByComparingTo("18100");
    }

    @Test
    void taxRoundsToTheNearestMinorUnitWithHalvesUp() {
        assertThat(Money.roundTax(Money.of("12.344", "EUR")).amount()).isEqualByComparingTo("12.34");
        assertThat(Money.roundTax(Money.of("12.345", "EUR")).amount()).isEqualByComparingTo("12.35");
        assertThat(Money.roundTax(Money.of("310.5", "JPY")).amount()).isEqualByComparingTo("311");
    }

    @Test
    void refundRoundsDownSoTheDeskNeverOwesAFraction() {
        assertThat(Money.roundRefund(Money.of("12.349", "EUR")).amount()).isEqualByComparingTo("12.34");
        assertThat(Money.roundRefund(Money.of("12.999", "KWD")).amount()).isEqualByComparingTo("12.999");
    }

    @Test
    void theThreeRoundingsDisagreeAndThatIsThePoint() {
        Money value = Money.of("101.006", "SEK");
        assertThat(Money.roundFare(value).amount()).isEqualByComparingTo("105");
        assertThat(Money.roundTax(value).amount()).isEqualByComparingTo("101.01");
        assertThat(Money.roundRefund(value).amount()).isEqualByComparingTo("101.00");
    }

    @Test
    void neutralUnitsTruncateTheThirdDecimal() {
        assertThat(Money.truncateNuc(new BigDecimal("512.999"))).isEqualByComparingTo("512.99");
        assertThat(Money.truncateNuc(new BigDecimal("512.001"))).isEqualByComparingTo("512.00");
    }

    @Test
    void totalAddsARunAndRefusesAnEmptyOne() {
        assertThat(Money.total(Money.of(1, "EUR"), Money.of(2, "EUR"), Money.of(3, "EUR"))
                        .amount())
                .isEqualByComparingTo("6");
        assertThatThrownBy(Money::total).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123.45EUR", "123.45 EUR", " 123.45  eur "})
    void parseMoneyReadsTheShapesSuppliersSend(String text) {
        assertThat(Money.parse(text)).isEqualTo(Money.of("123.45", "EUR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "12", "abcEUR", ""})
    void parseMoneyRefusesTheRest(String text) {
        assertThatThrownBy(() -> Money.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void coerceAmountTakesIntsAndStringsAndDecimals() {
        assertThat(Money.coerceAmount(5)).isEqualByComparingTo("5");
        assertThat(Money.coerceAmount(" 5.25 ")).isEqualByComparingTo("5.25");
        assertThat(Money.coerceAmount(new BigDecimal("5.25"))).isEqualByComparingTo("5.25");
    }

    @Test
    void coerceAmountRefusesAFloatBecauseAFareIsNotBinary() {
        assertThatThrownBy(() -> Money.coerceAmount(5.25)).isInstanceOf(ValidationError.class);
    }

    @Test
    void coerceAmountRefusesABoolBeforeItBecomesAOne() {
        assertThatThrownBy(() -> Money.coerceAmount(true)).isInstanceOf(ValidationError.class);
    }
}
