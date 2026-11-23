package io.github.rkaushikdev.gussetline.engine;

import static io.github.rkaushikdev.gussetline.Fixtures.combinable;
import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.oneWayFare;
import static io.github.rkaushikdev.gussetline.Fixtures.permissiveCombinability;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.transatlanticMiles;
import static io.github.rkaushikdev.gussetline.engine.Engine.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.rules.Stopovers;
import io.github.rkaushikdev.gussetline.surcharges.QSurcharge;
import io.github.rkaushikdev.gussetline.surcharges.SurchargeTable;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class EngineTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();
    private final MileageTable transatlanticMiles = transatlanticMiles();
    private final Tariffs tariffs = tariffs();

    private Tariffs tariffs() {
        TariffStore fares = new TariffStore();
        fares.add(fare("FRA", "JFK", "420.00"));
        fares.add(fare("JFK", "FRA", "420.00"));
        CombinabilityStore combos = new CombinabilityStore();
        combos.add(combinable());
        return Tariffs.builder(fares)
                .miles(transatlanticMiles)
                .combinability(combos)
                .build();
    }

    private static TariffStore faresOf(Fare... fares) {
        TariffStore store = new TariffStore();
        store.addMany(List.of(fares));
        return store;
    }

    private static Itinerary roundTrip() {
        return new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05")));
    }

    private static Itinerary oneWay() {
        return new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary viaHeathrow() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
    }

    private static Itinerary twoDaysInLondon() {
        return new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-06 11:05", "2025-03-06 14:00")));
    }

    private static Itinerary viaChicago() {
        return new Itinerary(List.of(
                segment("LH", "430", "FRA", "ORD", "2025-03-04 10:00", "2025-03-04 12:40"),
                segment("UA", "200", "ORD", "JFK", "2025-03-08 08:00", "2025-03-08 10:30")));
    }

    private static List<Step> steps(Priced result) {
        return StreamSupport.stream(result.trace().spliterator(), false).toList();
    }

    private static boolean anyStepSays(Priced result, String text) {
        return steps(result).stream().anyMatch(step -> step.message().contains(text));
    }

    @Test
    void aRoundTripPricesEndToEnd() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("840.00");
        assertThat(result.baseFare()).isEqualTo(Money.of("840", "EUR"));
        assertThat(result.issuable()).isTrue();
    }

    @Test
    void theLineReadsLikeATicket() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(result.line()).isEqualTo("FRA LH NYC 420.00 LH FRA 420.00 NUC840.00END ROE1.000000");
    }

    @Test
    void theTraceCarriesASelectionForEachComponent() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(result.trace().forStage(Stage.SELECTION)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.trace().failed()).isFalse();
    }

    @Test
    void aOneWayPricesOnOneComponent() {
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "310.00"));
        Priced result = price(
                oneWay(), Tariffs.builder(fares).miles(transatlanticMiles).build(), flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("310.00");
        assertThat(result.line()).isEqualTo("FRA LH NYC 310.00 NUC310.00END ROE1.000000");
    }

    @Test
    void aMarketWithNothingPublishedRefuses() {
        Tariffs empty =
                Tariffs.builder(new TariffStore()).miles(transatlanticMiles).build();
        assertThatThrownBy(() -> price(roundTrip(), empty, flatConverter, TICKETING, "EUR"))
                .isInstanceOf(NoFareFound.class);
    }

    @Test
    void aConnectionUnderTheMinimumPricesButIsNotIssuable() {
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "420.00"));
        Itinerary rushed = new Itinerary(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 08:20", "2025-03-04 11:00")));
        Trace trace = new Trace("rushed");
        Priced result = price(
                rushed,
                Tariffs.builder(fares).miles(transatlanticMiles).build(),
                flatConverter,
                TICKETING,
                "EUR",
                Optional.empty(),
                Optional.of(trace));
        assertThat(result.refusals()).containsExactly("connection under the minimum at LHR 40 minutes, 90 needed");
        assertThat(result.issuable()).isFalse();
        assertThat(trace.steps()).anyMatch(step -> step.message().startsWith("connection under the minimum"));
    }

    @Test
    void aConnectionIsMarkedOnTheLine() {
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "420.00"));
        Priced result = price(
                viaHeathrow(),
                Tariffs.builder(fares).miles(transatlanticMiles).build(),
                flatConverter,
                TICKETING,
                "EUR");
        assertThat(result.line()).contains("X/LON");
    }

    @Test
    void aSurchargeLandsBeforeTheMinimumChecks() {
        SurchargeTable charges = new SurchargeTable();
        charges.add(new QSurcharge("LH", "FRA", "JFK", Money.of("30.00", "EUR")));
        Tariffs loaded = Tariffs.builder(tariffs.fares())
                .miles(transatlanticMiles)
                .surcharges(charges)
                .combinability(tariffs.combinability())
                .build();
        Priced result = price(roundTrip(), loaded, flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("900.00");
        assertThat(result.line()).contains("Q30.00");
        assertThat(result.trace().forStage(Stage.SURCHARGE)).hasSize(2);
    }

    @Test
    void aHigherIntermediatePointRaisesTheComponent() {
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "420.00"), oneWayFare("FRA", "ORD", "560.00"));
        Priced result = price(
                viaChicago(),
                Tariffs.builder(fares).miles(transatlanticMiles).build(),
                flatConverter,
                TICKETING,
                "EUR",
                List.of());
        assertThat(result.totalNuc()).isEqualByComparingTo("560.00");
        assertThat(anyStepSays(result, "higher intermediate point")).isTrue();
    }

    @Test
    void aFareWithNoCombinabilityFiledPricesButIsNotIssuable() {
        TariffStore fares = faresOf(fare("FRA", "JFK", "420.00"), fare("JFK", "FRA", "420.00"));
        Priced result = price(
                roundTrip(), Tariffs.builder(fares).miles(transatlanticMiles).build(), flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("840.00");
        assertThat(result.issuable()).isFalse();
        assertThat(result.refusals().get(0)).contains("has no combinability filed");
    }

    @Test
    void anExplicitBreakIsUsedInsteadOfTheTurnaround() {
        TariffStore fares = faresOf(
                oneWayFare("FRA", "LHR", "90.00").withGlobalIndicator(GlobalIndicator.EH),
                oneWayFare("LHR", "JFK", "300.00"));
        CombinabilityStore combos = new CombinabilityStore();
        combos.add(combinable());
        Tariffs loaded = Tariffs.builder(fares)
                .miles(transatlanticMiles)
                .combinability(combos)
                .build();
        Priced result = price(viaHeathrow(), loaded, flatConverter, TICKETING, "EUR", List.of(0));
        assertThat(result.totalNuc()).isEqualByComparingTo("390.00");
        assertThat(result.line()).startsWith("FRA LH LON 90.00 LH NYC 300.00");
    }

    @Test
    void theConversionIntoTheSellingCurrencyHappensOnce() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        List<Step> conversions = result.trace().forStage(Stage.CONVERSION).stream()
                .filter(step -> step.message().contains("becomes"))
                .toList();
        assertThat(conversions).hasSize(1);
    }

    @Test
    void aPricedResultReadsBackAsTheFareAndTheLine() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(result.toString()).startsWith("840 EUR on FRA LH NYC");
    }

    @Test
    void aSplitThatDoesNotFitTheShapePricesEndOnEnd() {
        // Two components on a journey that is a one way. Grouping them into one
        // unit would drop the second, so they become two units joined end on end.
        TariffStore fares = faresOf(
                oneWayFare("FRA", "LHR", "90.00").withGlobalIndicator(GlobalIndicator.EH),
                oneWayFare("LHR", "JFK", "300.00"));
        CombinabilityStore combos = new CombinabilityStore();
        combos.add(combinable());
        Priced result = price(
                viaHeathrow(),
                Tariffs.builder(fares)
                        .miles(transatlanticMiles)
                        .combinability(combos)
                        .build(),
                flatConverter,
                TICKETING,
                "EUR",
                List.of(0));
        assertThat(result.totalNuc()).isEqualByComparingTo("390.00");
        assertThat(result.construction().units()).hasSize(2);
        assertThat(result.issuable()).isTrue();
    }

    @Test
    void theBandAppliesToTheFareTheMinimumCheckLeftGoverning() {
        // The Chicago fare governs after the higher intermediate point check, and
        // the five percent band is five percent of Chicago and not of New York.
        MileageTable table = new MileageTable();
        table.add("FRA", "NYC", 4000);
        table.add("FRA", "CHI", 4300);
        table.add("CHI", "NYC", 740);
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "400.00"), oneWayFare("FRA", "ORD", "600.00"));
        Priced result = price(
                viaChicago(), Tariffs.builder(fares).miles(table).build(), flatConverter, TICKETING, "EUR", List.of());
        // 600 governing, five percent of that is 30, not five percent of 400.
        assertThat(result.totalNuc()).isEqualByComparingTo("630.00");
        assertThat(result.line()).contains("5M");
    }

    @Test
    void everyComponentReachesTheLineWhenTheSplitIsWide() {
        TariffStore fares = faresOf(
                oneWayFare("FRA", "LHR", "90.00").withGlobalIndicator(GlobalIndicator.EH),
                oneWayFare("LHR", "JFK", "300.00"));
        Priced result = price(
                viaHeathrow(),
                Tariffs.builder(fares).miles(transatlanticMiles).build(),
                flatConverter,
                TICKETING,
                "EUR",
                List.of(0));
        assertThat(result.line()).startsWith("FRA LH LON 90.00 LH NYC 300.00");
        assertThat(result.line()).endsWith("NUC390.00END ROE1.000000");
    }

    private static RuleStore ruleFor(Provision... provisions) {
        RuleStore store = new RuleStore();
        store.add(new RuleSet("LH", "1100", List.of(provisions)));
        return store;
    }

    private Tariffs roundTripWithRules(RuleStore rules) {
        TariffStore fares = faresOf(fare("FRA", "JFK", "420.00"), fare("JFK", "FRA", "420.00"));
        return Tariffs.builder(fares)
                .miles(transatlanticMiles)
                .combinability(permissiveCombinability())
                .rules(rules)
                .build();
    }

    private Tariffs oneWayWithStopovers(int free) {
        TariffStore fares = faresOf(oneWayFare("FRA", "JFK", "420.00"));
        Stopovers charged = Stopovers.builder()
                .permitted(2)
                .free(free)
                .chargeNuc(new BigDecimal("60.00"))
                .build();
        return Tariffs.builder(fares)
                .miles(transatlanticMiles)
                .combinability(permissiveCombinability())
                .rules(ruleFor(charged))
                .build();
    }

    @Test
    void aFareWhoseRuleRefusesStillPricesAndIsNotIssuable() {
        Tariffs loaded = roundTripWithRules(ruleFor(new MinimumStay(21)));
        Priced result = price(roundTrip(), loaded, flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("840.00");
        assertThat(result.issuable()).isFalse();
        assertThat(result.refusals()).anyMatch(refusal -> refusal.contains("cat 6"));
    }

    @Test
    void aFareWhoseRuleIsSatisfiedStaysIssuable() {
        Tariffs loaded = roundTripWithRules(ruleFor(new MinimumStay(7)));
        Priced result = price(roundTrip(), loaded, flatConverter, TICKETING, "EUR");
        assertThat(result.issuable()).isTrue();
    }

    @Test
    void aFareWithNoRuleFiledIsNotedAndNotRefused() {
        Tariffs loaded = roundTripWithRules(new RuleStore());
        Priced result = price(roundTrip(), loaded, flatConverter, TICKETING, "EUR");
        assertThat(result.issuable()).isTrue();
        assertThat(anyStepSays(result, "no rule 1100 filed")).isTrue();
    }

    @Test
    void aStopoverChargeLandsOnTheComponentAsASurcharge() {
        Priced result = price(twoDaysInLondon(), oneWayWithStopovers(0), flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("480.00");
        assertThat(anyStepSays(result, "stopover charge")).isTrue();
    }

    @Test
    void aFreeStopoverAllowanceChargesNothing() {
        Priced result = price(twoDaysInLondon(), oneWayWithStopovers(1), flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("420.00");
    }

    @Test
    void theSameRoutingIsChargedOrNotByTheClockAlone() {
        Tariffs loaded = oneWayWithStopovers(0);
        assertThat(price(viaHeathrow(), loaded, flatConverter, TICKETING, "EUR").totalNuc())
                .isEqualByComparingTo("420.00");
        assertThat(price(twoDaysInLondon(), loaded, flatConverter, TICKETING, "EUR")
                        .totalNuc())
                .isEqualByComparingTo("480.00");
    }

    /** Frankfurt to New York, by way of a week in Vienna. */
    private static Itinerary aSideTripJourney() {
        return new Itinerary(List.of(
                segment("LH", "100", "FRA", "VIE", "2025-03-04 08:00", "2025-03-04 09:30"),
                segment("LH", "101", "VIE", "FRA", "2025-03-11 18:00", "2025-03-11 19:30"),
                segment("LH", "400", "FRA", "JFK", "2025-03-12 10:25", "2025-03-12 13:05")));
    }

    private static Tariffs withVienna() {
        TariffStore fares = faresOf(
                oneWayFare("FRA", "JFK", "620.00").withFareBasis("KOW"),
                oneWayFare("FRA", "VIE", "90.00").withFareBasis("KVIE").withGlobalIndicator(GlobalIndicator.EH),
                oneWayFare("VIE", "FRA", "90.00").withFareBasis("KVIE").withGlobalIndicator(GlobalIndicator.EH));
        MileageTable miles = new MileageTable();
        miles.add("FRA", "JFK", 3851);
        miles.add("FRA", "VIE", 380);
        CombinabilityStore combos = new CombinabilityStore();
        for (String basis : List.of("KOW", "KVIE")) {
            combos.add(combinable(basis));
        }
        return Tariffs.builder(fares).miles(miles).combinability(combos).build();
    }

    @Test
    void anExcursionPricesAsItsOwnComponent() {
        Priced result = price(aSideTripJourney(), withVienna(), flatConverter, TICKETING, "EUR");
        List<String> bases = result.construction().components().stream()
                .map(FareComponent::fare)
                .flatMap(Optional::stream)
                .map(Fare::fareBasis)
                .toList();
        assertThat(bases).filteredOn("KVIE"::equals).hasSize(2);
        assertThat(bases).contains("KOW");
    }

    @Test
    void theExcursionIsKeptOffTheTransatlanticComponent() {
        // Vienna on the path would put a thousand miles east onto a fare that has
        // nothing to do with it, and the component would fail the ladder.
        Priced result = price(aSideTripJourney(), withVienna(), flatConverter, TICKETING, "EUR");
        List<FareComponent> atlantic = result.construction().components().stream()
                .filter(component -> component.offPoint().equals("NYC"))
                .toList();
        assertThat(atlantic).hasSize(1);
        assertThat(atlantic.get(0).viaPoints()).isEmpty();
    }

    @Test
    void theShapeIsReadAfterTheExcursionComesOut() {
        Priced result = price(aSideTripJourney(), withVienna(), flatConverter, TICKETING, "EUR");
        List<Step> shapes = steps(result).stream()
                .filter(step -> step.stage() == Stage.SHAPE)
                .toList();
        assertThat(shapes).isNotEmpty();
        assertThat(shapes.get(0).toString()).contains("leaving a OW");
    }

    @Test
    void theWholeThingStillAddsUp() {
        Priced result = price(aSideTripJourney(), withVienna(), flatConverter, TICKETING, "EUR");
        assertThat(result.totalNuc()).isEqualByComparingTo("800.00");
    }

    @Test
    void aJourneyWithNoExcursionRecordsNoShapeNote() {
        Priced result = price(roundTrip(), tariffs, flatConverter, TICKETING, "EUR");
        assertThat(steps(result).stream().filter(step -> step.stage() == Stage.SHAPE))
                .isEmpty();
    }
}
