package io.github.rkaushikdev.gussetline.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NumbersTest {

    @Test
    void divisionRunsAtTwentyEightDigitsWithTiesToEven() {
        assertThat(Numbers.divide(BigDecimal.ONE, new BigDecimal("3")).toString())
                .isEqualTo("0.3333333333333333333333333333");
        assertThat(Numbers.divide(new BigDecimal("2"), new BigDecimal("3")).toString())
                .isEqualTo("0.6666666666666666666666666667");
        assertThat(Numbers.divide(new BigDecimal("840.00"), new BigDecimal("2")).toString())
                .isEqualTo("420.00");
    }

    @Test
    void wholeNumbersAcceptSignsSpacesAndSingleUnderscores() {
        assertThat(Numbers.parseInt(" 21 ")).isEqualTo(21);
        assertThat(Numbers.parseInt("+7")).isEqualTo(7);
        assertThat(Numbers.parseInt("-007")).isEqualTo(-7);
        assertThat(Numbers.parseLong("1_000")).isEqualTo(1000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "lots", "1.5", "1__0", "_1", "1_", "0x10", "1e3"})
    void wholeNumbersRefuseEverythingElse(String text) {
        assertThatThrownBy(() -> Numbers.parseInt(text)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void aWholeNumberTooBigForAnIntIsRefused() {
        assertThatThrownBy(() -> Numbers.parseInt("99999999999")).isInstanceOf(NumberFormatException.class);
    }

    @ParameterizedTest
    @CsvSource({"12.50,12.50", "' 5.25 ',5.25", ".5,0.5", "3.,3", "1E+3,1E+3", "-0.01,-0.01", "1_000.5,1000.5"})
    void decimalsReadTheShapesWrittenDown(String text, String expected) {
        assertThat(Numbers.parseDecimal(text)).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "NaN", "Infinity", "1.2.3", "1e", ".", "12EUR"})
    void decimalsRefuseWhatIsNotAFiniteAmount(String text) {
        assertThatThrownBy(() -> Numbers.parseDecimal(text)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void floatsReadDecimalsAndTheNonFiniteWords() {
        assertThat(Numbers.parseFloat(" 1.5 ")).isEqualTo(1.5);
        assertThat(Numbers.parseFloat("10")).isEqualTo(10.0);
        assertThat(Numbers.parseFloat("inf")).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(Numbers.parseFloat("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(Numbers.parseFloat("nan")).isNaN();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fast", "1.0f", "0x1p3", ""})
    void floatsRefuseTheRest(String text) {
        assertThatThrownBy(() -> Numbers.parseFloat(text)).isInstanceOf(NumberFormatException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "1.0,1.0",
        "60.0,60.0",
        "0.1,0.1",
        "0.0001,0.0001",
        "0.00001,1e-05",
        "1e15,1000000000000000.0",
        "1e16,1e+16",
        "1.5e16,1.5e+16",
        "-2.5,-2.5",
        "123456.789,123456.789"
    })
    void floatTextIsTheShortestFormThatReadsBack(double value, String expected) {
        assertThat(Numbers.floatText(value)).isEqualTo(expected);
    }

    @Test
    void floatTextNamesTheValuesThatAreNotNumbers() {
        assertThat(Numbers.floatText(Double.NaN)).isEqualTo("nan");
        assertThat(Numbers.floatText(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(Numbers.floatText(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
        assertThat(Numbers.floatText(0.0)).isEqualTo("0.0");
        assertThat(Numbers.floatText(-0.0)).isEqualTo("-0.0");
    }

    @Test
    void fixedPlacesRoundTheExactBinaryValueWithTiesToEven() {
        assertThat(Numbers.fixed(0.125, 2)).isEqualTo("0.12");
        assertThat(Numbers.fixed(0.375, 2)).isEqualTo("0.38");
        assertThat(Numbers.fixed(2.675, 2)).isEqualTo("2.67");
        assertThat(Numbers.fixed(5.0, 1)).isEqualTo("5.0");
        assertThat(Numbers.fixed(-0.04, 1)).isEqualTo("-0.0");
    }

    @Test
    void fixedPlacesOnADecimalRoundTiesToEvenAndNeverGoScientific() {
        assertThat(Numbers.fixed(new BigDecimal("1.0850000"), 6)).isEqualTo("1.085000");
        assertThat(Numbers.fixed(new BigDecimal("0.125"), 2)).isEqualTo("0.12");
        assertThat(Numbers.fixed(new BigDecimal("0.135"), 2)).isEqualTo("0.14");
        assertThat(Numbers.fixed(new BigDecimal("1E+3"), 2)).isEqualTo("1000.00");
        assertThat(Numbers.fixed(new BigDecimal("-0.001"), 2)).isEqualTo("-0.00");
    }

    @Test
    void aWholeNumberIsWholeWhateverItsScale() {
        assertThat(Numbers.isWhole(new BigDecimal("840.00"))).isTrue();
        assertThat(Numbers.isWhole(new BigDecimal("1E+2"))).isTrue();
        assertThat(Numbers.isWhole(new BigDecimal("0.000"))).isTrue();
        assertThat(Numbers.isWhole(new BigDecimal("840.50"))).isFalse();
    }
}
