package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.collect.Harvest.combine;
import static io.github.rkaushikdev.gussetline.collect.Harvest.nothing;
import static io.github.rkaushikdev.gussetline.collect.Harvest.offersOnly;
import static io.github.rkaushikdev.gussetline.collect.Harvest.quotesOnly;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CollectHarvestTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 25, 9, 0);

    private static Offer anOffer() {
        return anOffer("840.00");
    }

    private static Offer anOffer(String amount) {
        return new Offer(
                "gds",
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05"))),
                new PriceBreakdown(Money.of(amount, "EUR")),
                List.of("KLWAP7RT"),
                SEEN);
    }

    private static Quote aQuote() {
        return aQuote("420.00", 4, false);
    }

    private static Quote aQuote(String amount) {
        return aQuote(amount, 4, false);
    }

    private static Quote aQuote(String amount, int day, boolean soldOut) {
        return new Quote(
                "FRA", "NYC", LocalDate.of(2025, 7, day), Money.of(amount, "EUR"), SEEN, "", Optional.empty(), soldOut);
    }

    @Test
    void anEmptyHarvestIsCleanAndEmpty() {
        Harvest harvest = new Harvest();
        assertThat(harvest.clean()).isTrue();
        assertThat(harvest.isEmpty()).isTrue();
        assertThat(harvest.size()).isZero();
        assertThat(harvest).hasToString("nothing");
    }

    @Test
    void aHarvestCountsBothKindsTogether() {
        Harvest harvest = new Harvest(List.of(anOffer()), List.of(aQuote(), aQuote("420.00", 5, false)), List.of());
        assertThat(harvest.size()).isEqualTo(3);
        assertThat(harvest.isEmpty()).isFalse();
        assertThat(harvest).hasToString("1 offers, 2 quotes");
    }

    @Test
    void problemsShowInTheSummaryAndStopItBeingClean() {
        Harvest harvest = new Harvest(List.of(anOffer()), List.of(), List.of("offer 2 had no base amount"));
        assertThat(harvest.clean()).isFalse();
        assertThat(harvest).hasToString("1 offers, 1 problems");
    }

    @Test
    void aHarvestWithOnlyProblemsReadsAsNothing() {
        assertThat(nothing("could not parse")).hasToString("nothing, 1 problems");
    }

    @Test
    void theCheapestOfferIsFound() {
        Harvest harvest = new Harvest(List.of(anOffer("840.00"), anOffer("790.00")), List.of(), List.of());
        Optional<Offer> cheapest = harvest.cheapestOffer();
        assertThat(cheapest).isPresent();
        assertThat(cheapest.get().total()).isEqualTo(Money.of("790.00", "EUR"));
    }

    @Test
    void aHarvestWithNoOffersHasNoCheapestOffer() {
        assertThat(quotesOnly(List.of(aQuote())).cheapestOffer()).isEmpty();
    }

    @Test
    void theCheapestQuoteSkipsTheSoldOutDays() {
        Harvest harvest = quotesOnly(List.of(aQuote("420.00"), aQuote("0", 5, true)));
        Optional<Quote> cheapest = harvest.cheapestQuote();
        assertThat(cheapest).isPresent();
        assertThat(cheapest.get().amount()).isEqualTo(Money.of("420.00", "EUR"));
    }

    @Test
    void aHarvestWhereEverythingIsSoldOutHasNoCheapestQuote() {
        assertThat(quotesOnly(List.of(aQuote("0", 4, true))).cheapestQuote()).isEmpty();
    }

    @Test
    void theCheapestAmountPrefersAnOfferOverAQuote() {
        Harvest harvest = new Harvest(List.of(anOffer("840.00")), List.of(aQuote("420.00")), List.of());
        assertThat(harvest.cheapestAmount()).isEqualTo("840.00 EUR");
    }

    @Test
    void theCheapestAmountFallsToAQuoteWhenThereAreNoOffers() {
        assertThat(quotesOnly(List.of(aQuote("420.00"))).cheapestAmount()).isEqualTo("420.00 EUR");
    }

    @Test
    void anEmptyHarvestHasNoCheapestAmount() {
        assertThat(new Harvest().cheapestAmount()).isEmpty();
    }

    @Test
    void aQuoteCanBeLookedUpByItsDay() {
        Harvest harvest = quotesOnly(List.of(aQuote("420.00", 4, false), aQuote("380.00", 5, false)));
        Optional<Quote> found = harvest.quoteOn(LocalDate.of(2025, 7, 5));
        assertThat(found).isPresent();
        assertThat(found.get().amount()).isEqualTo(Money.of("380.00", "EUR"));
        assertThat(harvest.quoteOn(LocalDate.of(2025, 8, 1))).isEmpty();
    }

    @Test
    void extendingTakesEverythingIncludingTheProblems() {
        Harvest left = new Harvest(List.of(anOffer()), List.of(), List.of("one"));
        Harvest right = new Harvest(List.of(), List.of(aQuote()), List.of("two"));
        left.extend(right);
        assertThat(left.size()).isEqualTo(2);
        assertThat(left.problems()).containsExactly("one", "two");
    }

    @Test
    void combiningARunKeepsEveryProblem() {
        Harvest joined = combine(List.of(offersOnly(List.of(anOffer())), nothing("bad payload"), new Harvest()));
        assertThat(joined.offers()).hasSize(1);
        assertThat(joined.problems()).containsExactly("bad payload");
    }

    @Test
    void combiningNothingGivesAnEmptyHarvest() {
        assertThat(combine(List.of()).isEmpty()).isTrue();
    }

    @Test
    void theTwoConvenienceBuildersDoWhatTheySay() {
        assertThat(offersOnly(List.of(anOffer()), "gds").source()).isEqualTo("gds");
        assertThat(offersOnly(List.of(anOffer())).quotes()).isEmpty();
        assertThat(quotesOnly(List.of(aQuote()), "calendar").offers()).isEmpty();
        assertThat(quotesOnly(List.of(aQuote())).quotes()).isNotEmpty();
    }

    @Test
    void aQuoteComparesAndReadsBack() {
        Quote cheap = aQuote("380.00");
        Quote dear = aQuote("420.00", 5, false);
        assertThat(cheap.cheaperThan(dear)).isTrue();
        assertThat(cheap.market()).containsExactly("FRA", "NYC");
        assertThat(cheap).hasToString("FRANYC 2025-07-04 380.00 EUR");
        assertThat(aQuote("0", 4, true).toString()).endsWith("sold out");
    }
}
