package io.github.rkaushikdev.gussetline.farebasis;

import static io.github.rkaushikdev.gussetline.farebasis.FareBasis.parse;
import static io.github.rkaushikdev.gussetline.farebasis.FareBasis.sameFareFamily;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FarebasisTest {

    @Test
    void aBareCodeGivesThePrimeAndNothingElse() {
        FareBasis fb = parse("Y");
        assertThat(fb.prime()).isEqualTo("Y");
        assertThat(fb.designator()).isEmpty();
        assertThat(fb.season()).isEmpty();
        assertThat(fb.unparsed()).isEmpty();
    }

    @Test
    void thePrimeIsTheBookingClass() {
        assertThat(parse("KLWAP7RT").prime()).isEqualTo("K");
        assertThat(parse("klwap7rt").prime()).isEqualTo("K");
        assertThat(parse("KLWAP7RT").matchesClass("k")).isTrue();
    }

    @Test
    void aFullCodeComesApart() {
        FareBasis fb = parse("KLWAP7RT");
        assertThat(fb.season()).contains("low");
        assertThat(fb.weekend()).contains(true);
        assertThat(fb.advanceDays()).contains(7);
        assertThat(fb.roundTrip()).contains(true);
        assertThat(fb.unparsed()).isEmpty();
    }

    @Test
    void theWeekdayVariantReadsAsNotWeekend() {
        assertThat(parse("KLXAP7RT").weekend()).contains(false);
    }

    @Test
    void advancePurchaseTakesUpToThreeDigits() {
        assertThat(parse("QAP21").advanceDays()).contains(21);
        assertThat(parse("QAP180").advanceDays()).contains(180);
    }

    @Test
    void aMaximumStayInMonthsIsPickedUp() {
        FareBasis fb = parse("QHX3M");
        assertThat(fb.maxStayMonths()).contains(3);
        assertThat(fb.season()).contains("high");
        assertThat(fb.weekend()).contains(false);
    }

    @Test
    void aMinimumStayIsReadWithItsUnit() {
        assertThat(parse("QLMIN3D").minStay()).contains(new MinimumStay(3, "days"));
        assertThat(parse("QLMIN6M").minStay()).contains(new MinimumStay(6, "months"));
        assertThat(parse("QL").minStay()).isEmpty();
    }

    @Test
    void aMinimumStayDoesNotSwallowTheMaximum() {
        FareBasis fb = parse("QLMIN3D6M");
        assertThat(fb.minStay()).contains(new MinimumStay(3, "days"));
        assertThat(fb.maxStayMonths()).contains(6);
    }

    @Test
    void oneWayAndRoundTripAreExclusive() {
        assertThat(parse("YOW").roundTrip()).contains(false);
        assertThat(parse("YRT").roundTrip()).contains(true);
        assertThat(parse("Y").roundTrip()).isEmpty();
    }

    @Test
    void anUnrecognisedTailIsKeptAndNoted() {
        FareBasis fb = parse("YZZ9");
        assertThat(fb.unparsed()).isEqualTo("ZZ9");
        assertThat(fb.notes()).containsExactly("unrecognised ZZ9");
        assertThat(fb.prime()).isEqualTo("Y");
    }

    @Test
    void aCodeWeCannotDecomposeIsStillACode() {
        // This is the whole point. The desk keeps pricing when a carrier files
        // something nobody here has seen before.
        FareBasis fb = parse("VNBQWERTY");
        assertThat(fb.raw()).isEqualTo("VNBQWERTY");
        assertThat(fb.prime()).isEqualTo("V");
    }

    static Stream<String> notFareBases() {
        return Stream.of("", "   ", "1YRT", "Y-RT", "Y".repeat(40));
    }

    @ParameterizedTest
    @MethodSource("notFareBases")
    void aShapeThatIsNotAFareBasisIsRefused(String text) {
        assertThatThrownBy(() -> parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aDesignatorSplitsOffAtTheSlash() {
        FareBasis fb = parse("KLWAP7RT/CH25");
        assertThat(fb.body()).isEqualTo("KLWAP7RT");
        assertThat(fb.designator()).isPresent();
        TicketDesignator designator = fb.designator().orElseThrow();
        assertThat(designator.kind()).isEqualTo("CH");
        assertThat(designator.percent())
                .hasValueSatisfying(percent -> assertThat(percent).isEqualByComparingTo("25"));
        assertThat(fb.isDiscounted()).isTrue();
    }

    @Test
    void aDesignatorWithNoPercentageDiscountsNothing() {
        FareBasis fb = parse("YRT/IT");
        assertThat(fb.designator()).isPresent();
        TicketDesignator designator = fb.designator().orElseThrow();
        assertThat(designator.kind()).isEqualTo("IT");
        assertThat(fb.isDiscounted()).isFalse();
        assertThat(designator.applyTo(BigDecimal.valueOf(500))).isEqualByComparingTo("500");
    }

    @Test
    void aDiscountComesOffTheAmount() {
        TicketDesignator designator = TicketDesignator.parse("CH25");
        assertThat(designator.applyTo(BigDecimal.valueOf(400))).isEqualByComparingTo("300");
        assertThat(TicketDesignator.parse("ID90").applyTo(BigDecimal.valueOf(400)))
                .isEqualByComparingTo("40");
    }

    @Test
    void aHundredPercentDiscountIsAllowedAndMoreIsNot() {
        assertThat(TicketDesignator.parse("ID100").applyTo(BigDecimal.valueOf(400)))
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> TicketDesignator.parse("ID101")).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "C", "CHILD25", "2C", "CH-25"})
    void aShapeThatIsNotADesignatorIsRefused(String text) {
        assertThatThrownBy(() -> TicketDesignator.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theBodyIgnoresTheDesignatorForKeying() {
        assertThat(parse("KLWAP7RT/CH25").body()).isEqualTo("KLWAP7RT");
        assertThat(parse("KLWAP7RT").body()).isEqualTo("KLWAP7RT");
    }

    @Test
    void twoCodesDifferingOnlyAfterTheSlashAreOneFare() {
        FareBasis adult = parse("KLWAP7RT");
        FareBasis child = parse("KLWAP7RT/CH25");
        assertThat(sameFareFamily(adult, child)).isTrue();
    }

    @Test
    void twoDifferentBodiesAreTwoFares() {
        assertThat(sameFareFamily(parse("KLWAP7RT"), parse("KHWAP7RT"))).isFalse();
    }

    @Test
    void stringFormGivesTheCodeBack() {
        assertThat(parse("KLWAP7RT/CH25")).hasToString("KLWAP7RT/CH25");
        assertThat(TicketDesignator.parse("CH25")).hasToString("CH25");
    }
}
