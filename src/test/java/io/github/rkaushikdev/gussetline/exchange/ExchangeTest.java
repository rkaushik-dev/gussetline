package io.github.rkaushikdev.gussetline.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExchangeTest {

    private static RateTable table() {
        RateTable t = new RateTable("test");
        t.add("EUR", LocalDate.of(2025, 1, 1), "0.951");
        t.add("EUR", LocalDate.of(2025, 4, 1), "0.924");
        t.add("JPY", LocalDate.of(2025, 1, 1), "152.44");
        t.add("USD", LocalDate.of(2025, 1, 1), "1.000");
        return t;
    }

    @Test
    void lookupTakesTheLatestRateOnOrBeforeTheDate() {
        RateTable table = table();
        assertThat(table.rateFor("EUR", LocalDate.of(2025, 3, 31))).isEqualByComparingTo("0.951");
        assertThat(table.rateFor("EUR", LocalDate.of(2025, 4, 1))).isEqualByComparingTo("0.924");
        assertThat(table.rateFor("EUR", LocalDate.of(2025, 9, 9))).isEqualByComparingTo("0.924");
    }

    @Test
    void lookupBeforeTheFirstRateRefusesRatherThanExtrapolating() {
        RateTable table = table();
        ValidationError caught =
                catchThrowableOfType(ValidationError.class, () -> table.rateFor("EUR", LocalDate.of(2024, 12, 31)));
        assertThat(caught.details().get("first")).isEqualTo("2025-01-01");
    }

    @Test
    void aCurrencyTheTableNeverHeldIsADifferentComplaint() {
        RateTable table = table();
        ValidationError caught =
                catchThrowableOfType(ValidationError.class, () -> table.rateFor("SEK", LocalDate.of(2025, 5, 1)));
        assertThat(caught.message()).contains("no rate held");
    }

    @Test
    void addingTheSameEffectiveDateTwiceReplacesIt() {
        RateTable table = table();
        table.add("EUR", LocalDate.of(2025, 1, 1), "0.900");
        assertThat(table.rateFor("EUR", LocalDate.of(2025, 2, 1))).isEqualByComparingTo("0.900");
        assertThat(table.codes()).containsExactly("EUR", "JPY", "USD");
    }

    @Test
    void ratesAddedOutOfOrderStillSort() {
        RateTable table = table();
        table.add("EUR", LocalDate.of(2024, 7, 1), "0.913");
        assertThat(table.rateFor("EUR", LocalDate.of(2024, 8, 1))).isEqualByComparingTo("0.913");
        assertThat(table.rateFor("EUR", LocalDate.of(2025, 2, 1))).isEqualByComparingTo("0.951");
    }

    @Test
    void aRateCannotBeZeroOrNegative() {
        RateTable table = table();
        assertThatThrownBy(() -> table.add("EUR", LocalDate.of(2025, 7, 1), "0"))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> table.add("EUR", LocalDate.of(2025, 7, 1), "-1"))
                .isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "2025-01-01, 2025-01-01",
        "2025-03-31, 2025-01-01",
        "2025-04-01, 2025-04-01",
        "2025-11-30, 2025-10-01",
    })
    void quarterStart(LocalDate day, LocalDate expected) {
        assertThat(Exchange.quarterStart(day)).isEqualTo(expected);
    }

    @Test
    void toNucTruncatesTheThirdDecimal() {
        Converter conv = new Converter(table());
        // 500.00 / 0.951 is 525.762355..., and the third decimal is dropped.
        assertThat(conv.toNuc(Money.of("500.00", "EUR"), LocalDate.of(2025, 2, 1)))
                .isEqualByComparingTo("525.76");
    }

    @Test
    void neutralUnitsPassThroughUntouched() {
        Converter conv = new Converter(table());
        assertThat(conv.toNuc(Money.of("100.005", "NUC"), LocalDate.of(2025, 2, 1)))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void fromNucRoundsUpToTheFareUnit() {
        Converter conv = new Converter(table());
        // 525.76 * 152.44 is 80146.86..., and yen fares round up to the hundred.
        assertThat(conv.fromNuc(new BigDecimal("525.76"), "JPY", LocalDate.of(2025, 2, 1)))
                .isEqualTo(Money.of("80200", "JPY"));
    }

    @Test
    void convertFareGoesThroughNeutralUnitsAndThatShows() {
        Converter conv = new Converter(table());
        Money out = conv.convertFare(Money.of("500.00", "EUR"), "JPY", LocalDate.of(2025, 2, 1));
        assertThat(out).isEqualTo(Money.of("80200", "JPY"));
    }

    @Test
    void aFareConvertedToItsOwnCurrencyCanMoveAndThatIsCorrect() {
        Converter conv = new Converter(table());
        // The round trip through neutral units truncates once and rounds up once,
        // so it is not the identity, and pretending it is hides real drift.
        Money out = conv.convertFare(Money.of("500.00", "EUR"), "EUR", LocalDate.of(2025, 2, 1));
        assertThat(out.currency()).isEqualTo("EUR");
        assertThat(out.amount()).isGreaterThanOrEqualTo(new BigDecimal("499"));
    }

    @Test
    void taxConversionUsesTheBankersTableNotTheRoe() {
        RateTable roe = new RateTable("roe");
        roe.add("EUR", LocalDate.of(2025, 1, 1), "0.951");
        roe.add("USD", LocalDate.of(2025, 1, 1), "1.000");
        RateTable bankers = new RateTable("bsr");
        bankers.add("EUR", LocalDate.of(2025, 1, 1), "0.980");
        bankers.add("USD", LocalDate.of(2025, 1, 1), "1.000");
        Converter conv = new Converter(roe, bankers);
        assertThat(conv.convertTax(Money.of("49.00", "EUR"), "USD", LocalDate.of(2025, 2, 1)))
                .isEqualTo(Money.of("50.00", "USD"));
    }

    @Test
    void taxConversionIntoTheSameCurrencyIsTheIdentity() {
        Converter conv = new Converter(table());
        Money same = Money.of("12.34", "EUR");
        assertThat(conv.convertTax(same, "EUR", LocalDate.of(2025, 2, 1))).isSameAs(same);
    }

    @Test
    void withoutABankersTableTheRoeStandsIn() {
        RateTable table = table();
        Converter conv = new Converter(table);
        assertThat(conv.bankers()).isSameAs(table);
    }

    @Test
    void theShippedTableCoversThreeQuartersAndTheSoldCurrencies() {
        RateTable roe = Exchange.defaultRoeTable();
        assertThat(roe.codes()).contains("EUR");
        assertThat(roe.rateFor("EUR", LocalDate.of(2024, 11, 5))).isEqualByComparingTo("0.913");
        assertThat(roe.rateFor("EUR", LocalDate.of(2025, 2, 5))).isEqualByComparingTo("0.951");
        assertThat(roe.rateFor("EUR", LocalDate.of(2025, 6, 5))).isEqualByComparingTo("0.924");
        assertThat(roe.rateFor("USD", LocalDate.of(2025, 6, 5))).isEqualByComparingTo("1.000");
    }
}
