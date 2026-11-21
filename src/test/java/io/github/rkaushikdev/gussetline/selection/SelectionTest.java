package io.github.rkaushikdev.gussetline.selection;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.Fixtures.tightMiles;
import static io.github.rkaushikdev.gussetline.Fixtures.transatlanticMiles;
import static io.github.rkaushikdev.gussetline.selection.Selection.applySelection;
import static io.github.rkaushikdev.gussetline.selection.Selection.select;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.routing.RoutingMap;
import io.github.rkaushikdev.gussetline.routing.RoutingStore;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SelectionTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();
    private final MileageTable transatlanticMiles = transatlanticMiles();
    private final MileageTable tightMiles = tightMiles();

    private static FareComponent direct() {
        return direct("K");
    }

    private static FareComponent direct(String bookingClass) {
        return new FareComponent(
                List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05", bookingClass)));
    }

    private static FareComponent viaLondon() {
        return new FareComponent(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
    }

    private static FareComponent viaDubai() {
        return new FareComponent(List.of(
                segment("LH", "630", "FRA", "DXB", "2025-03-04 10:00", "2025-03-04 18:30"),
                segment("EK", "201", "DXB", "JFK", "2025-03-06 08:00", "2025-03-06 14:20")));
    }

    /** The fixture fare from Frankfurt to New York under another basis and amount. */
    private static Fare filed(String basis, String amount) {
        return fare("FRA", "JFK", amount).withFareBasis(basis);
    }

    private static TariffStore tariffOf(Fare... fares) {
        TariffStore store = new TariffStore();
        store.addMany(List.of(fares));
        return store;
    }

    private Selection selectDirect(TariffStore store) {
        return select(direct(), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING);
    }

    @Test
    void aComponentWithNoFlownSectorCannotBePriced() {
        assertThatThrownBy(() -> select(
                        new FareComponent(List.of(surface("JFK", "BOS"))),
                        new TariffStore(),
                        new RoutingStore(),
                        transatlanticMiles,
                        flatConverter,
                        TICKETING))
                .isInstanceOf(NoFareFound.class);
    }

    @Test
    void nothingPublishedIsARefusalThatNamesTheMarket() {
        NoFareFound caught = catchThrowableOfType(NoFareFound.class, () -> selectDirect(new TariffStore()));
        assertThat(caught.details()).containsEntry("destination", "NYC");
    }

    @Test
    void theCheapestSurvivingFareWins() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("QLWAP14RT", "380.00"));
        Trace trace = new Trace();
        Selection chosen =
                select(direct("Q"), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING, trace);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("QLWAP14RT");
        assertThat(trace.forStage(Stage.SELECTION)).hasSize(1);
    }

    @Test
    void aFareWhoseClassIsNotBookedIsRefused() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("QLWAP14RT", "380.00"));
        Selection chosen = select(direct("K"), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("KLWAP7RT");
        assertThat(chosen.reasonFor("QLWAP14RT")).contains("no sector booked in Q");
    }

    @Test
    void aFareOnTheWrongIndicatorIsRefused() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00").withGlobalIndicator(GlobalIndicator.PA));
        assertThatThrownBy(() -> selectDirect(store)).isInstanceOf(NoFareFound.class);
    }

    @Test
    void aBroaderIndicatorStillPricesTheComponent() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00").withGlobalIndicator(GlobalIndicator.AT));
        assertThat(selectDirect(store).chosen().fare().fareBasis()).isEqualTo("KLWAP7RT");
    }

    @Test
    void aRoutingThatRefusesThePathDropsTheFare() {
        RoutingStore routings = new RoutingStore();
        RoutingMap routed = new RoutingMap("0101");
        routed.add("FRA", "NYC", List.of("LH"));
        routings.add("LH", routed);

        TariffStore store = tariffOf(
                filed("KLWAP7RT", "420.00").withRoutingNumber("0101"),
                filed("KHXRT", "980.00").withRoutingNumber("0000"));

        Selection chosen = select(viaLondon(), store, routings, transatlanticMiles, flatConverter, TICKETING);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("KHXRT");
        assertThat(chosen.reasonFor("KLWAP7RT")).contains("routing 0101 refuses the path");
    }

    @Test
    void aFareOverTheMileageLadderIsRefused() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"));
        NoFareFound caught = catchThrowableOfType(
                NoFareFound.class,
                () -> select(viaDubai(), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING));
        assertThat(caught.details()).containsEntry("refused", 1);
    }

    @Test
    void everyRefusalIsRecordedWhenNothingSurvives() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00").withGlobalIndicator(GlobalIndicator.PA));
        Trace trace = new Trace();
        assertThatThrownBy(() -> select(
                        direct(), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING, trace))
                .isInstanceOf(NoFareFound.class);
        assertThat(trace.stageFailed(Stage.SELECTION)).isTrue();
        assertThat(trace.reasons().get(0)).contains("filed PA, journey is AT");
    }

    @Test
    void aFareWeDidNotRefuseHasNoReason() {
        Selection chosen = selectDirect(tariffOf(filed("KLWAP7RT", "420.00")));
        assertThat(chosen.reasonFor("KLWAP7RT")).isEmpty();
    }

    @Test
    void theTotalCarriesTheMileageBandAndTheBaseDoesNot() {
        // The routing through London puts the component into the five percent
        // band. The number that leaves here has to be the surcharged one, because
        // it is compared against what the supplier quoted.
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("KHXRT", "410.00"));
        Selection chosen = select(viaLondon(), store, new RoutingStore(), tightMiles, flatConverter, TICKETING);
        assertThat(chosen.chosen().band()).isEqualTo("5M");
        assertThat(chosen.chosen().baseNuc()).isEqualByComparingTo("410.00");
        assertThat(chosen.chosen().surcharge()).isEqualByComparingTo("20.50");
        assertThat(chosen.chosen().totalNuc()).isEqualByComparingTo("430.50");
    }

    @Test
    void theBandIsTheSameForEveryCandidateOnOneComponent() {
        // The surcharge is a percentage of the fare, so it scales the survivors
        // together and cannot put a dearer fare in front of a cheaper one.
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("KHXRT", "410.00"));
        Selection chosen = select(viaLondon(), store, new RoutingStore(), tightMiles, flatConverter, TICKETING);
        Set<String> bands = chosen.considered().stream().map(Candidate::band).collect(Collectors.toSet());
        assertThat(bands).containsExactly("5M");
        assertThat(chosen.chosen().baseNuc()).isLessThan(new BigDecimal("420.00"));
    }

    @Test
    void theRunnerUpIsTheNextCheapest() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("QLWAP14RT", "380.00"));
        Selection chosen = select(direct("Q"), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING);
        // Only the Q class fare is bookable, so there is no runner up.
        assertThat(chosen.runnerUp()).isEmpty();
    }

    @Test
    void withTwoSurvivorsThereIsARunnerUp() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"), filed("KHWAP3RT", "460.00"));
        Selection chosen = select(direct("K"), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("KLWAP7RT");
        assertThat(chosen.runnerUp())
                .hasValueSatisfying(
                        runnerUp -> assertThat(runnerUp.fare().fareBasis()).isEqualTo("KHWAP3RT"));
    }

    @Test
    void aCandidateReadsBackWithItsBand() {
        Selection chosen = selectDirect(tariffOf(filed("KLWAP7RT", "420.00")));
        assertThat(chosen.chosen()).hasToString("KLWAP7RT 420.00 NUC M");
    }

    @Test
    void applyingTheSelectionPricesTheComponent() {
        Selection chosen = selectDirect(tariffOf(filed("KLWAP7RT", "420.00")));
        FareComponent priced = applySelection(direct(), chosen);
        assertThat(priced.nuc()).hasValueSatisfying(nuc -> assertThat(nuc).isEqualByComparingTo("420.00"));
        assertThat(priced.totalNuc()).isEqualByComparingTo("420.00");
        assertThat(priced.notes()).isEmpty();
    }

    @Test
    void applyingASurchargedSelectionCarriesTheBand() {
        TariffStore store = tariffOf(filed("KLWAP7RT", "420.00"));
        Selection chosen = select(viaLondon(), store, new RoutingStore(), tightMiles, flatConverter, TICKETING);
        FareComponent priced = applySelection(viaLondon(), chosen);
        assertThat(priced.notes()).containsExactly("5M");
        assertThat(priced.totalNuc()).isEqualByComparingTo("441.00");
    }

    @Test
    void aBusinessFareIsNotDroppedForBeingDearer() {
        TariffStore store = tariffOf(filed("JRT", "2900.00").withCabin(Cabin.BUSINESS));
        Selection chosen = select(direct("J"), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING);
        assertThat(chosen.chosen().fare().cabin()).isSameAs(Cabin.BUSINESS);
    }

    @Test
    void aRoundTripHalfIsNotOfferedToAOneWay() {
        // A half is not a cheap fare, it is half of one. Considering it lets it
        // win on price and fail at assembly, which reads as a broken engine
        // rather than as a market with no one way filed.
        TariffStore store =
                tariffOf(filed("KHALF", "420.00"), filed("KOW", "560.00").withOwrt(OwRt.ONE_WAY_ONLY));

        Selection chosen =
                select(direct(), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING, true);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("KOW");
        assertThat(chosen.rejected())
                .anySatisfy(refusal -> assertThat(refusal.reason()).contains("cannot stand on its own"));
    }

    @Test
    void aRoundTripHalfIsTheCheapestFareWhenItMayBeUsed() {
        TariffStore store =
                tariffOf(filed("KHALF", "420.00"), filed("KOW", "560.00").withOwrt(OwRt.ONE_WAY_ONLY));

        Selection chosen = selectDirect(store);
        assertThat(chosen.chosen().fare().fareBasis()).isEqualTo("KHALF");
    }

    @Test
    void aOneWayWithNothingButHalvesFiledSaysWhichRuleRefused() {
        TariffStore store = tariffOf(filed("KHALF", "420.00"));

        assertThatThrownBy(() ->
                        select(direct(), store, new RoutingStore(), transatlanticMiles, flatConverter, TICKETING, true))
                .isInstanceOf(NoFareFound.class)
                .hasMessageContaining("every published fare was refused");
    }
}
