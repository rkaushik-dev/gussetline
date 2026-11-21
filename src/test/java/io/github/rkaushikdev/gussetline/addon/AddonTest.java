package io.github.rkaushikdev.gussetline.addon;

import static io.github.rkaushikdev.gussetline.addon.AddOns.cheapestFor;
import static io.github.rkaushikdev.gussetline.addon.AddOns.construct;
import static io.github.rkaushikdev.gussetline.addon.AddOns.constructAtDestination;
import static io.github.rkaushikdev.gussetline.addon.AddOns.constructAtOrigin;
import static io.github.rkaushikdev.gussetline.addon.AddOns.oneWayOnly;
import static io.github.rkaushikdev.gussetline.addon.AddOns.preferPublished;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.Market;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddonTest {

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    /** Lufthansa's Frankfurt to New York round trip half, with whatever a test changes about it. */
    private static Fare.Builder gatewayFiling() {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KLWAP7RT")
                .amount(eur("420.00"))
                .owrt(OwRt.ROUND_TRIP_HALF)
                .globalIndicator(GlobalIndicator.AT)
                .ruleNumber("0100")
                .routingNumber("0011")
                .tariffNumber("1")
                .footnote("12")
                .fareType(FareType.PUBLISHED)
                .effective(LocalDate.of(2025, 1, 1))
                .discontinue(LocalDate.of(2025, 12, 31));
    }

    private static Fare gateway() {
        return gatewayFiling().build();
    }

    private static Fare gateway(String amount) {
        return gatewayFiling().amount(eur(amount)).build();
    }

    /** The Hamburg filing of the same market, which competes with a construction onto Frankfurt. */
    private static Fare hamburgFiling(String amount) {
        return gatewayFiling()
                .origin("HAM")
                .amount(eur(amount))
                .fareBasis("KHAM")
                .build();
    }

    /** The Hamburg arbitrary onto Frankfurt at the origin end. */
    private static AddOn arbitrary() {
        return arbitrary("40.00");
    }

    private static AddOn arbitrary(String amount) {
        return new AddOn("LH", "FRA", "HAM", eur(amount));
    }

    /** The Boston arbitrary off New York at the destination end. */
    private static AddOn bostonArbitrary(String amount) {
        return new AddOn("LH", "JFK", "BOS", eur(amount), End.DESTINATION);
    }

    @Test
    void anArbitraryReachesAPointFromAGateway() {
        AddOn made = arbitrary();
        assertThat(made.point()).isEqualTo("HAM");
        assertThat(made.gateway()).isEqualTo("FRA");
        assertThat(made.market()).isEqualTo(new Market("HAM", "FRA"));
    }

    @Test
    void anArbitraryAtTheFarEndReadsTheOtherWayRound() {
        AddOn made = bostonArbitrary("40.00");
        assertThat(made.market()).isEqualTo(new Market("NYC", "BOS"));
    }

    @Test
    void anArbitraryReadsBackInOneLine() {
        assertThat(arbitrary()).hasToString("LH HAM/FRA 40.00 EUR at the origin");
    }

    @Test
    void anArbitraryThatGoesNowhereIsRefused() {
        assertThatThrownBy(() -> new AddOn("LH", "FRA", "FRA", eur("40.00")))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("somewhere else");
    }

    @Test
    void aNegativeArbitraryIsRefused() {
        assertThatThrownBy(() -> arbitrary("-10.00"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not negative");
    }

    @Test
    void aConstructionAtTheOriginEndReachesTheInteriorPoint() {
        Constructed made = constructAtOrigin(gateway(), arbitrary());
        assertThat(made.fare().origin()).isEqualTo("HAM");
        assertThat(made.fare().destination()).isEqualTo("NYC");
        assertThat(made.fare().amount()).isEqualTo(eur("460.00"));
    }

    @Test
    void aConstructionAtTheFarEndReachesTheOtherWay() {
        Constructed made = constructAtDestination(gateway(), bostonArbitrary("40.00"));
        assertThat(made.fare().origin()).isEqualTo("FRA");
        assertThat(made.fare().destination()).isEqualTo("BOS");
    }

    @Test
    void aConstructionAtBothEndsAddsBoth() {
        Constructed made = construct(gateway(), arbitrary("40.00"), bostonArbitrary("30.00"));
        assertThat(made.fare().amount()).isEqualTo(eur("490.00"));
        assertThat(made.arbitraryTotal()).isEqualByComparingTo("70.00");
        assertThat(made.ends()).containsExactly(End.ORIGIN, End.DESTINATION);
    }

    @Test
    void aConstructionWithNoArbitraryAtAllIsRefused() {
        assertThatThrownBy(() -> construct(gateway(), Optional.empty(), Optional.empty()))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("needs an arbitrary");
    }

    @Test
    void theRuleAndTheFootnoteComeOffTheGatewayFare() {
        // An add-on is an amount and carries no conditions. Reading its blank
        // rule number leaves the constructed fare with no conditions at all.
        Constructed made = constructAtOrigin(gateway(), arbitrary());
        assertThat(made.fare().ruleNumber()).isEqualTo("0100");
        assertThat(made.fare().footnote()).isEqualTo("12");
        assertThat(made.fare().tariffNumber()).isEqualTo("1");
    }

    @Test
    void theIndicatorComesOffTheGatewayAndIsNotWorkedOutAgain() {
        // Nuremberg to New York over the Atlantic is still AT. Re-deriving it
        // from a domestic first sector files it as EH.
        Constructed made = constructAtOrigin(gateway(), arbitrary());
        assertThat(made.fare().globalIndicator()).isSameAs(GlobalIndicator.AT);
    }

    @Test
    void theSaleAndTravelWindowsComeOffTheGatewayFare() {
        Constructed made = constructAtOrigin(gateway(), arbitrary());
        assertThat(made.fare().effective()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(made.fare().discontinue()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void anArbitraryFiledForTheOtherEndIsRefused() {
        AddOn farEnd = new AddOn("LH", "FRA", "HAM", eur("40.00"), End.DESTINATION);
        assertThatThrownBy(() -> constructAtOrigin(gateway(), farEnd))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("the other end");
    }

    @Test
    void anArbitraryBelongingToAnotherCarrierIsRefused() {
        AddOn united = new AddOn("UA", "FRA", "HAM", eur("40.00"));
        assertThatThrownBy(() -> constructAtOrigin(gateway(), united))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("its own carrier");
    }

    @Test
    void anArbitraryThatDoesNotMeetTheFareIsRefused() {
        AddOn offMunich = new AddOn("LH", "MUC", "HAM", eur("40.00"));
        assertThatThrownBy(() -> constructAtOrigin(gateway(), offMunich))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("does not meet");
    }

    @Test
    void anArbitraryInAnotherCurrencyIsRefused() {
        AddOn inPounds = new AddOn("LH", "FRA", "HAM", Money.of("40.00", "GBP"));
        assertThatThrownBy(() -> constructAtOrigin(gateway(), inPounds))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("own currency");
    }

    @Test
    void aNegotiatedFareIsNotAGatewayFare() {
        Fare negotiated = gatewayFiling().fareType(FareType.NEGOTIATED).build();
        assertThatThrownBy(() -> constructAtOrigin(negotiated, arbitrary()))
                .isInstanceOf(ConstructionError.class)
                .hasMessageContaining("not a gateway fare");
    }

    @Test
    void aConstructionReadsBackWithWhatItWasBuiltFrom() {
        Constructed made = constructAtOrigin(gateway(), arbitrary());
        assertThat(made.toString()).contains("built on");
        assertThat(made.toString()).contains("40.00");
    }

    @Test
    void aStoreHandsBackTheArbitrariesForAPointCheapestFirst() {
        AddOnStore store = new AddOnStore();
        store.addMany(List.of(arbitrary("60.00"), arbitrary("40.00")));
        List<AddOn> found = store.forPoint("LH", "HAM", "FRA", End.ORIGIN);
        assertThat(found)
                .extracting(addOn -> addOn.amount().amount())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("60.00"));
    }

    @Test
    void aStoreKeepsTheTwoEndsApart() {
        AddOnStore store = new AddOnStore();
        store.add(arbitrary());
        assertThat(store.forPoint("LH", "HAM", "FRA", End.DESTINATION)).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aStoreListsTheGatewaysAPointCanBeReachedFrom() {
        AddOnStore store = new AddOnStore();
        store.add(new AddOn("LH", "FRA", "HAM", eur("40.00")));
        store.add(new AddOn("LH", "MUC", "HAM", eur("40.00")));
        assertThat(store.gateways("LH", "HAM")).containsExactly("FRA", "MUC");
    }

    @Test
    void aStoreFoldsAirportsToCities() {
        AddOnStore store = new AddOnStore();
        store.add(bostonArbitrary("40.00"));
        assertThat(store.forPoint("LH", "BOS", "EWR", End.DESTINATION)).isNotEmpty();
    }

    @Test
    void theCheapestConstructionForAMarketIsFound() {
        AddOnStore store = new AddOnStore();
        store.addMany(List.of(arbitrary("60.00"), arbitrary("40.00")));
        Optional<Constructed> best = cheapestFor("LH", "HAM", "JFK", List.of(gateway()), store);
        assertThat(best).isPresent();
        assertThat(best.get().fare().amount()).isEqualTo(eur("460.00"));
    }

    @Test
    void theCheapestGatewayFareWinsToo() {
        AddOnStore store = new AddOnStore();
        store.add(arbitrary("40.00"));
        Optional<Constructed> best =
                cheapestFor("LH", "HAM", "JFK", List.of(gateway("420.00"), gateway("380.00")), store);
        assertThat(best).isPresent();
        assertThat(best.get().fare().amount()).isEqualTo(eur("420.00"));
    }

    @Test
    void aMarketWithNoArbitraryHasNoConstruction() {
        assertThat(cheapestFor("LH", "HAM", "JFK", List.of(gateway()), new AddOnStore()))
                .isEmpty();
    }

    @Test
    void aMarketWithNoGatewayFareHasNoConstruction() {
        AddOnStore store = new AddOnStore();
        store.add(arbitrary());
        assertThat(cheapestFor("LH", "HAM", "JFK", List.of(), store)).isEmpty();
    }

    @Test
    void oneArbitraryAtOneEndIsEnough() {
        AddOnStore store = new AddOnStore();
        store.add(arbitrary());
        Optional<Constructed> best = cheapestFor("LH", "HAM", "JFK", List.of(gateway()), store);
        assertThat(best).isPresent();
        assertThat(best.get().destinationAddOn()).isEmpty();
    }

    @Test
    void aPublishedFareBeatsAConstructionOnATie() {
        // If a carrier has bothered to file the market, that fare is what it
        // sells, whatever an arbitrary adds up to.
        Constructed built = constructAtOrigin(gateway(), arbitrary());
        Fare filed = hamburgFiling("460.00");
        Optional<Fare> chosen = preferPublished(List.of(filed), Optional.of(built));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KHAM");
    }

    @Test
    void aPublishedFareBeatsAConstructionAPennyDearer() {
        Constructed built = constructAtOrigin(gateway(), arbitrary("40.00"));
        Fare filed = hamburgFiling("459.99");
        Optional<Fare> chosen = preferPublished(List.of(filed), Optional.of(built));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KHAM");
    }

    @Test
    void aCheaperConstructionWinsWhenItIsGenuinelyCheaper() {
        Constructed built = constructAtOrigin(gateway("300.00"), arbitrary("40.00"));
        Fare filed = hamburgFiling("460.00");
        Optional<Fare> chosen = preferPublished(List.of(filed), Optional.of(built));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().amount()).isEqualTo(eur("340.00"));
    }

    @Test
    void withNothingFiledTheConstructionIsWhatThereIs() {
        Constructed built = constructAtOrigin(gateway(), arbitrary());
        Optional<Fare> chosen = preferPublished(List.of(), Optional.of(built));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().origin()).isEqualTo("HAM");
    }

    @Test
    void withNothingAtAllThereIsNothing() {
        assertThat(preferPublished(List.of(), Optional.empty())).isEmpty();
    }

    @Test
    void aOneWayConstructionSaysItMayNotBeDoubled() {
        Constructed built =
                constructAtOrigin(gatewayFiling().owrt(OwRt.ONE_WAY_ONLY).build(), arbitrary());
        assertThat(oneWayOnly(built.fare())).isTrue();
        assertThat(oneWayOnly(constructAtOrigin(gateway(), arbitrary()).fare())).isFalse();
    }
}
