package io.github.rkaushikdev.gussetline.audit;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.transatlanticMiles;
import static io.github.rkaushikdev.gussetline.audit.Audit.audit;
import static io.github.rkaushikdev.gussetline.audit.Audit.auditLine;
import static io.github.rkaushikdev.gussetline.audit.Audit.byFinding;
import static io.github.rkaushikdev.gussetline.audit.Audit.recoveredTotal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.engine.Engine;
import io.github.rkaushikdev.gussetline.engine.Priced;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();
    private final MileageTable transatlanticMiles = transatlanticMiles();
    private final Tariffs tariffs = tariffs();

    private Tariffs tariffs() {
        TariffStore fares = new TariffStore();
        fares.add(fare("FRA", "JFK", "420.00"));
        fares.add(fare("JFK", "FRA", "420.00"));
        CombinabilityStore combos = new CombinabilityStore();
        combos.add(Combinability.builder("LH", "KLWAP7RT")
                .roundTrip(true)
                .endOnEnd(true)
                .sameCarrierOnly(false)
                .build());
        return Tariffs.builder(fares)
                .miles(transatlanticMiles)
                .combinability(combos)
                .build();
    }

    private Tariffs nothingFiled() {
        return Tariffs.builder(new TariffStore()).miles(transatlanticMiles).build();
    }

    /** Frankfurt to Chicago, Chicago to New York on United, and home: a circle trip. */
    private Tariffs aroundChicago() {
        TariffStore fares = new TariffStore();
        fares.add(fare("FRA", "ORD", "400.00"));
        fares.add(fare("ORD", "JFK", "50.00").withCarrier("UA").withGlobalIndicator(GlobalIndicator.DO));
        fares.add(fare("JFK", "FRA", "300.00"));
        return Tariffs.builder(fares).miles(transatlanticMiles).build();
    }

    private static Itinerary loop() {
        return new Itinerary(List.of(
                segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
                segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static Itinerary trip() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static Offer anOffer(String base) {
        return anOffer(base, List.of(), Optional.empty(), trip());
    }

    private static Offer anOffer(String base, Itinerary itinerary) {
        return anOffer(base, List.of(), Optional.empty(), itinerary);
    }

    private static Offer anOffer(String base, List<AppliedTax> taxes, Optional<Money> quoted, Itinerary itinerary) {
        return new Offer(
                "gds",
                itinerary,
                new PriceBreakdown(Money.of(base, "EUR"), taxes, quoted),
                List.of("KLWAP7RT"),
                LocalDateTime.of(2025, 2, 14, 9, 0));
    }

    private static AppliedTax tax() {
        return new AppliedTax("DE", Money.of("70.83", "EUR"), "FRA");
    }

    @Test
    void anOfferThatMatchesTheTariffAgrees() {
        Audit result = audit(anOffer("840.00"), tariffs, flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.AGREES);
        assertThat(result.ours()).contains(Money.of("840", "EUR"));
        assertThat(result.material()).isFalse();
        assertThat(result.claimable()).isFalse();
    }

    @Test
    void aGapUnderOneUnitIsNotMaterial() {
        Audit result = audit(anOffer("840.99"), tariffs, flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.AGREES);
        assertThat(result.material()).isFalse();
    }

    @Test
    void aSupplierChargingMoreIsAClaim() {
        Audit result = audit(anOffer("900.00"), tariffs, flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.SUPPLIER_HIGH);
        assertThat(result.difference()).contains(Money.of("60", "EUR"));
        assertThat(result.material()).isTrue();
        assertThat(result.claimable()).isTrue();
        assertThat(result.recovered()).isEqualTo(Money.of("60", "EUR"));
    }

    @Test
    void aSupplierChargingLessIsOurTariffBeingStale() {
        Audit result = audit(anOffer("700.00"), tariffs, flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.SUPPLIER_LOW);
        assertThat(result.material()).isTrue();
        assertThat(result.claimable()).isFalse();
        assertThat(result.recovered()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aBreakdownThatDoesNotAddUpIsATaxDifference() {
        Offer offer = anOffer("900.00", List.of(tax()), Optional.of(Money.of("1000.00", "EUR")), trip());
        Audit result = audit(offer, tariffs, flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.TAX_DIFFERENCE);
        assertThat(result.claimable()).isFalse();
    }

    @Test
    void aGapThatLandsOnAMinimumCheckIsMarkedAsOurs() {
        // Three components adding to 750, and the Frankfurt to Chicago round trip
        // is 800, so the circle trip minimum raises ours. The supplier is lower
        // and it is our own check that made the gap, not their price.
        Audit result = audit(anOffer("600.00", loop()), aroundChicago(), flatConverter, TICKETING, List.of(0, 1));
        assertThat(result.priced()).isPresent();
        assertThat(result.priced().get().construction().minimumAdded()).isPositive();
        assertThat(result.finding()).isSameAs(Finding.MINIMUM_CHECK);
        assertThat(result.claimable()).isFalse();
    }

    @Test
    void anItineraryTheTariffCannotPriceSaysSo() {
        Audit result = audit(anOffer("840.00"), nothingFiled(), flatConverter, TICKETING);
        assertThat(result.finding()).isSameAs(Finding.NOT_PRICEABLE);
        assertThat(result.ours()).isEmpty();
        assertThat(result.material()).isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void aFailureToRepriceIsRecordedOnTheTrace() {
        Trace trail = new Trace();
        audit(anOffer("840.00"), nothingFiled(), flatConverter, TICKETING, trail);
        assertThat(trail.stageFailed(Stage.AUDIT)).isTrue();
    }

    @Test
    void anAgreementIsRecordedAsAPass() {
        Trace trail = new Trace();
        audit(anOffer("840.00"), tariffs, flatConverter, TICKETING, trail);
        Step step = trail.forStage(Stage.AUDIT).get(0);
        assertThat(step.failed()).isFalse();
        assertThat(step.details()).containsEntry("ours", "840 EUR");
    }

    @Test
    void aSupplierLowFindingIsInformationAndNotAFailure() {
        Trace trail = new Trace();
        audit(anOffer("700.00"), tariffs, flatConverter, TICKETING, trail);
        assertThat(trail.stageFailed(Stage.AUDIT)).isFalse();
    }

    @Test
    void aSupplierHighFindingIsAFailure() {
        Trace trail = new Trace();
        audit(anOffer("900.00"), tariffs, flatConverter, TICKETING, trail);
        assertThat(trail.stageFailed(Stage.AUDIT)).isTrue();
    }

    @Test
    void anAuditReadsBackInOneLine() {
        assertThat(audit(anOffer("840.00"), tariffs, flatConverter, TICKETING)).hasToString("gds agrees at 840.00 EUR");
        assertThat(audit(anOffer("900.00"), tariffs, flatConverter, TICKETING))
                .hasToString("gds supplier_high by 60.00 EUR");
    }

    @Test
    void twoLinesThatAgreeHaveNothingToSay() {
        Priced priced = Engine.price(trip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(auditLine(priced.line(), priced)).isEmpty();
    }

    @Test
    void aLineThatRoutesDifferentlyIsReported() {
        Priced priced = Engine.price(trip(), tariffs, flatConverter, TICKETING, "EUR");
        String theirs = "FRA LH BOS 420.00 LH FRA 420.00 NUC840.00END ROE1.000000";
        List<String> found = auditLine(theirs, priced);
        assertThat(found).anyMatch(line -> line.contains("routes"));
    }

    @Test
    void theRecoveredTotalOnlyCountsClaims() {
        List<Audit> audits = List.of(
                audit(anOffer("900.00"), tariffs, flatConverter, TICKETING),
                audit(anOffer("700.00"), tariffs, flatConverter, TICKETING),
                audit(anOffer("840.00"), tariffs, flatConverter, TICKETING));
        assertThat(recoveredTotal(audits, "EUR")).isEqualTo(Money.of("60", "EUR"));
    }

    @Test
    void theRecoveredTotalOfNothingIsZero() {
        assertThat(recoveredTotal(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void aRunIsCountedByFinding() {
        List<Audit> audits = List.of(
                audit(anOffer("900.00"), tariffs, flatConverter, TICKETING),
                audit(anOffer("910.00"), tariffs, flatConverter, TICKETING),
                audit(anOffer("840.00"), tariffs, flatConverter, TICKETING));
        assertThat(byFinding(audits)).isEqualTo(Map.of("supplier_high", 2, "agrees", 1));
    }

    @Test
    void theSplitTheSupplierUsedIsTheOneWeRepriceAgainst() {
        // The default split breaks somewhere the tariff has no fare for, and the
        // audit says so rather than quietly pricing a different journey.
        Audit guessed = audit(anOffer("600.00", loop()), aroundChicago(), flatConverter, TICKETING);
        assertThat(guessed.finding()).isSameAs(Finding.NOT_PRICEABLE);

        Audit told = audit(anOffer("600.00", loop()), aroundChicago(), flatConverter, TICKETING, List.of(0, 1));
        assertThat(told.finding()).isNotSameAs(Finding.NOT_PRICEABLE);
    }

    @Test
    void thePricedResultIsKeptForTheLetter() {
        Audit result = audit(anOffer("900.00"), tariffs, flatConverter, TICKETING);
        assertThat(result.priced()).isPresent();
        assertThat(result.priced().get().totalNuc()).isEqualByComparingTo("840.00");
        assertThat(result.priced().get().line()).contains("NUC840.00END");
    }
}
