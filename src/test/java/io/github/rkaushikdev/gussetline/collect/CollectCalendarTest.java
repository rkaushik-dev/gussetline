package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Calendar.parse;
import static io.github.rkaushikdev.gussetline.collect.Calendar.readPrice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CollectCalendarTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 5, 18, 9, 0);
    private static final String DAY = "2025-06-04";
    private static final String FARE_DAY = "fare-day";
    private static final String SOLD_OUT = "fare-day sold-out";

    private static String page(String cells) {
        return page(cells, "EUR", "FRA-JFK", "LH");
    }

    private static String page(String cells, String currency, String market, String carrier) {
        List<String> heads = new ArrayList<>();
        if (!currency.isEmpty()) {
            heads.add("<meta name=\"fare-currency\" content=\"" + currency + "\">");
        }
        if (!market.isEmpty()) {
            heads.add("<meta name=\"fare-market\" content=\"" + market + "\">");
        }
        if (!carrier.isEmpty()) {
            heads.add("<meta name=\"fare-carrier\" content=\"" + carrier + "\">");
        }
        return "<html><head>" + String.join("", heads) + "</head><body><table><tr>" + cells
                + "</tr></table></body></html>";
    }

    private static String cell() {
        return cell(DAY, "420.00", "", FARE_DAY, "");
    }

    private static String cellOn(String day) {
        return cell(day, "420.00", "", FARE_DAY, "");
    }

    private static String cellOn(String day, String amount) {
        return cell(day, amount, "", FARE_DAY, "");
    }

    private static String cellShowing(String amount, String text) {
        return cell(DAY, amount, text, FARE_DAY, "");
    }

    private static String soldOutOn(String day) {
        return cell(day, "", "", SOLD_OUT, "");
    }

    /** A day of the grid: the class in double quotes and the date in single ones, as the page mixes them. */
    private static String cell(String day, String amount, String text, String cls, String seats) {
        List<String> bits = new ArrayList<>(List.of("class=\"" + cls + "\"", "data-date='" + day + "'"));
        if (!amount.isEmpty()) {
            bits.add("data-amount=\"" + amount + "\"");
        }
        if (!seats.isEmpty()) {
            bits.add("data-seats=\"" + seats + "\"");
        }
        return "<td " + String.join(" ", bits) + ">" + (text.isEmpty() ? amount : text) + "</td>";
    }

    @Test
    void aPlainPriceReads() {
        assertThat(readPrice("420.00", "EUR")).contains(Money.of("420.00", "EUR"));
        assertThat(readPrice("420", "EUR")).contains(Money.of("420", "EUR"));
    }

    @Test
    void aCurrencySymbolOnEitherSideIsIgnored() {
        assertThat(readPrice("EUR 420.00", "EUR")).contains(Money.of("420.00", "EUR"));
        assertThat(readPrice("420.00 kr", "SEK")).contains(Money.of("420.00", "SEK"));
    }

    @Test
    void aThousandSeparatorIsReadTheWayTheLocaleMeantIt() {
        assertThat(readPrice("1,420.00", "EUR")).contains(Money.of("1420.00", "EUR"));
        assertThat(readPrice("1.420,00", "EUR")).contains(Money.of("1420.00", "EUR"));
        assertThat(readPrice("1 420", "EUR")).contains(Money.of("1420", "EUR"));
    }

    @Test
    void aCommaThatIsADecimalPointIsReadAsOne() {
        assertThat(readPrice("420,50", "EUR")).contains(Money.of("420.50", "EUR"));
    }

    @Test
    void aCommaThatIsAThousandSeparatorIsDropped() {
        assertThat(readPrice("1,420", "EUR")).contains(Money.of("1420", "EUR"));
    }

    @Test
    void aCellWithNoDigitsHasNoPrice() {
        assertThat(readPrice("sold out", "EUR")).isEmpty();
        assertThat(readPrice("", "EUR")).isEmpty();
        assertThat(readPrice("   ", "EUR")).isEmpty();
    }

    @Test
    void aPageThatDoesNotSayItsMarketIsRefused() {
        MalformedPayload caught =
                catchThrowableOfType(MalformedPayload.class, () -> parse(page(cell(), "EUR", "", "LH"), SEEN));
        assertThat(caught.message()).contains("which market");
    }

    @Test
    void aPageThatDoesNotSayItsCurrencyIsRefused() {
        assertThatThrownBy(() -> parse(page(cell(), "", "FRA-JFK", "LH"), SEEN)).isInstanceOf(MalformedPayload.class);
    }

    @Test
    void aSingleCellGivesAQuote() {
        Harvest reading = parse(page(cell()), SEEN);
        assertThat(reading.clean()).isTrue();
        assertThat(reading.size()).isEqualTo(1);
        Quote quote = reading.quotes().get(0);
        assertThat(quote.market()).containsExactly("FRA", "NYC");
        assertThat(quote.departure()).isEqualTo(LocalDate.of(2025, 6, 4));
        assertThat(quote.amount()).isEqualTo(Money.of("420.00", "EUR"));
        assertThat(quote.carrier()).isEqualTo("LH");
        assertThat(quote.observedAt()).isEqualTo(SEEN);
    }

    @Test
    void theMarketIsFoldedToCities() {
        Harvest reading = parse(page(cell(), "EUR", "LHR-EWR", "LH"), SEEN);
        assertThat(reading.quotes().get(0).market()).containsExactly("LON", "NYC");
    }

    @Test
    void aPriceInTheCellTextIsUsedWhenThereIsNoDataAttribute() {
        Harvest reading = parse(page(cellShowing("", "EUR 512.00")), SEEN);
        assertThat(reading.quotes().get(0).amount()).isEqualTo(Money.of("512.00", "EUR"));
    }

    @Test
    void theDataAttributeWinsOverTheText() {
        Harvest reading = parse(page(cellShowing("420.00", "from EUR 999")), SEEN);
        assertThat(reading.quotes().get(0).amount()).isEqualTo(Money.of("420.00", "EUR"));
    }

    @Test
    void aSoldOutCellIsAQuoteWithNoPrice() {
        Harvest reading = parse(page(soldOutOn(DAY)), SEEN);
        assertThat(reading.size()).isEqualTo(1);
        assertThat(reading.quotes().get(0).soldOut()).isTrue();
        assertThat(reading.quotes().get(0).toString()).endsWith("sold out");
    }

    @Test
    void anEmptyCellCountsAsSoldOut() {
        Harvest reading = parse(page(cellShowing("", "")), SEEN);
        assertThat(reading.quotes().get(0).soldOut()).isTrue();
    }

    @Test
    void aCellWithADateWeCannotReadIsAProblem() {
        Harvest reading = parse(page(cellOn("the fourth")), SEEN);
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0)).contains("unreadable date");
    }

    @Test
    void aCellWithTextButNoReadablePriceIsAProblem() {
        Harvest reading = parse(page(cellShowing("", "call us")), SEEN);
        assertThat(reading.size()).isZero();
        assertThat(reading.problems().get(0)).contains("no price we could read");
    }

    @Test
    void cellsThatAreNotFareDaysAreIgnored() {
        String other = "<td class=\"month-name\">June</td>";
        Harvest reading = parse(page(other + cell()), SEEN);
        assertThat(reading.size()).isEqualTo(1);
    }

    @Test
    void attributesInEitherQuotingStyleAndAnyOrderRead() {
        String odd = "<td data-amount='380.00' class=\"fare-day\" data-date=\"2025-06-05\">380</td>";
        Harvest reading = parse(page(odd), SEEN);
        assertThat(reading.quotes().get(0).amount()).isEqualTo(Money.of("380.00", "EUR"));
        assertThat(reading.quotes().get(0).departure()).isEqualTo(LocalDate.of(2025, 6, 5));
    }

    @Test
    void aSeatCountIsReadWhenThePageCarriesOne() {
        Harvest reading = parse(page(cell(DAY, "420.00", "", FARE_DAY, "4")), SEEN);
        assertThat(reading.quotes().get(0).seats()).contains(4);
        assertThat(parse(page(cell()), SEEN).quotes().get(0).seats()).isEmpty();
    }

    @Test
    void aMonthOfCellsReadsAsAMonthOfQuotes() {
        String cells = IntStream.rangeClosed(1, 30)
                .mapToObj(day -> cellOn(String.format(Locale.ROOT, "2025-06-%02d", day), String.valueOf(400 + day)))
                .collect(Collectors.joining());
        Harvest reading = parse(page(cells), SEEN);
        assertThat(reading.size()).isEqualTo(30);
        assertThat(reading.clean()).isTrue();
    }

    @Test
    void theCheapestLiveQuoteIsFound() {
        String cells = cellOn("2025-06-04", "420") + cellOn("2025-06-05", "380");
        Harvest reading = parse(page(cells), SEEN);
        Optional<Quote> cheapest = reading.cheapestQuote();
        assertThat(cheapest).isPresent();
        assertThat(cheapest.get().departure()).isEqualTo(LocalDate.of(2025, 6, 5));
    }

    @Test
    void aSoldOutDayIsNeverTheCheapest() {
        String cells = cellOn("2025-06-04", "420") + soldOutOn("2025-06-05");
        Optional<Quote> cheapest = parse(page(cells), SEEN).cheapestQuote();
        assertThat(cheapest).isPresent();
        assertThat(cheapest.get().departure()).isEqualTo(LocalDate.of(2025, 6, 4));
    }

    @Test
    void aPageWhereEverythingIsSoldOutHasNoCheapest() {
        String cells = soldOutOn("2025-06-04");
        assertThat(parse(page(cells), SEEN).cheapestQuote()).isEmpty();
    }

    @Test
    void aParticularDayCanBeLookedUp() {
        String cells = cellOn("2025-06-04") + cellOn("2025-06-05", "380");
        Harvest reading = parse(page(cells), SEEN);
        Optional<Quote> found = reading.quoteOn(LocalDate.of(2025, 6, 5));
        assertThat(found).isPresent();
        assertThat(found.get().amount()).isEqualTo(Money.of("380", "EUR"));
        assertThat(reading.quoteOn(LocalDate.of(2025, 7, 1))).isEmpty();
    }

    @Test
    void twoQuotesCompareOnTheirAmounts() {
        Quote left = new Quote("FRA", "NYC", LocalDate.of(2025, 6, 4), Money.of("380", "EUR"), SEEN);
        Quote right = new Quote("FRA", "NYC", LocalDate.of(2025, 6, 5), Money.of("420", "EUR"), SEEN);
        assertThat(left.cheaperThan(right)).isTrue();
        assertThat(right.cheaperThan(left)).isFalse();
    }

    @Test
    void aQuoteReadsBackInOneLine() {
        Quote quote = new Quote("FRA", "NYC", LocalDate.of(2025, 6, 4), Money.of("420.00", "EUR"), SEEN);
        assertThat(quote).hasToString("FRANYC 2025-06-04 420.00 EUR");
    }

    @Test
    void aReadingReadsBackWithBothCounts() {
        Harvest reading = parse(page(cell() + cellOn("bad")), SEEN);
        assertThat(reading).hasToString("1 quotes, 1 problems");
    }
}
