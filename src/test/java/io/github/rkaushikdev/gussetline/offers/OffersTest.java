package io.github.rkaushikdev.gussetline.offers;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.offers.Offers.cheapest;
import static io.github.rkaushikdev.gussetline.offers.Offers.deduplicate;
import static io.github.rkaushikdev.gussetline.offers.Offers.spread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OffersTest {

    private static AppliedTax tax(String code, String amount, String currency, boolean carrier) {
        return new AppliedTax(code, Money.of(amount, currency), "FRA", carrier);
    }

    /** The German transport tax, charged at Frankfurt. */
    private static AppliedTax tax() {
        return tax("DE", "70.83", "EUR", false);
    }

    private static AppliedTax tax(String code) {
        return tax(code, "70.83", "EUR", false);
    }

    private static AppliedTax taxIn(String currency) {
        return tax("DE", "70.83", currency, false);
    }

    private static Itinerary trip(String departure) {
        return new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", departure, "2025-03-04 13:05")));
    }

    private static Itinerary trip() {
        return trip("2025-03-04 10:25");
    }

    /** A Frankfurt to New York offer from the GDS, with whatever a test changes about it. */
    private static Offer.Builder offer() {
        return Offer.builder()
                .source("gds")
                .itinerary(trip())
                .breakdown(new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax())))
                .fareBases(List.of("KLWAP7RT"))
                .observedAt(LocalDateTime.of(2025, 2, 1, 9, 0));
    }

    private static PriceBreakdown pricedAt(String base) {
        return new PriceBreakdown(Money.of(base, "EUR"), List.of(tax()));
    }

    @Test
    void aBagAllowanceIsPiecesOrKilosAndReadsBack() {
        assertThat(Baggage.ofPieces(2)).hasToString("2PC");
        assertThat(Baggage.ofKilos(23)).hasToString("23KG");
        assertThat(new Baggage()).hasToString("unstated");
        assertThat(new Baggage().isStated()).isFalse();
        assertThat(Baggage.ofPieces(0).isStated()).isTrue();
    }

    @Test
    void aNegativeAllowanceIsRefused() {
        assertThatThrownBy(() -> Baggage.ofPieces(-1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Baggage.ofKilos(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBreakdownIsInOneCurrency() {
        assertThatThrownBy(() -> new PriceBreakdown(Money.of("420", "EUR"), List.of(taxIn("USD"))))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new PriceBreakdown(Money.of("420", "EUR"), List.of(), Money.of("500", "USD")))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theTaxTotalLeavesTheCarrierSurchargeOut() {
        PriceBreakdown breakdown =
                new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax(), tax("YQ", "180.00", "EUR", true)));
        assertThat(breakdown.taxTotal()).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(breakdown.surchargeTotal()).isEqualTo(Money.of("180.00", "EUR"));
        assertThat(breakdown.computedTotal()).isEqualTo(Money.of("670.83", "EUR"));
    }

    @Test
    void withoutAQuotedTotalThePartsAreTheTotal() {
        PriceBreakdown breakdown = new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax()));
        assertThat(breakdown.statedTotal()).isEqualTo(Money.of("490.83", "EUR"));
        assertThat(breakdown.addsUp()).isTrue();
        assertThat(breakdown.discrepancy().isZero()).isTrue();
    }

    @Test
    void aQuotedTotalThatDisagreesIsKeptAndTheGapIsReported() {
        PriceBreakdown breakdown =
                new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax()), Money.of("505.00", "EUR"));
        assertThat(breakdown.statedTotal()).isEqualTo(Money.of("505.00", "EUR"));
        assertThat(breakdown.computedTotal()).isEqualTo(Money.of("490.83", "EUR"));
        assertThat(breakdown.discrepancy()).isEqualTo(Money.of("14.17", "EUR"));
        assertThat(breakdown.addsUp()).isFalse();
    }

    @Test
    void aQuotedTotalBelowThePartsGivesANegativeGap() {
        PriceBreakdown breakdown =
                new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax()), Money.of("480.00", "EUR"));
        assertThat(breakdown.discrepancy().isNegative()).isTrue();
    }

    @Test
    void taxCodesComeBackSortedAndDeduplicated() {
        PriceBreakdown breakdown =
                new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax("RA"), tax("DE"), tax("DE")));
        assertThat(breakdown.taxCodes()).containsExactly("DE", "RA");
    }

    @Test
    void breakdownReadsBackAsThreeParts() {
        assertThat(new PriceBreakdown(Money.of("420.00", "EUR"), List.of(tax())))
                .hasToString("420.00 EUR + 70.83 EUR tax + 0 EUR yq");
    }

    @Test
    void anOfferNamesItsSourceAndNormalisesIt() {
        assertThat(offer().source(" GDS ").build().source()).isEqualTo("gds");
        assertThatThrownBy(() -> offer().source("   ").build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void anOfferCarriesAtLeastOneFareBasis() {
        assertThatThrownBy(() -> offer().fareBases(List.of()).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aNegativeSeatCountIsRefused() {
        assertThatThrownBy(() -> offer().seats(-1).build()).isInstanceOf(ValidationError.class);
        assertThat(offer().seats(0).build().seats()).contains(0);
    }

    @Test
    void anOfferForwardsTheEndsAndTheTotal() {
        Offer o = offer().build();
        assertThat(o.origin()).isEqualTo("FRA");
        assertThat(o.destination()).isEqualTo("JFK");
        assertThat(o.total()).isEqualTo(Money.of("490.83", "EUR"));
        assertThat(o.currency()).isEqualTo("EUR");
        assertThat(o.carriers()).containsExactly("LH");
        assertThat(o.isDirect()).isTrue();
    }

    @Test
    void theFingerprintIgnoresWhenItWasSeen() {
        Offer early = offer().observedAt(LocalDateTime.of(2025, 2, 1, 9, 0)).build();
        Offer late = offer().observedAt(LocalDateTime.of(2025, 2, 3, 17, 30)).build();
        assertThat(early.fingerprint()).isEqualTo(late.fingerprint());
    }

    @Test
    void theFingerprintIgnoresWhichSupplierSentIt() {
        assertThat(offer().source("gds").build().fingerprint())
                .isEqualTo(offer().source("ndc").build().fingerprint());
    }

    @Test
    void theFingerprintMovesWhenThePriceMoves() {
        Offer dearer = offer().breakdown(pricedAt("520.00")).build();
        assertThat(offer().build().fingerprint()).isNotEqualTo(dearer.fingerprint());
    }

    @Test
    void theFingerprintMovesWhenTheFlightsMove() {
        Offer later = offer().itinerary(trip("2025-03-04 16:25")).build();
        assertThat(offer().build().fingerprint()).isNotEqualTo(later.fingerprint());
    }

    @Test
    void theFingerprintMovesWhenTheFareBasisMoves() {
        assertThat(offer().build().fingerprint())
                .isNotEqualTo(offer().fareBases(List.of("QLWAP14RT")).build().fingerprint());
    }

    @Test
    void theSeriesKeyGroupsAMarketAndADate() {
        // Cities, because a watch is on a market. A series keyed on Newark never meets the watch keyed
        // on New York and nothing raises to say so.
        assertThat(offer().build().seriesKey()).isEqualTo(new Series.Parts("FRA", "NYC", "2025-03-04", "Y"));
    }

    @Test
    void twoOffersCompareAndSubtractInOneCurrency() {
        Offer dearer = offer().breakdown(pricedAt("520.00")).build();
        assertThat(offer().build().cheaperThan(dearer)).isTrue();
        assertThat(dearer.differenceFrom(offer().build())).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void twoOffersInDifferentCurrenciesRefuseToCompare() {
        Offer other = offer().breakdown(new PriceBreakdown(Money.of("520.00", "USD"), List.of(taxIn("USD"))))
                .build();
        Offer euros = offer().build();
        assertThatThrownBy(() -> euros.cheaperThan(other)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> euros.differenceFrom(other)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aNoteAppendsAndLeavesTheOfferItWasAddedToAlone() {
        Offer plain = offer().build();
        Offer noted = plain.withNote("quoted total disagrees").withNote("fallback mileage");
        assertThat(plain.notes()).isEmpty();
        assertThat(noted.notes()).containsExactly("quoted total disagrees", "fallback mileage");
    }

    @Test
    void stringFormWalksTheRoute() {
        assertThat(offer().build()).hasToString("gds FRAJFK KLWAP7RT 490.83 EUR");
    }

    @Test
    void cheapestTakesTheLowestTotal() {
        Offer dearer = offer().breakdown(pricedAt("520.00")).build();
        assertThat(cheapest(List.of(dearer, offer().build())).total()).isEqualTo(Money.of("490.83", "EUR"));
    }

    @Test
    void cheapestBreaksATieOnTheEarlierSighting() {
        Offer early = offer().source("ndc")
                .observedAt(LocalDateTime.of(2025, 2, 1, 8, 0))
                .build();
        Offer late = offer().source("gds")
                .observedAt(LocalDateTime.of(2025, 2, 1, 9, 0))
                .build();
        assertThat(cheapest(List.of(late, early)).source()).isEqualTo("ndc");
    }

    @Test
    void cheapestRefusesAnEmptyListAndAMixedCurrencyOne() {
        assertThatThrownBy(() -> cheapest(List.of())).isInstanceOf(ValidationError.class);
        Offer other =
                offer().breakdown(new PriceBreakdown(Money.of("520.00", "USD"))).build();
        List<Offer> mixed = List.of(offer().build(), other);
        assertThatThrownBy(() -> cheapest(mixed)).isInstanceOf(ValidationError.class);
    }

    @Test
    void deduplicateKeepsTheFirstSighting() {
        Offer early = offer().observedAt(LocalDateTime.of(2025, 2, 1, 8, 0)).build();
        Offer late = offer().observedAt(LocalDateTime.of(2025, 2, 1, 9, 0)).build();
        List<Offer> kept = deduplicate(List.of(late, early));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).observedAt()).isEqualTo(LocalDateTime.of(2025, 2, 1, 8, 0));
    }

    @Test
    void deduplicateKeepsTwoGenuinelyDifferentOffers() {
        Offer dearer = offer().breakdown(pricedAt("520.00")).build();
        assertThat(deduplicate(List.of(offer().build(), dearer))).hasSize(2);
    }

    @Test
    void spreadIsZeroOnFewerThanTwo() {
        assertThat(spread(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(spread(List.of(offer().build()))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void spreadIsTheGapBetweenTheEnds() {
        Offer dearer = offer().breakdown(pricedAt("620.00")).build();
        assertThat(spread(List.of(offer().build(), dearer))).isEqualByComparingTo("200.00");
    }

    @Test
    void theSharedClockHelperReadsAWallClock() {
        assertThat(at("2025-03-04 10:25")).isEqualTo(LocalDateTime.of(2025, 3, 4, 10, 25));
    }
}
