package io.github.rkaushikdev.gussetline.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TariffTest {

    private static Fare fare() {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KLWAP7RT")
                .amount(Money.of("420.00", "EUR"))
                .owrt(OwRt.ROUND_TRIP_HALF)
                .globalIndicator(GlobalIndicator.AT)
                .ruleNumber("1100")
                .routingNumber("0001")
                .build();
    }

    @Test
    void aFareFoldsItsPointsToCities() {
        Fare f = fare().toBuilder().origin("fra").destination("ewr").build();
        assertThat(f.market()).isEqualTo(new Market("FRA", "NYC"));
    }

    @Test
    void aFareGoesBetweenTwoPlaces() {
        assertThatThrownBy(() -> fare().withDestination("FRA")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPublishedFareIsPositive() {
        assertThatThrownBy(() -> fare().withAmount(Money.of("0", "EUR"))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> fare().withAmount(Money.of("-10", "EUR"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSaleWindowThatClosesBeforeItOpensIsRefused() {
        assertThatThrownBy(() -> fare().toBuilder()
                        .effective(LocalDate.of(2025, 6, 1))
                        .discontinue(LocalDate.of(2025, 5, 1))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aTravelWindowThatClosesBeforeItOpensIsRefused() {
        assertThatThrownBy(() -> fare().toBuilder()
                        .firstTravel(LocalDate.of(2025, 6, 1))
                        .lastTravel(LocalDate.of(2025, 5, 1))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theTwoWindowsAreReadAgainstTwoDifferentDates() {
        Fare f = fare().toBuilder()
                .effective(LocalDate.of(2025, 1, 1))
                .discontinue(LocalDate.of(2025, 3, 31))
                .firstTravel(LocalDate.of(2025, 4, 1))
                .lastTravel(LocalDate.of(2025, 10, 31))
                .build();
        assertThat(f.sellableOn(LocalDate.of(2025, 2, 14))).isTrue();
        assertThat(f.sellableOn(LocalDate.of(2025, 4, 14))).isFalse();
        assertThat(f.flyableOn(LocalDate.of(2025, 6, 14))).isTrue();
        assertThat(f.flyableOn(LocalDate.of(2025, 2, 14))).isFalse();
    }

    @Test
    void theBookingClassIsTheFirstLetterOfTheBasis() {
        assertThat(fare().withFareBasis("KLWAP7RT").bookingClass()).isEqualTo("K");
        assertThat(fare().withFareBasis("ylwap7rt").bookingClass()).isEqualTo("Y");
    }

    @Test
    void theParsedBasisIsAvailableWithoutReparsingAtTheCallSite() {
        assertThat(fare().withFareBasis("KLWAP7RT").parsedBasis().advanceDays()).contains(7);
    }

    @Test
    void aRoundTripHalfDoublesAndRefusesToStandAlone() {
        Fare f = fare().withOwrt(OwRt.ROUND_TRIP_HALF).withAmount(Money.of("420.00", "EUR"));
        assertThat(f.isHalfRoundTrip()).isTrue();
        assertThat(f.roundTripAmount()).isEqualTo(Money.of("840.00", "EUR"));
        assertThatThrownBy(f::oneWayAmount).isInstanceOf(ValidationError.class);
    }

    @Test
    void aOneWayOnlyFareStandsAloneAndRefusesToDouble() {
        Fare f = fare().withOwrt(OwRt.ONE_WAY_ONLY).withAmount(Money.of("310.00", "EUR"));
        assertThat(f.oneWayAmount()).isEqualTo(Money.of("310.00", "EUR"));
        assertThatThrownBy(f::roundTripAmount).isInstanceOf(ValidationError.class);
    }

    @Test
    void aOneWayThatMayDoubleDoesBoth() {
        Fare f = fare().withOwrt(OwRt.ONE_WAY_MAY_DOUBLE).withAmount(Money.of("310.00", "EUR"));
        assertThat(f.oneWayAmount()).isEqualTo(Money.of("310.00", "EUR"));
        assertThat(f.roundTripAmount()).isEqualTo(Money.of("620.00", "EUR"));
    }

    @Test
    void theKeyCarriesTheIndicatorBecauseTheFareDoes() {
        assertThat(fare().key()).isEqualTo(new Fare.Key("LH", "FRA", "NYC", "KLWAP7RT", GlobalIndicator.AT));
    }

    private static TariffStore store() {
        TariffStore s = new TariffStore();
        s.addMany(List.of(
                fare().withFareBasis("KLWAP7RT").withAmount(Money.of("420.00", "EUR")),
                fare().withFareBasis("QLWAP14RT").withAmount(Money.of("380.00", "EUR")),
                fare().withFareBasis("YRT")
                        .withAmount(Money.of("1980.00", "EUR"))
                        .withOwrt(OwRt.ROUND_TRIP_HALF),
                fare().withFareBasis("JRT")
                        .withAmount(Money.of("2900.00", "EUR"))
                        .withCabin(Cabin.BUSINESS),
                fare().toBuilder()
                        .carrier("LH")
                        .origin("JFK")
                        .destination("FRA")
                        .fareBasis("KLWAP7RT")
                        .amount(Money.of("510.00", "USD"))
                        .build()));
        return s;
    }

    @Test
    void theStoreCountsWhatWentIn() {
        TariffStore store = store();
        assertThat(store.size()).isEqualTo(5);
        assertThat(store.markets()).contains(new CarrierMarket("LH", "FRA", "NYC"));
    }

    @Test
    void aSearchIsDirectional() {
        TariffStore store = store();
        List<Fare> out = store.search("LH", "FRA", "JFK");
        List<Fare> back = store.search("LH", "JFK", "FRA");
        assertThat(out).hasSize(4);
        assertThat(back).hasSize(1);
        assertThat(back.get(0).amount().currency()).isEqualTo("USD");
    }

    @Test
    void aSearchComesBackCheapestFirst() {
        List<Fare> found = store().search("LH", "FRA", "JFK");
        assertThat(found)
                .extracting(f -> f.amount().amount().toString())
                .containsExactly("380.00", "420.00", "1980.00", "2900.00");
    }

    @Test
    void aSearchFiltersOnCabin() {
        TariffStore store = store();
        assertThat(store.search("LH", "FRA", "JFK", FareFilter.any().withCabin(Cabin.BUSINESS)))
                .hasSize(1);
        assertThat(store.search("LH", "FRA", "JFK", FareFilter.any().withCabin(Cabin.ECONOMY)))
                .hasSize(3);
    }

    @Test
    void aSearchFiltersOnBookingClass() {
        TariffStore store = store();
        assertThat(store.search("LH", "FRA", "JFK", FareFilter.any().withBookingClass("q")))
                .hasSize(1);
        assertThat(store.search("LH", "FRA", "JFK", FareFilter.any().withBookingClass("Z")))
                .isEmpty();
    }

    @Test
    void aSearchFiltersOnTheIndicatorAndABroaderFilingStillAnswers() {
        TariffStore s = new TariffStore();
        s.add(fare().withGlobalIndicator(GlobalIndicator.AT));
        assertThat(s.search("LH", "FRA", "JFK", FareFilter.any().withIndicator(GlobalIndicator.AT)))
                .hasSize(1);
        assertThat(s.search("LH", "FRA", "JFK", FareFilter.any().withIndicator(GlobalIndicator.SA)))
                .hasSize(1);
        assertThat(s.search("LH", "FRA", "JFK", FareFilter.any().withIndicator(GlobalIndicator.PA)))
                .isEmpty();
    }

    @Test
    void aSearchOnAMarketWeHoldNothingForIsEmptyNotAnError() {
        assertThat(store().search("LH", "FRA", "BKK")).isEmpty();
    }

    @Test
    void cheapestRaisesWhenNothingSurvivesTheFilters() {
        TariffStore store = store();
        NoFareFound caught = catchThrowableOfType(NoFareFound.class, () -> store.cheapest("LH", "FRA", "BKK"));
        assertThat(caught.details()).containsEntry("destination", "BKK");
    }

    @Test
    void cheapestRespectsTheFilters() {
        TariffStore store = store();
        assertThat(store.cheapest("LH", "FRA", "JFK").fareBasis()).isEqualTo("QLWAP14RT");
        assertThat(store.cheapest("LH", "FRA", "JFK", FareFilter.any().withCabin(Cabin.BUSINESS))
                        .fareBasis())
                .isEqualTo("JRT");
    }

    @Test
    void lookupByBasisFindsTheExactFiling() {
        assertThat(store().byBasis("LH", "FRA", "JFK", "yrt").amount()).isEqualTo(Money.of("1980.00", "EUR"));
    }

    @Test
    void lookupByBasisRaisesWhenTheBasisIsNotFiled() {
        TariffStore store = store();
        assertThatThrownBy(() -> store.byBasis("LH", "FRA", "JFK", "ZZZ")).isInstanceOf(NoFareFound.class);
    }

    @Test
    void privateAndPublishedFaresSitInTheSameStore() {
        TariffStore store = store();
        store.add(fare().withFareBasis("KLWCORP").withFareType(FareType.CORPORATE));
        assertThat(store.search("LH", "FRA", "JFK")).hasSize(5);
    }

    @Test
    void theFilingPairsOfOneMarketAreBothDirections() {
        assertThat(Tariff.filingPairs(List.of("FRA", "JFK")))
                .containsExactly(new Market("FRA", "NYC"), new Market("NYC", "FRA"));
    }

    @Test
    void theFilingPairsFoldAirportsToCities() {
        // The tariff files FRA to NYC and an itinerary says JFK. Asking for the
        // airport finds nothing and reads exactly like an uncovered market.
        assertThat(Tariff.filingPairs(List.of("FRA", "EWR"))).contains(new Market("FRA", "NYC"));
    }

    @Test
    void twoAirportsInOneCityAreOnePoint() {
        assertThat(Tariff.filingPairs(List.of("JFK", "EWR"))).isEmpty();
    }

    @Test
    void aConnectionAddsItsOwnPairs() {
        List<Market> pairs = Tariff.filingPairs(List.of("FRA", "MUC", "JFK"));
        assertThat(pairs).contains(new Market("FRA", "MUC"));
        assertThat(pairs).contains(new Market("MUC", "NYC"));
        assertThat(pairs).contains(new Market("FRA", "NYC"));
        assertThat(pairs).hasSize(6);
    }

    @Test
    void aPointNeverPairsWithItself() {
        assertThat(Tariff.filingPairs(List.of("FRA", "MUC", "FRA")))
                .allMatch(pair -> !pair.origin().equals(pair.destination()));
    }

    @Test
    void noPointsGiveNoPairs() {
        assertThat(Tariff.filingPairs(List.of())).isEmpty();
    }
}
