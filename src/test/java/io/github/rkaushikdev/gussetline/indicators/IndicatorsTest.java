package io.github.rkaushikdev.gussetline.indicators;

import static io.github.rkaushikdev.gussetline.indicators.Indicators.globalIndicator;
import static io.github.rkaushikdev.gussetline.indicators.Indicators.indicatorMatches;
import static io.github.rkaushikdev.gussetline.indicators.Indicators.isInternational;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicatorsTest {

    @Test
    void aDomesticJourneyIsDomesticEvenWithAStop() {
        assertThat(globalIndicator("JFK", "LAX")).isSameAs(GlobalIndicator.DO);
        assertThat(globalIndicator("JFK", "LAX", List.of("ORD"))).isSameAs(GlobalIndicator.DO);
        assertThat(globalIndicator("LHR", "EDI")).isSameAs(GlobalIndicator.DO);
    }

    @Test
    void europeToNorthAmericaIsAtlantic() {
        assertThat(globalIndicator("FRA", "JFK")).isSameAs(GlobalIndicator.AT);
        assertThat(globalIndicator("LHR", "ORD", List.of("JFK"))).isSameAs(GlobalIndicator.AT);
    }

    @Test
    void northAmericaToAsiaIsPacificUnlessItGoesTheOtherWay() {
        assertThat(globalIndicator("LAX", "NRT")).isSameAs(GlobalIndicator.PA);
        assertThat(globalIndicator("SFO", "HKG", List.of("NRT"))).isSameAs(GlobalIndicator.PA);
        assertThat(globalIndicator("JFK", "DEL", List.of("FRA"))).isSameAs(GlobalIndicator.AT);
    }

    @Test
    void theSameCityPairGetsTwoIndicatorsAndThatIsThePoint() {
        GlobalIndicator east = globalIndicator("JFK", "SIN", List.of("FRA"));
        GlobalIndicator west = globalIndicator("JFK", "SIN", List.of("LAX"));
        assertThat(east).isSameAs(GlobalIndicator.AT);
        assertThat(west).isSameAs(GlobalIndicator.PA);
        assertThat(east).isNotSameAs(west);
    }

    @Test
    void touchingAllThreeAreasIsARoundTheWorldConstruction() {
        assertThat(globalIndicator("FRA", "NRT", List.of("JFK", "LAX"))).isSameAs(GlobalIndicator.AP);
    }

    @Test
    void withinEuropeIsEasternHemisphere() {
        assertThat(globalIndicator("FRA", "MAD")).isSameAs(GlobalIndicator.EH);
        assertThat(globalIndicator("LHR", "IST")).isSameAs(GlobalIndicator.EH);
    }

    @Test
    void europeToAsiaIsEasternHemisphereByDefault() {
        assertThat(globalIndicator("FRA", "NRT")).isSameAs(GlobalIndicator.EH);
        assertThat(globalIndicator("LHR", "SIN", List.of("DXB"))).isSameAs(GlobalIndicator.EH);
    }

    @Test
    void routingThroughMoscowMakesItTransSiberian() {
        assertThat(globalIndicator("FRA", "NRT", List.of("SVO"))).isSameAs(GlobalIndicator.TS);
        assertThat(globalIndicator("LHR", "ICN", List.of("MOW"))).isSameAs(GlobalIndicator.TS);
    }

    @Test
    void withinTheFarEastHasItsOwnIndicator() {
        assertThat(globalIndicator("NRT", "SIN")).isSameAs(GlobalIndicator.FE);
        assertThat(globalIndicator("HKG", "SYD")).isSameAs(GlobalIndicator.FE);
    }

    @Test
    void indiaIsAreaThreeButNotFarEast() {
        assertThat(globalIndicator("DEL", "SIN")).isSameAs(GlobalIndicator.EH);
    }

    @Test
    void withinTheAmericasAcrossBordersIsWesternHemisphere() {
        assertThat(globalIndicator("JFK", "GRU")).isSameAs(GlobalIndicator.WH);
        assertThat(globalIndicator("YYZ", "MEX")).isSameAs(GlobalIndicator.WH);
    }

    @Test
    void southAmericaToAfricaIsTheSouthAtlantic() {
        assertThat(globalIndicator("GRU", "JNB")).isSameAs(GlobalIndicator.SA);
        assertThat(globalIndicator("JNB", "EZE")).isSameAs(GlobalIndicator.SA);
    }

    @Test
    void northAmericaToAfricaIsThePlainAtlantic() {
        assertThat(globalIndicator("JFK", "JNB")).isSameAs(GlobalIndicator.AT);
    }

    @Test
    void anEmptyEndIsRefused() {
        assertThatThrownBy(() -> globalIndicator("", "JFK")).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> globalIndicator("FRA", "")).isInstanceOf(ValidationError.class);
    }

    @Test
    void isInternationalReadsTheCountryNotTheArea() {
        assertThat(isInternational("FRA", "MUC")).isFalse();
        assertThat(isInternational("FRA", "ZRH")).isTrue();
        assertThat(isInternational("LHR", "EDI")).isFalse();
    }

    @Test
    void aBroaderIndicatorPricesANarrowerJourney() {
        assertThat(indicatorMatches(GlobalIndicator.EH, GlobalIndicator.TS)).isTrue();
        assertThat(indicatorMatches(GlobalIndicator.EH, GlobalIndicator.FE)).isTrue();
        assertThat(indicatorMatches(GlobalIndicator.AT, GlobalIndicator.SA)).isTrue();
    }

    @Test
    void aNarrowerIndicatorDoesNotPriceABroaderJourney() {
        assertThat(indicatorMatches(GlobalIndicator.TS, GlobalIndicator.EH)).isFalse();
        assertThat(indicatorMatches(GlobalIndicator.SA, GlobalIndicator.AT)).isFalse();
        assertThat(indicatorMatches(GlobalIndicator.PA, GlobalIndicator.AT)).isFalse();
    }

    @Test
    void anIndicatorAlwaysMatchesItself() {
        for (GlobalIndicator value : GlobalIndicator.values()) {
            assertThat(indicatorMatches(value, value)).isTrue();
        }
    }
}
